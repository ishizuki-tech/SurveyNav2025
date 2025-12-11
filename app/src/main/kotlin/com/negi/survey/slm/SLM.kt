/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Concurrency-safe helper for managing MediaPipe LLM inference sessions
 *  on Android.
 *
 *  Responsibilities:
 *    • Initialize and configure LlmInference / LlmInferenceSession.
 *    • Stream responses via generateResponseAsync with partial tokens.
 *    • Provide cancellation and cleanup hooks with session reuse.
 *    • Expose simple busy-state checks for higher-level watchdogs.
 *
 *  Update (Stability Fixes):
 *    • Use a stable runtime key (cacheKey) for cleanup listeners.
 *    • isBusy() checks process-wide cache + native-generation guard.
 *    • Deferred session close to avoid "Previous invocation still processing".
 *    • fireClean() resets captured instance state directly.
 *    • cleanUp() hard-evicts even when model.instance is null.
 *    • runInference() sync-rebuilds session if sampling params changed.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Hardware accelerator options for inference (CPU or GPU).
 */
enum class Accelerator(val label: String) { CPU("CPU"), GPU("GPU") }

/**
 * Configuration keys for LLM inference.
 */
enum class ConfigKey { MAX_TOKENS, TOP_K, TOP_P, TEMPERATURE, ACCELERATOR }

/** Default values for model parameters. */
private const val DEFAULT_MAX_TOKEN = 256
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f
private const val TAG = "SLM"

/**
 * Callback to deliver partial or final inference results.
 *
 * @param partialResult Current accumulated text or token chunk.
 * @param done True when the inference is complete for this request.
 */
typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

/**
 * Callback to notify when the model session and engine have reached
 * a cleaned or stable state for this request.
 */
typealias CleanUpListener = () -> Unit

/**
 * Execution states of a model instance.
 */
enum class RunState { IDLE, RUNNING, CANCELLING }

/**
 * Represents a loaded LLM model configuration and runtime instance.
 */
data class Model(
    val name: String,
    val taskPath: String,
    val config: Map<ConfigKey, Any> = emptyMap(),
    @Volatile var instance: LlmModelInstance? = null
) {
    fun getPath(): String = taskPath

    fun getIntConfigValue(key: ConfigKey, default: Int): Int =
        (config[key] as? Number)?.toInt()
            ?: (config[key] as? String)?.toIntOrNull()
            ?: default

    fun getFloatConfigValue(key: ConfigKey, default: Float): Float =
        when (val v = config[key]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: default
            else -> default
        }

    fun getStringConfigValue(key: ConfigKey, default: String): String =
        (config[key] as? String) ?: default
}

/**
 * Snapshot of session parameters derived from [Model.config].
 */
data class SessionParams(
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val temperature: Float = DEFAULT_TEMPERATURE
)

/**
 * Holds the initialized engine and session for a model.
 *
 * @property engine Underlying LlmInference engine instance.
 * @property session Active LlmInferenceSession that can be rebuilt.
 * @property state Current run state for this model instance.
 * @property lastParams The parameters used to build the current session.
 *
 * Native-generation guards:
 * - activeGenerationId and lastDoneGenerationId track whether the underlying
 *   MediaPipe session is still processing even if app-level state flips to IDLE.
 * - pendingClose holds an old session reference that should be closed only
 *   after done=true is observed for the active generation.
 */
data class LlmModelInstance(
    val engine: LlmInference,
    @Volatile var session: LlmInferenceSession,
    val state: AtomicReference<RunState> = AtomicReference(RunState.IDLE),
    @Volatile var lastParams: SessionParams = SessionParams(),
    val generationSeq: AtomicLong = AtomicLong(0L),
    @Volatile var activeGenerationId: Long = 0L,
    @Volatile var lastDoneGenerationId: Long = 0L,
    val pendingClose: AtomicReference<LlmInferenceSession?> = AtomicReference(null),
    @Volatile var lastGenerateStartMs: Long = 0L,
    @Volatile var lastGenerateDoneMs: Long = 0L
)

/**
 * Safe Language Model inference helper.
 *
 * This object is designed to be called from a single coordinator
 * (for example, SlmDirectRepository) that serializes requests across
 * the app process.
 */
object SLM {

