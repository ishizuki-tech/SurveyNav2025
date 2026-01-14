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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * IMPORTANT:
 * - Never close [engine] / [conversation] while a stream callback may still arrive.
 * - Cleanup must be deferred until the stream is fully terminated.
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
 * Design goals (SIGSEGV prevention):
 * - Never close/replace engine/conversation while callbacks may still arrive.
 * - Ensure stream termination happens exactly once.
 * - Defer cleanup/reset/replace until after stream ends.
 * - Ignore late callbacks using a monotonically increasing runId.
 *
 * IMPORTANT CONVENTION:
 * - Streaming terminal signal is delivered ONLY via resultListener("", true).
 * - cleanUpListener is just a hook; never used as terminal.
 */
object LiteRtLM {

    /** Main thread handler for UI-safe callbacks. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /** Dedicated IO scope for init/cleanup work. */
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Global lock for instance map + state transitions. */
    private val stateMutex: Mutex = Mutex()

    /** Per-runtime LiteRT-LM runtime instances keyed by [runtimeKey]. */
    private val instances: MutableMap<String, LiteRtLmInstance> = ConcurrentHashMap()

    /** Pending actions to execute once the active stream terminates. */
    private val pendingAfterStream: MutableMap<String, MutableList<() -> Unit>> = ConcurrentHashMap()

    /** Prevent concurrent init for the same key. */
    private val initInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Mutex to serialize initializeIfNeeded() and generateText().
     * Streaming runInference() is guarded per-key by RunState.active.
     */
    private val apiMutex: Mutex = Mutex()

    /** Busy flag used only by generateText() (suspend API). */
    private val busy: AtomicBoolean = AtomicBoolean(false)

    /** Returns `true` when a generateText call is currently in progress. */
    fun isBusy(): Boolean = busy.get()

    /** Stable runtime key for instances and state. */
    private fun runtimeKey(model: Model): String = model.name

    /** Post work onto the main thread. */
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Per-key run state (stream lifecycle + runId). */
    private data class RunState(
        val active: AtomicBoolean = AtomicBoolean(false),
        val runId: AtomicLong = AtomicLong(0L),
        val terminated: AtomicBoolean = AtomicBoolean(false),
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        @Volatile var terminateHook: ((String?) -> Unit)? = null,
    )

    private val runStates: MutableMap<String, RunState> = ConcurrentHashMap()

    /** Get or create per-key run state. */
    private fun getRunState(key: String): RunState {
        return runStates.getOrPut(key) { RunState() }
    }

