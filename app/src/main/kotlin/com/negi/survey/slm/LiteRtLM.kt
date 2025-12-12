/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: LiteRtLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LiteRtLM"

/** LiteRT-LM defaults (independent from MediaPipe SLM defaults). */
private const val DEFAULT_MAX_TOKEN = 512
private const val DEFAULT_TOPK = 40
private const val DEFAULT_TOPP = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/** Upper bound for error strings rendered in UI/log aggregation. */
private const val ERROR_MAX_CHARS = 240

/**
 * Simple holder for a LiteRT-LM [Engine] and its active [Conversation].
 */
data class LiteRtLmInstance(
    val engine: Engine,
    var conversation: Conversation,
)

/**
 * High-level object for LiteRT-LM integration.
 *
 * Responsibilities:
 *  - Create and initialize LiteRT-LM [Engine] and [Conversation].
 *  - Keep instances in an internal map keyed by a runtime key derived from [Model].
 *  - Provide a callback-based streaming API ([runInference]).
 *  - Provide a suspend API ([generateText]) that serializes calls.
 *  - Manage cleanup hooks via [cleanUp] and [resetConversation].
 *
 * Notes:
 *  - This object does NOT share state with MediaPipe SLM runtime.
 *  - Callers should gate concurrent inference per runtime key. This object also
 *    includes a best-effort in-flight guard to prevent accidental overlap.
 */
object LiteRtLM {

    /**
     * Per-runtime cleanup listener invoked when the model instance has been fully
     * cleaned up (from [cleanUp] or stream terminal paths).
     */
    private val cleanUpListeners: MutableMap<String, () -> Unit> = ConcurrentHashMap()

    /** Per-runtime LiteRT-LM runtime instances keyed by [runtimeKey]. */
    private val instances: MutableMap<String, LiteRtLmInstance> = ConcurrentHashMap()

    /**
     * Set of runtime keys that currently have an active streaming inference.
     *
     * This is a defensive guard: it prevents accidental overlap (e.g., UI double taps,
     * repository gate bugs, etc.). The primary concurrency contract still lives at a
     * higher level, but this makes failures explicit and safer.
     */
    private val activeStreams: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Mutex to serialize initialization and [generateText] calls.
     *
     * Repository-level streaming ([runInference]) is gated separately.
     */
    private val mutex: Mutex = Mutex()

    /**
     * Simple volatile busy flag used only by [generateText] (suspend API).
     *
     * This does not represent repository streaming state; use [activeStreams] for that.
     */
    @Volatile
    private var busy: Boolean = false

    /** Main thread handler for UI-safe callbacks. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /**
     * Returns `true` when a [generateText] call is currently in progress.
     *
     * This is not used for repository-based streaming.
     */
    fun isBusy(): Boolean = busy

    /**
     * Stable runtime key for instances and cleanup listeners.
     *
     * For now this uses [Model.name] directly. If you later need to distinguish
     * different engine configs for the same name, update this function.
     */
    private fun runtimeKey(model: Model): String = model.name

