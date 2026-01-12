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
import com.negi.survey.slm.LiteRtLM.cleanUp
import com.negi.survey.slm.LiteRtLM.generateText
import com.negi.survey.slm.LiteRtLM.mutex
import com.negi.survey.slm.LiteRtLM.resetConversation
import com.negi.survey.slm.LiteRtLM.runInference
import com.negi.survey.slm.LiteRtLM.runtimeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 *
 * @property supportImage The engine was initialized with vision backend enabled if true.
 * @property supportAudio The engine was initialized with audio backend enabled if true.
 * @property engineConfigSnapshot Snapshot for debug/logging and mismatch warnings.
 */
data class LiteRtLmInstance(
    val engine: Engine,
    @Volatile var conversation: Conversation,
    val supportImage: Boolean,
    val supportAudio: Boolean,
    val engineConfigSnapshot: EngineConfig,
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

    /** Main thread handler for UI-safe callbacks. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /** Lock for state transitions across instances/listeners/flags. */
    private val stateLock = Any()

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
     * Defensive guard: prevents accidental overlap (UI double taps, etc.).
     */
    private val activeStreams: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Pending cleanup requests keyed by runtime key.
     *
     * If cleanup is requested during streaming, we defer engine/conversation close until
     * the stream terminates (onDone/onError). This avoids undefined SDK behavior.
     */
    private val pendingCleanupCallbacks: MutableMap<String, MutableList<() -> Unit>> =
        ConcurrentHashMap()

    /**
     * Keys currently initializing.
     *
     * This prevents concurrent initialize() calls from racing and closing each other’s engines.
     */
    private val initInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Mutex to serialize initialization and [generateText] calls.
     *
     * Repository-level streaming ([runInference]) is gated separately.
     */
    private val mutex: Mutex = Mutex()

    /** Busy flag used only by [generateText] (suspend API). */
    private val busy: AtomicBoolean = AtomicBoolean(false)

    /**
     * Returns `true` when a [generateText] call is currently in progress.
     *
     * This is not used for repository-based streaming.
     */
    fun isBusy(): Boolean = busy.get()

    /**
     * Stable runtime key for instances and cleanup listeners.
     *
     * Keeping this as model.name preserves your current behavior,
     * but we add strict config mismatch warnings and safe re-init pathways.
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
     * Normalize accelerator string for stable backend selection.
     */
    private fun normalizedAccelerator(model: Model): String {
        return model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)
            .trim()
            .uppercase()
            .ifBlank { Accelerator.GPU.label }
    }

    /**
     * Resolve preferred backend from model config.
     */
    private fun preferredBackend(model: Model): Backend {
        return when (normalizedAccelerator(model)) {
            Accelerator.CPU.label -> Backend.CPU
            Accelerator.GPU.label -> Backend.GPU
            else -> Backend.GPU
        }
    }

    /**
     * Low-level initializer for LiteRT-LM [Engine] and [Conversation].
     *
     * This method is non-suspending; it always performs heavy work off the main thread.
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
        val key = runtimeKey(model)

        /**
         * Prevent concurrent initialize() for the same key.
         */
        val accepted = initInFlight.add(key)
        if (!accepted) {
            postToMain { onDone("Initialization already in progress for key='$key'.") }
            return
        }

        /** Run initialization off the main thread to avoid UI jank. */
        thread(name = "LiteRtLM-init-$key") {
            var engine: Engine? = null

            try {
                val maxTokens =
                    model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN).coerceAtLeast(1)
                val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
                val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
                val temperature =
                    sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                val backend = preferredBackend(model)

                Log.d(TAG, "Initializing LiteRT-LM engine for model='${model.name}', key='$key'")
                Log.d(TAG, "Enable image: $supportImage, enable audio: $supportAudio")
                Log.d(TAG, "Preferred backend: $backend, maxTokens=$maxTokens, topK=$topK, topP=$topP, temp=$temperature")

                val modelPath = model.getPath()

                val cacheDirPath = runCatching {
                    /** Prefer app-private cache for safety. */
                    context.cacheDir?.absolutePath
                }.getOrNull()

                fun buildConfig(forBackend: Backend): EngineConfig {
                    return EngineConfig(
                        modelPath = modelPath,
                        backend = forBackend,
                        /**
                         * Vision must be GPU for vision-capable Gemma variants (if enabled).
                         */
                        visionBackend = if (supportImage) Backend.GPU else null,
                        /**
                         * Audio must be CPU for Gemma-3n (if enabled).
                         */
                        audioBackend = if (supportAudio) Backend.CPU else null,
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDirPath,
                    )
                }

                var engineConfig = buildConfig(backend)

                /**
                 * Create and initialize the engine.
                 * If GPU fails and we are NOT using vision/audio, fall back to CPU.
                 */
                runCatching {
                    engine = Engine(engineConfig)
                    engine!!.initialize()
                }.getOrElse { first ->
                    if (backend == Backend.GPU && !supportImage && !supportAudio) {
                        Log.w(TAG, "GPU init failed. Falling back to CPU: ${first.message}")
                        engineConfig = buildConfig(Backend.CPU)
                        engine = Engine(engineConfig)
                        engine.initialize()
                    } else {
                        throw first
                    }
                }

                synchronized(stateLock) {
                    if (activeStreams.contains(key)) {
                        throw IllegalStateException(
                            "Initialization rejected: active stream in progress for key='$key'.",
                        )
                    }

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

                    val conversation = engine!!.createConversation(conversationConfig)
                    instances[key] = LiteRtLmInstance(
                        engine = engine,
                        conversation = conversation,
                        supportImage = supportImage,
                        supportAudio = supportAudio,
                        engineConfigSnapshot = engineConfig,
                    )
                }

                Log.d(TAG, "LiteRT-LM initialization succeeded for model='${model.name}', key='$key'")
                postToMain { onDone("") }
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM initialization failed: ${e.message}", e)
                runCatching { engine?.close() }
                    .onFailure { Log.w(TAG, "Failed to close engine after init failure: ${it.message}", it) }
                postToMain { onDone(cleanError(e.message)) }
            } finally {
                initInFlight.remove(key)
            }
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
        synchronized(stateLock) {
            if (instances.containsKey(key)) return
        }

        mutex.withLock {
            synchronized(stateLock) {
                if (instances.containsKey(key)) return
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
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        synchronized(stateLock) {
            if (activeStreams.contains(key)) {
                Log.w(TAG, "resetConversation rejected: active stream in progress for key='$key'")
                return
            }
        }

        try {
            Log.d(TAG, "Resetting LiteRT-LM conversation for model='${model.name}', key='$key'")

            val instance = synchronized(stateLock) { instances[key] } ?: return

            if (instance.supportImage != supportImage || instance.supportAudio != supportAudio) {
                Log.w(
                    TAG,
                    "resetConversation called with different capabilities than initialization. " +
                            "init(image=${instance.supportImage}, audio=${instance.supportAudio}) vs " +
                            "reset(image=$supportImage, audio=$supportAudio). " +
                            "Engine backends will NOT change; reinitialize if needed.",
                )
            }

            runCatching { instance.conversation.close() }
                .onFailure { Log.w(TAG, "Failed to close old conversation: ${it.message}", it) }

            val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
            val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
            val temperature =
                sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

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

            synchronized(stateLock) {
                val latest = instances[key]
                if (latest == null) {
                    runCatching { newConversation.close() }
                    Log.w(TAG, "resetConversation aborted: instance removed during reset for key='$key'")
                    return
                }
                latest.conversation = newConversation
            }

            Log.d(TAG, "LiteRT-LM conversation reset completed for model='${model.name}', key='$key'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset LiteRT-LM conversation: ${e.message}", e)
        }
    }

    /**
     * Fully release the [Engine] and [Conversation] for the given [model].
     *
     * If cleanup is requested while a stream is active, cleanup is deferred until the
     * stream terminates.
     */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)

        val shouldDefer = synchronized(stateLock) {
            if (activeStreams.contains(key)) {
                val list = pendingCleanupCallbacks.getOrPut(key) { mutableListOf() }
                list.add(onDone)
                true
            } else {
                false
            }
        }

        if (shouldDefer) {
            Log.w(TAG, "LiteRT-LM cleanUp deferred: active stream in progress for key='$key'")
            return
        }

        val instance = synchronized(stateLock) { instances.remove(key) }
        if (instance == null) {
            synchronized(stateLock) {
                cleanUpListeners.remove(key)
                pendingCleanupCallbacks.remove(key)
            }
            postToMain { onDone() }
            return
        }

        doCleanupInternal(
            key = key,
            instance = instance,
            extraCallbacks = listOf(onDone),
        )
    }

    /**
     * Background cleanup worker.
     *
     * @param key Runtime key.
     * @param instance Instance to close.
     * @param extraCallbacks Callbacks to invoke on Main after closing.
     */
    private fun doCleanupInternal(
        key: String,
        instance: LiteRtLmInstance,
        extraCallbacks: List<() -> Unit>,
    ) {
        thread(name = "LiteRtLM-cleanup-$key") {
            runCatching { instance.conversation.close() }
                .onFailure { Log.e(TAG, "Failed to close LiteRT-LM conversation: ${it.message}", it) }

            runCatching { instance.engine.close() }
                .onFailure { Log.e(TAG, "Failed to close LiteRT-LM engine: ${it.message}", it) }

            val listener = synchronized(stateLock) { cleanUpListeners.remove(key) }
            val deferred = synchronized(stateLock) { pendingCleanupCallbacks.remove(key) } ?: mutableListOf()

            postToMain {
                runCatching { listener?.invoke() }
                    .onFailure { Log.w(TAG, "Cleanup listener failed for key='$key': ${it.message}", it) }

                (deferred + extraCallbacks).forEach { cb ->
                    runCatching { cb.invoke() }
                        .onFailure { Log.w(TAG, "Cleanup callback failed for key='$key': ${it.message}", it) }
                }
            }

            Log.d(TAG, "LiteRT-LM clean up done for key='$key'")
        }
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

        /** Multimodal-first ordering (image/audio then text). */
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
     * Compute a robust delta between [last] and [full] using longest common prefix (LCP).
     *
     * Note:
     * - If the SDK "revises" earlier tokens, LCP may be small and deltas may become large.
     * - This code favors "not missing output" over perfect patching.
     */
    private fun computeDelta(last: String, full: String): String {
        if (last.isEmpty()) return full
        if (full.isEmpty()) return ""

        val minLen = minOf(last.length, full.length)
        var i = 0
        while (i < minLen && last[i] == full[i]) {
            i++
        }
        return if (i <= full.length) full.substring(i) else full
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

        val instance = synchronized(stateLock) { instances[key] }
        if (instance == null) {
            val msg = "LiteRT-LM model '${model.name}' is not initialized. Call initializeIfNeeded() first."
            Log.w(TAG, msg)
            postToMain {
                onError(msg)
                resultListener("", true)
            }
            return
        }

        /** Capability checks help debug quickly. */
        if (images.isNotEmpty() && !instance.supportImage) {
            val msg = "Vision input rejected: supportImage=false for key='$key'. Reinitialize with supportImage=true."
            Log.w(TAG, msg)
            postToMain {
                onError(msg)
                resultListener("", true)
            }
            return
        }
        if (audioClips.isNotEmpty() && !instance.supportAudio) {
            val msg = "Audio input rejected: supportAudio=false for key='$key'. Reinitialize with supportAudio=true."
            Log.w(TAG, msg)
            postToMain {
                onError(msg)
                resultListener("", true)
            }
            return
        }

        val added = activeStreams.add(key)
        if (!added) {
            val msg = "LiteRT-LM runInference rejected: another stream is already active for key='$key'."
            Log.w(TAG, msg)
            postToMain {
                onError(msg)
                resultListener("", true)
            }
            return
        }

        val previous = cleanUpListeners.put(key, cleanUpListener)
        if (previous != null) {
            Log.w(TAG, "LiteRT-LM cleanup listener overwritten for key='$key' (overlapping calls?)")
        }

        val conversation = instance.conversation
        val contents = buildContents(input = input, images = images, audioClips = audioClips)

        val didTerminate = AtomicBoolean(false)
        var lastFullText = ""

        fun terminateSafely(errorMessage: String? = null) {
            if (!didTerminate.compareAndSet(false, true)) return

            activeStreams.remove(key)

            postToMain {
                if (!errorMessage.isNullOrBlank()) {
                    onError(errorMessage)
                }

                /** Terminal signal (exactly once). */
                resultListener("", true)

                cleanUpListeners.remove(key)?.let { listener ->
                    runCatching { listener.invoke() }
                        .onFailure { t ->
                            Log.w(TAG, "cleanUpListener failed for key='$key': ${t.message}", t)
                        }
                }
            }

            val deferredCallbacks = synchronized(stateLock) { pendingCleanupCallbacks.remove(key) }
            if (!deferredCallbacks.isNullOrEmpty()) {
                Log.w(TAG, "Executing deferred cleanup for key='$key' (${deferredCallbacks.size} callbacks)")
                val latest = synchronized(stateLock) { instances.remove(key) }
                if (latest != null) {
                    doCleanupInternal(
                        key = key,
                        instance = latest,
                        extraCallbacks = deferredCallbacks,
                    )
                } else {
                    postToMain {
                        deferredCallbacks.forEach { cb -> runCatching { cb.invoke() } }
                    }
                }
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

                        if (full.isEmpty()) return

                        val delta = computeDelta(lastFullText, full)
                        lastFullText = full

                        if (delta.isNotEmpty()) {
                            postToMain { resultListener(delta, false) }
                        }
                    }

                    override fun onDone() {
                        terminateSafely()
                    }

                    override fun onError(throwable: Throwable) {
                        if (throwable is CancellationException) {
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
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
    ): String = mutex.withLock {
        val key = runtimeKey(model)

        synchronized(stateLock) {
            if (activeStreams.contains(key)) {
                throw IllegalStateException(
                    "LiteRT-LM generateText rejected: active stream in progress for key='$key'.",
                )
            }
            if (!instances.containsKey(key)) {
                throw IllegalStateException(
                    "LiteRT-LM model '${model.name}' is not initialized. Call initializeIfNeeded() first.",
                )
            }
        }

        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException("LiteRT-LM is already busy with another request.")
        }

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
                        Log.d(TAG, "LiteRT-LM clean-up listener invoked for model='${model.name}', key='$key'")
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
                    /** LiteRT-LM does not expose a true cancel API for conversations. */
                    Log.i(TAG, "LiteRT-LM coroutine cancelled for model='${model.name}', key='$key'.")
                }
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * Convert this [Bitmap] to a PNG-encoded [ByteArray].
     */
    private fun Bitmap.toPngByteArray(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /** Sanitize TopK - must be >= 1. */
    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)

    /** Sanitize TopP - must be in [0, 1]. */
    private fun sanitizeTopP(p: Float): Float = p.takeIf { it in 0f..1f } ?: DEFAULT_TOPP

    /** Sanitize Temperature - typical safe band [0, 2]. */
    private fun sanitizeTemperature(t: Float): Float =
        t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    /** Clean and compress error messages for UI/logging. */
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