    /**
     * Per-runtime cleanup listeners keyed by a stable runtime identity.
     *
     * Key strategy:
     * - Use the same discriminator as the process-wide instance cache
     *   so that cleanup hooks follow the actual runtime, not transient
     *   Model object identities.
     */
    private val cleanUpListeners = ConcurrentHashMap<String, () -> Unit>()

    /**
     * Process-wide cache to reuse heavy engine/session across UI resets.
     *
     * Key design:
     * - The task file path is the main discriminator.
     * - Backend preference and maxTokens are included because they affect
     *   engine-level options.
     *
     * Sampling params (topK/topP/temp) are session-level and can be rebuilt.
     */
    private val instanceCache = ConcurrentHashMap<String, LlmModelInstance>()

    /**
     * Returns true when the runtime is not idle.
     *
     * This method checks:
     * - Process-wide cached state
     * - App-level run state
     * - Native-generation guard (active > done)
     *
     * This prevents false-IDLE right after cancel() when MediaPipe
     * is still finishing the previous invocation.
     */
    fun isBusy(model: Model): Boolean {
        val cacheKey = cacheKeyOf(model)
        val cached = instanceCache[cacheKey]
        if (cached != null) {
            val stateBusy = cached.state.get() != RunState.IDLE
            val genBusy = cached.activeGenerationId > cached.lastDoneGenerationId
            return stateBusy || genBusy
        }

        val attached = model.instance
        if (attached != null) {
            val stateBusy = attached.state.get() != RunState.IDLE
            val genBusy = attached.activeGenerationId > attached.lastDoneGenerationId
            return stateBusy || genBusy
        }

        return false
    }

    /**
     * Idempotent initialization entry point.
     *
     * Behavior:
     * - If a cached instance exists, attach it to [model.instance].
     * - If session params differ, rebuild ONLY the session.
     * - Otherwise, no-op with success.
     * - If no cache exists, fall back to [initialize] and cache on success.
     */
    @Synchronized
    fun ensureInitialized(context: Context, model: Model, onDone: (String) -> Unit) {
        val cacheKey = cacheKeyOf(model)
        val cached = instanceCache[cacheKey]

        Log.d(
            TAG,
            "ensureInitialized: model='${model.name}', cacheKey='$cacheKey', " +
                    "hasCached=${cached != null}, hasAttached=${model.instance != null}"
        )

        if (cached != null) {
            if (isBusy(model)) {
                onDone("Model '${model.name}' is busy. Try again after done=true or call cancel().")
                return
            }

            val desired = paramsFromModel(model)
            if (cached.lastParams != desired) {
                val old = cached.session
                val newSession = runCatching { buildSession(cached.engine, desired) }
                    .getOrElse {
                        Log.e(TAG, "ensureInitialized: session rebuild failed: ${it.message}", it)
                        onDone(cleanError(it.message))
                        return
                    }

                cached.session = newSession
                cached.lastParams = desired
                scheduleOrCloseOldSession(cached, old, "ensureInitialized-rebuild")
            }

            model.instance = cached
            cleanUpListeners.remove(runtimeKeyOf(model))
            onDone("")
            return
        }

        initialize(context, model) { err ->
            if (err.isEmpty()) {
                model.instance?.let { inst ->
                    instanceCache[cacheKey] = inst
                    Log.d(TAG, "ensureInitialized: cached new instance model='${model.name}', cacheKey='$cacheKey'")
                }
            }
            onDone(err)
        }
    }

    /**
     * Initializes an engine + session for [model].
     *
     * Note:
     * - Prefer calling [ensureInitialized] from UI.
     * - This function remains as the hard init implementation.
     */
    @Synchronized
    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        var oldEngine: LlmInference? = null
        var oldSession: LlmInferenceSession? = null

        Log.d(
            TAG,
            "initialize: model='${model.name}', cacheKey='${cacheKeyOf(model)}', " +
                    "hasAttached=${model.instance != null}"
        )
        Log.d(TAG, "initialize: closing old session/engine (if any)")

        model.instance?.let { inst ->
            if (inst.state.get() != RunState.IDLE) {
                onDone("Model '${model.name}' is busy. Try again after done=true or call cancel().")
                return
            }

            oldSession = inst.session
            oldEngine = inst.engine
            cleanUpListeners.remove(runtimeKeyOf(model))
            model.instance = null
        }