    /**
     * Post work onto the main thread.
     *
     * @param block Work to execute on the main thread.
     */
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * Low-level initializer for LiteRT-LM [Engine] and [Conversation].
     *
     * This is typically wrapped by [initializeIfNeeded].
     *
     * @param context Android [Context] used to resolve model path and cache dir.
     * @param model Model descriptor (name, path, config).
     * @param supportImage Whether vision input should be enabled for this model.
     * @param supportAudio Whether audio input should be enabled for this model.
     * @param onDone Called with an empty string on success or an error message.
     * @param systemMessage Optional system message for the conversation.
     * @param tools Optional tools passed into the conversation configuration.
     */
    fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
        val temperature = sanitizeTemperature(
            model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE),
        )
        val accelerator = model.getStringConfigValue(
            ConfigKey.ACCELERATOR,
            Accelerator.GPU.label,
        )

        val key = runtimeKey(model)

        Log.d(TAG, "Initializing LiteRT-LM engine for model='${model.name}', key='$key'")
        Log.d(TAG, "Enable image: $supportImage, enable audio: $supportAudio")

        val preferredBackend = when (accelerator) {
            Accelerator.CPU.label -> Backend.CPU
            Accelerator.GPU.label -> Backend.GPU
            else -> Backend.CPU
        }
        Log.d(TAG, "Preferred backend: $preferredBackend")

        val modelPath = model.getPath()
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = preferredBackend,
            /**
             * Vision must be GPU for vision-capable Gemma variants (if enabled).
             */
            visionBackend = if (supportImage) Backend.GPU else null,
            /**
             * Audio must be CPU for Gemma-3n (if enabled).
             */
            audioBackend = if (supportAudio) Backend.CPU else null,
            maxNumTokens = maxTokens.coerceAtLeast(1),
            cacheDir = if (modelPath.startsWith("/data/local/tmp")) {
                context.getExternalFilesDir(null)?.absolutePath
            } else {
                null
            },
        )

        var engine: Engine? = null

        try {
            /** Create and initialize the engine. */
            engine = Engine(engineConfig)
            engine.initialize()

            /**
             * If there was an existing instance for this runtime key, close it defensively.
             */
            instances.remove(key)?.let { old ->
                runCatching { old.conversation.close() }
                    .onFailure { Log.w(TAG, "Failed to close old conversation: ${it.message}", it) }
                runCatching { old.engine.close() }
                    .onFailure { Log.w(TAG, "Failed to close old engine: ${it.message}", it) }
            }

            val conversationConfig = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = topK,
                    topP = topP.toDouble(),
                    temperature = temperature.toDouble(),
                ),
                systemMessage = systemMessage,
                tools = tools,
            )

            val conversation = engine.createConversation(conversationConfig)
            instances[key] = LiteRtLmInstance(engine = engine, conversation = conversation)

            Log.d(TAG, "LiteRT-LM initialization succeeded for model='${model.name}', key='$key'")
            postToMain { onDone("") }
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT-LM initialization failed: ${e.message}", e)
            /**
             * Best-effort cleanup of a partially initialized engine.
             */
            runCatching { engine?.close() }
                .onFailure { Log.w(TAG, "Failed to close LiteRT-LM engine after init failure: ${it.message}", it) }
            postToMain { onDone(cleanError(e.message)) }
        }
    }

    /**
     * Suspend-style convenience initializer.
     *
     * This method:
     *  - Returns immediately if an instance already exists for this runtime key.
     *  - Uses [mutex] to avoid concurrent double initialization.
     *  - Offloads heavy initialization work to [Dispatchers.IO].
     *  - Throws [IllegalStateException] if initialization fails.
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)
        if (instances.containsKey(key)) {
            return
        }

        mutex.withLock {
            if (instances.containsKey(key)) {
                /** Another coroutine finished initialization while we waited. */
                return
            }

            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<Unit> { cont ->
                    initialize(
                        context = context,
                        model = model,
                        supportImage = supportImage,
                        supportAudio = supportAudio,
                        onDone = { error ->
                            if (error.isEmpty()) {
                                if (cont.isActive) cont.resume(Unit)
                            } else {
                                if (cont.isActive) {
                                    cont.resumeWithException(
                                        IllegalStateException("LiteRT-LM initialization failed: $error"),
                                    )
                                }
                            }
                        },
                        systemMessage = systemMessage,
                        tools = tools,
                    )
                }
            }
        }
    }

    /**
     * Reset the current [Conversation] for the given [model] while reusing the [Engine].
     *
     * This is useful when you want to clear chat history but avoid reloading the model.
     *
     * Note:
     *  - Vision/audio capabilities are determined at engine initialization time.
     *  - The [supportImage]/[supportAudio] parameters are logged for clarity but do not
     *    change the underlying engine backends.
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        /**
         * Resetting while streaming is risky; it can invalidate callbacks and produce
         * undefined SDK states. Prefer cancel/cleanup first at a higher level.
         */
        if (activeStreams.contains(key)) {
            Log.w(TAG, "resetConversation rejected: active stream in progress for key='$key'")
            return
        }

        try {
            Log.d(TAG, "Resetting LiteRT-LM conversation for model='${model.name}', key='$key'")

            val instance = instances[key] ?: return
            runCatching { instance.conversation.close() }
                .onFailure { Log.w(TAG, "Failed to close old conversation: ${it.message}", it) }

            val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
            val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
            val temperature = sanitizeTemperature(
                model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE),
            )

            Log.d(TAG, "Enable image: $supportImage, enable audio: $supportAudio")

            val newConversation = instance.engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = topK,
                        topP = topP.toDouble(),
                        temperature = temperature.toDouble(),
                    ),
                    systemMessage = systemMessage,
                    tools = tools,
                ),
            )

            instance.conversation = newConversation
            Log.d(TAG, "LiteRT-LM conversation reset completed for model='${model.name}', key='$key'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset LiteRT-LM conversation: ${e.message}", e)
        }
    }

    /**
     * Fully release the [Engine] and [Conversation] for the given [model].
     *
     * After calling this method, you must call [initializeIfNeeded] again before
     * requesting inference for the same runtime key.
     */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)

        /**
         * Do not eagerly remove [activeStreams] here: if a stream is currently active,
         * removing it would allow a new request to slip in before callbacks terminate.
         * The stream terminal paths will remove the key.
         */
        val instance = instances.remove(key)
        if (instance == null) {
            cleanUpListeners.remove(key)
            postToMain { onDone() }
            return
        }

        runCatching { instance.conversation.close() }
            .onFailure { Log.e(TAG, "Failed to close LiteRT-LM conversation: ${it.message}", it) }

        runCatching { instance.engine.close() }
            .onFailure { Log.e(TAG, "Failed to close LiteRT-LM engine: ${it.message}", it) }

        /**
         * Notify any registered listener (e.g., repository watchdogs).
         * Note: A streaming terminal path might also try to invoke it; remove() makes it single-shot.
         */
        cleanUpListeners.remove(key)?.let { listener ->
            postToMain { listener.invoke() }
        }

        /**
         * If cleanup was forced externally, the stream should end shortly; if not,
         * we still clear the guard to prevent permanent lock due to missing callbacks.
         */
        activeStreams.remove(key)

        postToMain { onDone() }
        Log.d(TAG, "LiteRT-LM clean up done for model='${model.name}', key='$key'")
    }

    /**
     * Build [Content] list for a single message.
     *
     * @param input Text input appended after multimodal inputs.
     * @param images Image list to include as PNG bytes.
     * @param audioClips Audio byte arrays.
     */
    private fun buildContents(
        input: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
    ): List<Content> {
        val contents = mutableListOf<Content>()

        /**
         * Multimodal-first ordering (image/audio then text).
         * Adjust if a specific model/prompt template expects text first.
         */
        for (image in images) {
            contents.add(Content.ImageBytes(image.toPngByteArray()))
        }
        for (audioClip in audioClips) {
            contents.add(Content.AudioBytes(audioClip))
        }
        if (input.trim().isNotEmpty()) {
            contents.add(Content.Text(input))
        }

        return contents
    }

    /**
     * Low-level callback-based inference API.
     *
     * Contract:
     *  - The stream must be terminated exactly once with `done=true`.
     *  - Caller should ensure only one inference per runtime key is running.
     *    This method also enforces a best-effort in-flight guard.
     *
     * Threading:
     *  - [resultListener], [cleanUpListener], and [onError] are invoked on Main.
     */
    fun runInference(
        model: Model,
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
    ) {
        val key = runtimeKey(model)
        val instance = instances[key]

        if (instance == null) {
            val msg = "LiteRT-LM model '${model.name}' is not initialized. Call initializeIfNeeded() first."
            Log.w(TAG, msg)
            postToMain {
                /** Emit error first so suspend wrappers can fail reliably. */
                onError(msg)
                resultListener("", true)
            }
            return
        }

        /**
         * Best-effort guard against overlapping streams on the same key.
         */
        if (!activeStreams.add(key)) {
            val msg = "LiteRT-LM runInference rejected: another stream is already active for key='$key'."
            Log.w(TAG, msg)
            postToMain {
                onError(msg)
                resultListener("", true)
            }
            return
        }

        /**
         * Register cleanup listener for this key. Warn if overwritten (contract violation).
         */
        val previous = cleanUpListeners.put(key, cleanUpListener)
        if (previous != null) {
            Log.w(TAG, "LiteRT-LM cleanup listener overwritten for key='$key' (overlapping calls?)")
        }

        val conversation = instance.conversation
        val contents = buildContents(input = input, images = images, audioClips = audioClips)

        /**
         * Terminal guard ensures we signal done exactly once.
         */
        val didTerminate = AtomicBoolean(false)

        /**
         * Streaming text normalization:
         * Some SDKs emit cumulative text, others emit deltas.
         * We normalize to delta before invoking [resultListener].
         */
        var lastFullText = ""

        fun terminateSafely(errorMessage: String? = null) {
            if (!didTerminate.compareAndSet(false, true)) {
                return
            }

            activeStreams.remove(key)

            postToMain {
                /**
                 * Emit error first so suspend wrappers can fail reliably.
                 */
                if (!errorMessage.isNullOrBlank()) {
                    onError(errorMessage)
                }

                /**
                 * Always send terminal signal.
                 */
                resultListener("", true)

                /**
                 * Invoke and clear cleanup listener.
                 * If [cleanUp] already removed it, this becomes a no-op.
                 */
                cleanUpListeners.remove(key)?.invoke()
            }
        }

        try {
            conversation.sendMessageAsync(
                Message.of(contents),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val full = message.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString(separator = "") { it.text }

                        if (full.isEmpty()) {
                            Log.d(TAG, "LiteRT-LM onMessage with non-text payload for model='${model.name}', key='$key'")
                            return
                        }

                        /**
                         * If the SDK provides cumulative text, emit only the delta.
                         * If it provides delta already, this still works (fallback branch).
                         */
                        val delta = if (full.startsWith(lastFullText)) {
                            full.substring(lastFullText.length)
                        } else {
                            full
                        }
                        lastFullText = full

                        if (delta.isNotEmpty()) {
                            postToMain {
                                resultListener(delta, false)
                            }
                        }
                    }

                    override fun onDone() {
                        terminateSafely()
                    }

                    override fun onError(throwable: Throwable) {
                        if (throwable is CancellationException || throwable is kotlinx.coroutines.CancellationException) {
                            Log.i(TAG, "LiteRT-LM inference cancelled for model='${model.name}', key='$key'.")
                            terminateSafely()
                        } else {
                            Log.e(TAG, "LiteRT-LM onError for key='$key'", throwable)
                            terminateSafely("Error: ${cleanError(throwable.message)}")
                        }
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT-LM sendMessageAsync failed for key='$key': ${e.message}", e)
            terminateSafely(cleanError(e.message))
        }
    }

    /**
     * High-level suspend API that:
     *  - Serializes all requests via a [Mutex].
     *  - Exposes streaming partial text via [onPartial].
     *  - Returns the final concatenated response as a [String].
     *
     * Note:
     *  - You must call [initializeIfNeeded] successfully before using this.
     *  - This API should not be used concurrently with repository streaming for the
     *    same runtime key.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
    ): String = mutex.withLock {
        if (busy) {
            throw IllegalStateException("LiteRT-LM is already busy with another request.")
        }
        busy = true

        try {
            suspendCancellableCoroutine { cont ->
                val buffer = StringBuilder()
                val sawError = AtomicBoolean(false)

                runInference(
                    model = model,
                    input = input,
                    resultListener = { partial, done ->
                        if (partial.isNotEmpty()) {
                            buffer.append(partial)
                            runCatching { onPartial(partial) }
                                .onFailure { t ->
                                    Log.w(TAG, "onPartial callback failed: ${t.message}", t)
                                }
                        }

                        if (done && cont.isActive && !sawError.get()) {
                            cont.resume(buffer.toString())
                        }
                    },
                    cleanUpListener = {
                        Log.d(TAG, "LiteRT-LM clean-up listener invoked for model='${model.name}', key='${runtimeKey(model)}'")
                    },
                    onError = { message ->
                        sawError.set(true)
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("LiteRT-LM generation error: $message"),
                            )
                        }
                    },
                    images = images,
                    audioClips = audioClips,
                )

                cont.invokeOnCancellation {
                    /**
                     * LiteRT-LM does not expose an explicit cancel API for conversations.
                     * If a cancel hook is added in the future, it can be invoked here.
                     */
                    Log.i(TAG, "LiteRT-LM coroutine cancelled for model='${model.name}', key='${runtimeKey(model)}'.")
                }
            }
        } finally {
            busy = false
        }
    }

    /**
     * Convert this [Bitmap] to a PNG-encoded [ByteArray].
     */
    private fun Bitmap.toPngByteArray(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            this.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /**
     * Sanitize TopK - must be >= 1.
     */
    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)

    /**
     * Sanitize TopP - must be in [0, 1].
     */
    private fun sanitizeTopP(p: Float): Float = p.takeIf { it in 0f..1f } ?: DEFAULT_TOPP

    /**
     * Sanitize Temperature - typical safe band [0, 2].
     */
    private fun sanitizeTemperature(t: Float): Float = t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    /**
     * Clean and compress error messages for UI/logging.
     */
    private fun cleanError(msg: String?): String {
        return msg
            ?.replace("INTERNAL:", "", ignoreCase = true)
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.take(ERROR_MAX_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown error"
    }
}