    /** Normalize accelerator string for stable backend selection. */
    private fun normalizedAccelerator(model: Model): String {
        return model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)
            .trim()
            .uppercase()
            .ifBlank { Accelerator.GPU.label }
    }

    /** Resolve preferred backend from model config. */
    private fun preferredBackend(model: Model): Backend {
        return when (normalizedAccelerator(model)) {
            Accelerator.CPU.label -> Backend.CPU
            Accelerator.GPU.label -> Backend.GPU
            else -> Backend.GPU
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

    /** Convert this Bitmap to PNG bytes. */
    private fun Bitmap.toPngByteArray(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /**
     * Build Content list for a single message (multimodal first, then text).
     */
    private fun buildContents(
        input: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
    ): List<Content> {
        val contents = mutableListOf<Content>()
        for (image in images) contents.add(Content.ImageBytes(image.toPngByteArray()))
        for (audio in audioClips) contents.add(Content.AudioBytes(audio))
        val t = input.trim()
        if (t.isNotEmpty()) contents.add(Content.Text(t))
        return contents
    }

    /**
     * Compute delta between last and full using longest common prefix.
     */
    private fun computeDelta(last: String, full: String): String {
        if (last.isEmpty()) return full
        if (full.isEmpty()) return ""
        val minLen = minOf(last.length, full.length)
        var i = 0
        while (i < minLen && last[i] == full[i]) i++
        return if (i <= full.length) full.substring(i) else ""
    }

    /**
     * Execute deferred actions (reset/cleanup) after stream ends.
     */
    private suspend fun executeDeferredActions(key: String) {
        val deferred: List<() -> Unit> = stateMutex.withLock {
            pendingAfterStream.remove(key)?.toList() ?: emptyList()
        }
        deferred.forEach { act ->
            runCatching { act.invoke() }
                .onFailure { t -> Log.w(TAG, "Deferred action failed for key='$key': ${t.message}", t) }
        }
    }

    /**
     * Initialize LiteRT-LM Engine + Conversation (async).
     *
     * Rules:
     * - If a stream is active, init is rejected (caller should defer via reinitialize path).
     * - If init is already in progress for the same key, returns an error.
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

        val accepted = initInFlight.add(key)
        if (!accepted) {
            postToMain { onDone("Initialization already in progress for key='$key'.") }
            return
        }

        ioScope.launch {
            try {
                // Reject init while streaming for safety.
                stateMutex.withLock {
                    val rs = getRunState(key)
                    if (rs.active.get()) {
                        postToMain { onDone("Initialization rejected: active stream in progress for key='$key'.") }
                        return@launch
                    }
                }

                val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN).coerceAtLeast(1)
                val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
                val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
                val temperature = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                val backend = preferredBackend(model)

                Log.d(TAG, "Initializing LiteRT-LM engine: model='${model.name}', key='$key'")
                Log.d(TAG, "Capabilities: image=$supportImage audio=$supportAudio")
                Log.d(TAG, "Backend=$backend maxTokens=$maxTokens topK=$topK topP=$topP temp=$temperature")

                val modelPath = model.getPath()
                val cacheDirPath = runCatching { context.cacheDir?.absolutePath }.getOrNull()

                fun buildConfig(forBackend: Backend): EngineConfig {
                    return EngineConfig(
                        modelPath = modelPath,
                        backend = forBackend,
                        visionBackend = if (supportImage) Backend.GPU else null,
                        audioBackend = if (supportAudio) Backend.CPU else null,
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDirPath,
                    )
                }

                var engineConfig = buildConfig(backend)

                // Create engine off main thread.
                val engine = runCatching {
                    Engine(engineConfig).also { it.initialize() }
                }.getOrElse { first ->
                    // Safe fallback to CPU only for text-only models.
                    if (backend == Backend.GPU && !supportImage && !supportAudio) {
                        Log.w(TAG, "GPU init failed; falling back to CPU: ${first.message}")
                        engineConfig = buildConfig(Backend.CPU)
                        Engine(engineConfig).also { it.initialize() }
                    } else {
                        throw first
                    }
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

                // Swap instance under lock, but close old outside the lock.
                val old: LiteRtLmInstance? = stateMutex.withLock {
                    val prev = instances.remove(key)
                    instances[key] = LiteRtLmInstance(
                        engine = engine,
                        conversation = conversation,
                        supportImage = supportImage,
                        supportAudio = supportAudio,
                        engineConfigSnapshot = engineConfig,
                    )
                    prev
                }

                if (old != null) {
                    runCatching { old.conversation.close() }
                        .onFailure { Log.w(TAG, "Failed to close old conversation: ${it.message}", it) }
                    runCatching { old.engine.close() }
                        .onFailure { Log.w(TAG, "Failed to close old engine: ${it.message}", it) }
                }

                Log.d(TAG, "LiteRT-LM initialization succeeded: model='${model.name}', key='$key'")
                postToMain { onDone("") }
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM initialization failed: ${e.message}", e)
                postToMain { onDone(cleanError(e.message)) }
            } finally {
                initInFlight.remove(key)
            }
        }
    }

    /**
     * Suspend-style initializer.
     *
     * Uses apiMutex to prevent double init and to avoid init-vs-generateText overlap.
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

        stateMutex.withLock {
            if (instances.containsKey(key)) return
        }

        apiMutex.withLock {
            stateMutex.withLock {
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
                                if (cont.isActive) cont.resumeWithException(
                                    IllegalStateException("LiteRT-LM initialization failed: $error")
                                )
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
     * Reset conversation (reuse engine) safely.
     *
     * If a stream is active, reset is deferred until the stream terminates.
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        ioScope.launch {
            val action: () -> Unit = {
                ioScope.launch {
                    try {
                        val instance = stateMutex.withLock { instances[key] } ?: return@launch

                        if (instance.supportImage != supportImage || instance.supportAudio != supportAudio) {
                            Log.w(
                                TAG,
                                "resetConversation called with different capabilities; " +
                                        "init(image=${instance.supportImage}, audio=${instance.supportAudio}) vs " +
                                        "reset(image=$supportImage, audio=$supportAudio). Reinitialize if needed."
                            )
                        }

                        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
                        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
                        val temperature = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                        val newConversation = instance.engine.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(
                                    topK = topK,
                                    topP = topP.toDouble(),
                                    temperature = temperature.toDouble(),
                                ),
                                systemMessage = systemMessage,
                                tools = tools,
                            )
                        )

                        val oldConversation: Conversation? = stateMutex.withLock {
                            val latest = instances[key] ?: run {
                                runCatching { newConversation.close() }
                                return@withLock null
                            }
                            val old = latest.conversation
                            latest.conversation = newConversation
                            old
                        }

                        if (oldConversation != null) {
                            runCatching { oldConversation.close() }
                                .onFailure { Log.w(TAG, "Failed to close old conversation: ${it.message}", it) }
                        }

                        Log.d(TAG, "Conversation reset completed: key='$key'")
                    } catch (e: Exception) {
                        Log.e(TAG, "resetConversation failed: ${e.message}", e)
                    }
                }
            }

            val defer = stateMutex.withLock {
                val rs = getRunState(key)
                rs.active.get()
            }

            if (defer) {
                stateMutex.withLock {
                    val list = pendingAfterStream.getOrPut(key) { mutableListOf() }
                    list.add(action)
                }
                Log.w(TAG, "resetConversation deferred: active stream in progress for key='$key'")
                return@launch
            }

            action.invoke()
        }
    }

    /**
     * Fully release Engine + Conversation safely.
     *
     * If a stream is active, cleanup is deferred until stream termination.
     */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)
        val doneOnce = AtomicBoolean(false)

        fun done() {
            if (!doneOnce.compareAndSet(false, true)) return
            postToMain { onDone() }
        }

        ioScope.launch {
            val action: () -> Unit = {
                ioScope.launch {
                    val instance: LiteRtLmInstance? = stateMutex.withLock {
                        instances.remove(key)
                    }

                    if (instance == null) {
                        done()
                        return@launch
                    }

                    runCatching { instance.conversation.close() }
                        .onFailure { Log.e(TAG, "Failed to close conversation: ${it.message}", it) }
                    runCatching { instance.engine.close() }
                        .onFailure { Log.e(TAG, "Failed to close engine: ${it.message}", it) }

                    Log.d(TAG, "LiteRT-LM cleaned up: key='$key'")
                    done()
                }
            }

            val defer = stateMutex.withLock {
                val rs = getRunState(key)
                rs.active.get()
            }

            if (defer) {
                stateMutex.withLock {
                    val list = pendingAfterStream.getOrPut(key) { mutableListOf() }
                    list.add(action)
                }
                Log.w(TAG, "cleanUp deferred: active stream in progress for key='$key'")
                return@launch
            }

            action.invoke()
        }
    }

    /**
     * Low-level callback-based streaming API.
     *
     * Contract:
     * - Terminates exactly once with done=true (via resultListener("", true)).
     * - Partial callbacks are delivered on Main.
     * - Never closes engine/conversation here; cleanup/reset are deferred actions.
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

        ioScope.launch {
            val instance = stateMutex.withLock { instances[key] }
            if (instance == null) {
                val msg = "LiteRT-LM model '${model.name}' is not initialized. Call initializeIfNeeded() first."
                Log.w(TAG, msg)
                postToMain {
                    onError(msg)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
                return@launch
            }

            if (images.isNotEmpty() && !instance.supportImage) {
                val msg = "Vision input rejected: supportImage=false for key='$key'. Reinitialize with supportImage=true."
                Log.w(TAG, msg)
                postToMain {
                    onError(msg)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
                return@launch
            }

            if (audioClips.isNotEmpty() && !instance.supportAudio) {
                val msg = "Audio input rejected: supportAudio=false for key='$key'. Reinitialize with supportAudio=true."
                Log.w(TAG, msg)
                postToMain {
                    onError(msg)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
                return@launch
            }

            val rs = getRunState(key)

            // Acquire per-key active flag.
            val acquired = rs.active.compareAndSet(false, true)
            if (!acquired) {
                val msg = "LiteRT-LM runInference rejected: another stream is already active for key='$key'."
                Log.w(TAG, msg)
                postToMain {
                    onError(msg)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
                return@launch
            }

            // New runId; late callbacks from older runs will be ignored.
            val myRunId = rs.runId.incrementAndGet()
            rs.terminated.set(false)
            rs.cancelRequested.set(false)

            val conversation: Conversation = instance.conversation
            val contents = buildContents(input = input, images = images, audioClips = audioClips)

            var lastFullText = ""

            fun terminateOnce(errorMessage: String? = null) {
                if (!rs.terminated.compareAndSet(false, true)) return

                // Clear hook immediately to avoid leaks / duplicate calls.
                rs.terminateHook = null

                // Mark inactive before deferred actions.
                rs.active.set(false)

                postToMain {
                    if (!errorMessage.isNullOrBlank()) onError(errorMessage)
                    // Terminal signal exactly once.
                    resultListener("", true)
                    // Hook: must never throw.
                    runCatching { cleanUpListener.invoke() }
                        .onFailure { t -> Log.w(TAG, "cleanUpListener failed: ${t.message}", t) }
                }

                ioScope.launch {
                    executeDeferredActions(key)
                }
            }

            // Allow external cancel() to terminate this in-flight run safely.
            rs.terminateHook = { err -> terminateOnce(err) }

            try {
                conversation.sendMessageAsync(
                    Message.of(contents),
                    object : MessageCallback {

                        override fun onMessage(message: Message) {
                            // Ignore callbacks from older runs.
                            if (rs.runId.get() != myRunId) return
                            // Ignore after termination.
                            if (rs.terminated.get()) return

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
                            if (rs.runId.get() != myRunId) return
                            terminateOnce()
                        }

                        override fun onError(throwable: Throwable) {
                            if (rs.runId.get() != myRunId) return
                            if (rs.terminated.get()) return

                            if (throwable is CancellationException || rs.cancelRequested.get()) {
                                Log.i(TAG, "LiteRT-LM inference cancelled: key='$key'")
                                terminateOnce()
                            } else {
                                Log.e(TAG, "LiteRT-LM onError: key='$key'", throwable)
                                terminateOnce("Error: ${cleanError(throwable.message)}")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM sendMessageAsync failed: key='$key' msg=${e.message}", e)
                terminateOnce(cleanError(e.message))
            }
        }
    }

    /**
     * High-level suspend API:
     * - Serializes calls via apiMutex.
     * - Uses runInference internally and returns full aggregated text.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
    ): String = apiMutex.withLock {
        val key = runtimeKey(model)

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
                    images = images,
                    audioClips = audioClips,
                    resultListener = { partial, done ->
                        if (partial.isNotEmpty()) {
                            buffer.append(partial)
                            runCatching { onPartial(partial) }
                                .onFailure { t -> Log.w(TAG, "onPartial failed: ${t.message}", t) }
                        }
                        if (done && cont.isActive && !sawError.get()) {
                            cont.resume(buffer.toString())
                        }
                    },
                    cleanUpListener = {
                        // No-op by default; caller can use this hook if needed.
                    },
                    onError = { message ->
                        sawError.set(true)
                        if (cont.isActive) cont.resumeWithException(
                            IllegalStateException("LiteRT-LM generation error: $message")
                        )
                    },
                )

                cont.invokeOnCancellation {
                    // Best-effort logical cancel.
                    Log.i(TAG, "generateText cancelled: key='$key'")
                    cancel(model)
                }
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * Best-effort logical cancellation.
     *
     * IMPORTANT:
     * - This MUST release RunState.active to avoid deadlocking future requests and deferred cleanups.
     * - We do not close/replace engine/conversation here.
     * - We trigger the active run's terminateHook so StreamRunner postClose cleanup can proceed.
     */
    fun cancel(model: Model) {
        val key = runtimeKey(model)
        val rs = getRunState(key)

        // Nothing to do if not active.
        if (!rs.active.get()) return

        rs.cancelRequested.set(true)

        val hook = rs.terminateHook
        if (hook != null) {
            // Terminate the stream logically (will set active=false and run deferred actions).
            hook.invoke(null)
            return
        }

        // Fallback: if hook isn't installed yet, ensure we still release active
        // so deferred cleanups (e.g., cleanUp()) won't get stuck.
        if (rs.terminated.compareAndSet(false, true)) {
            rs.active.set(false)
            rs.terminateHook = null
            ioScope.launch {
                executeDeferredActions(key)
            }
        }
    }
}