        tryCloseQuietly(oldSession)
        safeClose(oldEngine)

        val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
        val temp = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
        val backendPref = model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)

        val backend = when (backendPref.uppercase()) {
            Accelerator.CPU.label -> LlmInference.Backend.CPU
            else -> LlmInference.Backend.GPU
        }

        Log.d(
            TAG,
            "initialize: opts model='${model.name}' path='${model.getPath()}', " +
                    "backendPref='$backendPref', resolvedBackend=$backend, " +
                    "maxTokens=$maxTokens, topK=$topK, topP=$topP, temp=$temp"
        )
        Log.d(TAG, "initialize: creating engine with backend=$backend")

        val baseOpts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.getPath())
            .setMaxTokens(maxTokens)

        val engine = try {
            LlmInference.createFromOptions(context, baseOpts.setPreferredBackend(backend).build())
        } catch (e: Exception) {
            if (backend == LlmInference.Backend.GPU) {
                Log.w(TAG, "GPU init failed. Falling back to CPU: ${e.message}")
                try {
                    LlmInference.createFromOptions(
                        context,
                        baseOpts.setPreferredBackend(LlmInference.Backend.CPU).build()
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Engine init failed on CPU: ${e2.message}", e2)
                    onDone(cleanError(e2.message))
                    return
                }
            } else {
                Log.e(TAG, "Engine init failed on CPU: ${e.message}", e)
                onDone(cleanError(e.message))
                return
            }
        }

        try {
            val params = SessionParams(topK = topK, topP = topP, temperature = temp)
            Log.d(TAG, "initialize: building session with params=$params")

            val session = buildSession(engine, params)

            model.instance = LlmModelInstance(
                engine = engine,
                session = session,
                lastParams = params
            )

            // Cache immediately for process-wide reuse.
            instanceCache[cacheKeyOf(model)] = model.instance!!

            Log.d(
                TAG,
                "initialize: success model='${model.name}', cacheKey='${cacheKeyOf(model)}' attached+cached"
            )

            onDone("")
        } catch (e: Exception) {
            Log.e(TAG, "Session init failed: ${e.message}", e)
            safeClose(engine)
            onDone(cleanError(e.message))
        }
    }

    /**
     * Rebuilds the [LlmInferenceSession] for [model] while keeping the current engine.
     *
     * The old session is closed with a native-generation guard:
     * - If MediaPipe is still processing, the close is deferred until done=true.
     */
    fun resetSession(model: Model): Boolean {
        Log.d(
            TAG,
            "resetSession: model='${model.name}', cacheKey='${cacheKeyOf(model)}', " +
                    "hasAttached=${model.instance != null}, hasCached=${instanceCache.containsKey(cacheKeyOf(model))}"
        )

        val snap = synchronized(this) {
            val inst = model.instance
                ?: instanceCache[cacheKeyOf(model)]
                ?: return false

            if (inst.state.get() != RunState.IDLE) return false

            val params = paramsFromModel(model)
            Log.d(TAG, "resetSession: snapshot params desired=$params, last=${inst.lastParams}")

            Snap(inst = inst, oldSession = inst.session, params = params)
        }

        Log.d(TAG, "resetSession: closing old session before rebuild")
        scheduleOrCloseOldSession(snap.inst, snap.oldSession, "resetSession-pre-rebuild")

        Log.d(TAG, "resetSession: building new session params=${snap.params}")
        val newSession = try {
            buildSession(snap.inst.engine, snap.params)
        } catch (e: Exception) {
            Log.e(TAG, "Session reset failed: ${e.message}", e)
            return false
        }

        synchronized(this) {
            val inst = model.instance
                ?: instanceCache[cacheKeyOf(model)]
                ?: return false.also { tryCloseQuietly(newSession) }

            if (inst.engine != snap.inst.engine || inst.state.get() != RunState.IDLE) {
                tryCloseQuietly(newSession)
                return false
            }

            inst.session = newSession
            inst.lastParams = snap.params
            model.instance = inst
        }

        Log.d(TAG, "resetSession: success model='${model.name}', cacheKey='${cacheKeyOf(model)}'")
        return true
    }

    /**
     * Detach this [model] from runtime without closing engine/session.
     *
     * Use this from Compose onDispose when you want to keep the SLM warm
     * across UI session resets.
     */
    @Synchronized
    fun release(model: Model) {
        cleanUpListeners.remove(runtimeKeyOf(model))
        model.instance = null
    }

    /**
     * Completely cleans up the model's engine and session and disposes resources.
     *
     * This is a hard-evict path:
     * - Removes the cached instance for the model's cache key.
     * - Closes engine/session.
     *
     * This implementation also attempts to evict even when [model.instance] is null.
     */
    @Synchronized
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val cacheKey = cacheKeyOf(model)
        val inst = model.instance ?: instanceCache.remove(cacheKey)

        if (inst == null) {
            cleanUpListeners.remove(runtimeKeyOf(model))
            onDone()
            return
        }

        cleanUpListeners.remove(runtimeKeyOf(model))?.invoke()
        instanceCache.remove(cacheKey)

        runCatching {
            if (inst.state.get() != RunState.IDLE) {
                inst.session.cancelGenerateResponseAsync()
            }
        }

        inst.state.set(RunState.IDLE)

        model.instance = null

        // Best-effort: close any deferred session first.
        flushPendingClose(inst, "cleanup-hard-evict")

        tryCloseQuietly(inst.session)
        safeClose(inst.engine)

        onDone()
    }

    /**
     * Attempts to cancel the current generation for [model].
     *
     * Note:
     * - App-level state may flip to IDLE immediately.
     * - Native-generation guard keeps isBusy() true until done=true arrives.
     * - Any pending close is flushed when safe.
     */
    @Synchronized
    fun cancel(model: Model) {
        val cacheKey = cacheKeyOf(model)
        val inst = model.instance ?: instanceCache[cacheKey] ?: return

        Log.d(
            TAG,
            "cancel: invoked model='${model.name}', runtimeKey='${runtimeKeyOf(model)}', " +
                    "cacheKey='$cacheKey', stateBefore=${inst.state.get()}"
        )

        if (inst.state.get() == RunState.IDLE) {
            cleanUpListeners.remove(runtimeKeyOf(model))
            flushPendingClose(inst, "cancel-idle")
            return
        }

        inst.state.set(RunState.CANCELLING)

        runCatching { inst.session.cancelGenerateResponseAsync() }
            .onFailure { Log.w(TAG, "cancelGenerateResponseAsync failed: ${it.message}") }

        // Do not modify generation IDs here.
        // done=true callback will finalize native-generation state.
        inst.state.set(RunState.IDLE)

        Log.d(TAG, "cancel: stateAfter=${inst.state.get()} model='${model.name}'")

        cleanUpListeners.remove(runtimeKeyOf(model))?.invoke()

        // If native has already finished, we can close deferred sessions.
        flushPendingClose(inst, "cancel-post")
    }

    /**
     * Launches an asynchronous inference for [model] with [input].
     */
    fun runInference(
        model: Model,
        input: String,
        listener: ResultListener,
        onClean: CleanUpListener
    ) {
        val runtimeKey = runtimeKeyOf(model)

        val inst = synchronized(this) {
            model.instance ?: instanceCache[cacheKeyOf(model)]
        } ?: run {
            listener("Model not initialized.", true)
            return
        }

        // Re-attach the cached instance to this model for consistent visibility.
        model.instance = inst

        val once = AtomicBoolean(false)

        fun fireClean(tag: String) {
            if (once.compareAndSet(false, true)) {
                Log.d(
                    TAG,
                    "runInference: onClean fired tag='$tag' model='${model.name}', " +
                            "stateBefore=${inst.state.get()}"
                )
                inst.state.set(RunState.IDLE)
                onClean()
            }
        }

        // Ensure session params are aligned before state transition.
        val desired = paramsFromModel(model)
        if (!ensureSessionParams(inst, desired)) {
            listener("Session rebuild failed.", true)
            fireClean("session-rebuild-failed")
            cleanUpListeners.remove(runtimeKey)
            return
        }

        val acquired = inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)
        if (!acquired) {
            cancel(model)
            if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
                listener("Model '${model.name}' is busy.", true)
                fireClean("busy-refused")
                cleanUpListeners.remove(runtimeKey)
                return
            }
        }

        // Start a new native-generation window.
        val genId = markGenerationStart(inst)

        Log.d(
            TAG,
            "runInference[1/1]: start model='${model.name}', runtimeKey='$runtimeKey', " +
                    "cacheKey='${cacheKeyOf(model)}', state=${inst.state.get()}, " +
                    "lastParams=${inst.lastParams}, input.len=${input.length}, genId=$genId"
        )

        cleanUpListeners[runtimeKey] = { fireClean("cleanup-listener") }

        val text = input.trim()
        if (text.isNotEmpty()) {
            Log.d(TAG, "runInference[1/1]: addQueryChunk start len=${text.length}")
            val ok = addQueryChunkWithOneRetry(model, inst, desired, text)
            if (!ok) {
                listener("Failed to add query chunk.", true)
                cleanUpListeners.remove(runtimeKey)?.invoke()
                return
            }
        }

        try {
            Log.d(TAG, "runInference[1/1]: generateResponseAsync START")
            inst.session.generateResponseAsync { partial, done ->
                val preview =
                    if (partial.length > 256) {
                        partial.take(128) + " … " + partial.takeLast(64)
                    } else {
                        partial
                    }

                Log.d(TAG, "runInference[1/1]: partial[len=${partial.length}, done=$done] $preview")

                if (!done) {
                    listener(partial, false)
                } else {
                    markGenerationDone(inst, genId)
                    flushPendingClose(inst, "done-callback")

                    listener(partial, true)

                    Log.d(TAG, "runInference[1/1]: DONE received → invoking cleanup listener")
                    cleanUpListeners.remove(runtimeKey)?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateResponseAsync failed: ${e.message}", e)
            listener(cleanError(e.message), true)
            cleanUpListeners.remove(runtimeKey)?.invoke()
        }
    }

    /* --------------------------------------------------------------------- */
    /* Internal helpers                                                      */
    /* --------------------------------------------------------------------- */

    /**
     * Derive session parameters from [Model.config] with sanitization.
     */
    private fun paramsFromModel(model: Model): SessionParams {
        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
        val temp = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
        return SessionParams(topK = topK, topP = topP, temperature = temp)
    }

    /**
     * Ensure the current session matches [desired] params.
     *
     * This is a lightweight sync guard to avoid subtle mismatches when
     * config changed without a full re-init.
     */
    private fun ensureSessionParams(
        inst: LlmModelInstance,
        desired: SessionParams
    ): Boolean {
        if (inst.lastParams == desired) return true

        val old = inst.session
        val newSession = runCatching { buildSession(inst.engine, desired) }
            .getOrElse {
                Log.e(TAG, "ensureSessionParams: rebuild failed: ${it.message}", it)
                return false
            }

        inst.session = newSession
        inst.lastParams = desired

        scheduleOrCloseOldSession(inst, old, "ensureSessionParams-rebuild")
        return true
    }

    /**
     * Build a new session from [engine] and [params].
     */
    private fun buildSession(
        engine: LlmInference,
        params: SessionParams
    ): LlmInferenceSession =
        LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(params.topK)
                .setTopP(params.topP)
                .setTemperature(params.temperature)
                .build()
        )

    /**
     * Try addQueryChunk; if it fails, rebuild the session once and retry.
     */
    private fun addQueryChunkWithOneRetry(
        model: Model,
        inst: LlmModelInstance,
        desired: SessionParams,
        text: String
    ): Boolean {
        fun tryAdd(): Boolean =
            runCatching {
                inst.session.addQueryChunk(text)
                true
            }.getOrElse { e ->
                Log.w(TAG, "addQueryChunk failed: ${e.message}")
                false
            }

        if (tryAdd()) {
            Log.d(TAG, "addQueryChunk[1/1]: first OK len=${text.length}")
            return true
        }

        val rebuilt = synchronized(this) {
            val current = model.instance ?: instanceCache[cacheKeyOf(model)] ?: return@synchronized false
            if (current.engine != inst.engine) return@synchronized false

            val old = current.session
            val newSession = runCatching { buildSession(current.engine, desired) }
                .getOrElse {
                    Log.e(TAG, "Session rebuild failed: ${it.message}", it)
                    return@synchronized false
                }

            current.session = newSession
            current.lastParams = desired
            model.instance = current

            scheduleOrCloseOldSession(current, old, "addQueryChunk-rebuild")
            true
        }

        if (!rebuilt) return false

        return runCatching {
            inst.session.addQueryChunk(text)
            true
        }.getOrElse { e ->
            Log.e(TAG, "addQueryChunk retry failed: ${e.message}", e)
            false
        }
    }

    /**
     * Mark the start of a new generation and return its id.
     */
    private fun markGenerationStart(inst: LlmModelInstance): Long {
        val id = inst.generationSeq.incrementAndGet()
        inst.activeGenerationId = id
        inst.lastGenerateStartMs = SystemClock.elapsedRealtime()
        return id
    }

    /**
     * Mark the completion of a generation.
     */
    private fun markGenerationDone(inst: LlmModelInstance, id: Long) {
        // Only advance done marker forward.
        if (id >= inst.lastDoneGenerationId) {
            inst.lastDoneGenerationId = id
            inst.lastGenerateDoneMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Close the old session immediately if the native layer is idle.
     * Otherwise defer the close until done=true is observed.
     */
    private fun scheduleOrCloseOldSession(
        inst: LlmModelInstance,
        old: LlmInferenceSession?,
        reason: String
    ) {
        if (old == null) return

        val active = inst.activeGenerationId
        val done = inst.lastDoneGenerationId
        val genBusy = active > done

        if (genBusy) {
            val prev = inst.pendingClose.getAndSet(old)
            Log.d(
                TAG,
                "deferClose: reason=$reason active=$active done=$done " +
                        "state=${inst.state.get()} prevPending=${prev != null}"
            )

            // Best-effort cleanup of a previously deferred session.
            if (prev != null && prev !== old) {
                runCatching { prev.close() }
                    .onFailure { Log.d(TAG, "deferClose: previous pending close failed: ${it.message}") }
            }
            return
        }

        tryCloseQuietly(old)
    }

    /**
     * Flush a deferred close if it is safe to do so.
     */
    private fun flushPendingClose(inst: LlmModelInstance, reason: String) {
        val active = inst.activeGenerationId
        val done = inst.lastDoneGenerationId
        val genBusy = active > done

        if (genBusy) {
            Log.d(TAG, "flushPendingClose: skipped reason=$reason active=$active done=$done")
            return
        }

        val pending = inst.pendingClose.getAndSet(null)
        if (pending != null) {
            Log.d(TAG, "flushPendingClose: closing deferred session reason=$reason")
            tryCloseQuietly(pending)
        }
    }

    /**
     * Sanitize TopK - must be >= 1.
     */
    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)

    /**
     * Sanitize TopP - must be in [0, 1].
     */
    private fun sanitizeTopP(p: Float): Float =
        p.takeIf { it in 0f..1f } ?: DEFAULT_TOP_P

    /**
     * Sanitize Temperature - typical safe band [0, 2].
     */
    private fun sanitizeTemperature(t: Float): Float =
        t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    /**
     * Stable runtime key for cleanup/listener routing.
     */
    private fun runtimeKeyOf(model: Model): String = cacheKeyOf(model)

    /**
     * Build a process-wide cache key for heavy runtime reuse.
     */
    private fun cacheKeyOf(model: Model): String {
        val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
        val backendPref = model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)
            .uppercase()
        return "${model.getPath()}|$backendPref|$maxTokens"
    }

    /**
     * Clean and compress error messages for UI.
     */
    private fun cleanError(msg: String?): String =
        msg
            ?.replace("INTERNAL:", "")
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown error"

    /**
     * Try to cancel and close a session quietly.
     */
    private fun tryCloseQuietly(session: LlmInferenceSession?) {
        runCatching {
            session?.cancelGenerateResponseAsync()
            session?.close()
        }.onFailure {
            Log.w(TAG, "Session close failed: ${it.message}")
        }
    }

    /**
     * Close an engine quietly.
     */
    private fun safeClose(engine: LlmInference?) {
        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "Engine close failed: ${it.message}") }
    }

    private data class Snap(
        val inst: LlmModelInstance,
        val oldSession: LlmInferenceSession,
        val params: SessionParams
    )
}
