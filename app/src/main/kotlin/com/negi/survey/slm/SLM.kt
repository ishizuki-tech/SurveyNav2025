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
 *  Stability notes:
 *    • All user callbacks are delivered on Main.
 *    • cleanup hooks are generation-aware (prevents late-done races).
 *    • cleanUp(busy) is deferred but guarded by a watchdog timeout.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Hardware accelerator options for inference (CPU or GPU).
 */
enum class Accelerator(val label: String) { CPU("CPU"), GPU("GPU") }

/**
 * Configuration keys for LLM inference.
 */
enum class ConfigKey { MAX_TOKENS, TOP_K, TOP_P, TEMPERATURE, ACCELERATOR }

/** Default values for model parameters. */
private const val DEFAULT_MAX_TOKEN = 4096
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/** Absolute safety clamp for engine-level max tokens. */
private const val ABS_MAX_TOKENS = 4096

private const val TAG = "SLM"

/** Upper bound for error strings rendered in UI/log aggregation. */
private const val ERROR_MAX_CHARS = 240

/**
 * Deferred hard-close watchdog:
 * If done=true is never observed after cancel, we eventually force-close.
 *
 * WARNING:
 * Force-closing native resources while generation is truly active may be risky.
 * We only do this after a grace timeout and best-effort cancel.
 */
private const val HARD_CLOSE_TIMEOUT_MS = 15_000L
private const val HARD_CLOSE_POLL_MS = 750L

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
    @Volatile var instance: SlmModelInstance? = null
) {
    /** Returns the raw task path used by MediaPipe Tasks. */
    fun getPath(): String = taskPath

    /** Lookup an Int config value with a sane fallback. */
    fun getIntConfigValue(key: ConfigKey, default: Int): Int =
        (config[key] as? Number)?.toInt()
            ?: (config[key] as? String)?.toIntOrNull()
            ?: default

    /** Lookup a Float config value with a sane fallback. */
    fun getFloatConfigValue(key: ConfigKey, default: Float): Float =
        when (val v = config[key]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: default
            else -> default
        }

    /** Lookup a String config value with a sane fallback. */
    fun getStringConfigValue(key: ConfigKey, default: String): String =
        (config[key] as? String) ?: default
}

/**
 * Snapshot of session parameters derived from Model.config.
 */
data class SessionParams(
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val temperature: Float = DEFAULT_TEMPERATURE
)

/**
 * Holds the initialized engine and session for a model.
 *
 * Native-generation guards:
 * - generationSeq: monotonic request id for generation-aware cleanup hooks.
 * - activeGenerationId / lastDoneGenerationId: indicates native work in flight.
 * - pendingClose: old session that must be closed only when safe.
 */
data class SlmModelInstance(
    @Volatile var cacheKey: String = "",
    @Volatile var backendLabel: String = Accelerator.GPU.label,
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
 */
object SLM {

    /** Main thread handler for UI-safe callbacks. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /** Global lock for multi-map state transitions. */
    private val stateLock = Any()

    /**
     * Generation-aware cleanup hook.
     *
     * Using generationId prevents late callbacks from older sessions
     * from invoking (or removing) the cleanup hook for a newer request.
     */
    private data class CleanUpHook(
        val generationId: Long,
        val callback: () -> Unit
    )

    /**
     * Per-runtime cleanup hooks keyed by stable runtime identity.
     */
    private val cleanUpHooks = ConcurrentHashMap<String, CleanUpHook>()

    /**
     * Process-wide cache to reuse heavy engine/session across UI resets.
     *
     * Key = "taskPath|backendLabel|maxTokens"
     */
    private val instanceCache = ConcurrentHashMap<String, SlmModelInstance>()

    /**
     * Alias mapping for requested key -> actual key.
     */
    private val cacheAliases = ConcurrentHashMap<String, String>()

    /**
     * Prevent concurrent init on the same requested key.
     */
    private val initInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Deferred hard closes keyed by runtime key.
     */
    private data class DeferredHardClose(
        val instance: SlmModelInstance,
        val callbacks: MutableList<() -> Unit>,
        val requestedAtMs: Long,
        val reason: String
    )

    private val deferredHardCloses = ConcurrentHashMap<String, DeferredHardClose>()

    /**
     * Returns true when the runtime is not idle for this model.
     */
    fun isBusy(model: Model): Boolean {
        val attached = model.instance
        if (attached != null) return isInstanceBusy(attached)

        val requestedKey = cacheKeyOf(model)
        val resolvedKey = resolveCacheKey(requestedKey)
        val cached = instanceCache[resolvedKey] ?: instanceCache[requestedKey]
        return cached?.let { isInstanceBusy(it) } ?: false
    }

    /**
     * Idempotent initialization entry point.
     *
     * Behavior:
     * - If cached exists, attach it.
     * - If params differ, rebuild only the session.
     * - Otherwise, no-op.
     * - If no cache exists, initialize in background and callback on Main.
     */
    fun ensureInitialized(context: Context, model: Model, onDone: (String) -> Unit) {
        val requestedKey = cacheKeyOf(model)
        val resolvedKey = resolveCacheKey(requestedKey)
        val cached = instanceCache[resolvedKey] ?: instanceCache[requestedKey]

        Log.d(
            TAG,
            "ensureInitialized: model='${model.name}', requestedKey='$requestedKey', resolvedKey='$resolvedKey', " +
                    "hasCached=${cached != null}, hasAttached=${model.instance != null}"
        )

        if (cached != null) {
            if (isInstanceBusy(cached)) {
                postToMain { onDone("Model '${model.name}' is busy. Try again after done=true or call cancel().") }
                return
            }

            val desired = paramsFromModel(model)
            if (cached.lastParams != desired) {
                val old = cached.session
                val newSession = runCatching { buildSession(cached.engine, desired) }
                    .getOrElse {
                        Log.e(TAG, "ensureInitialized: session rebuild failed: ${it.message}", it)
                        postToMain { onDone(cleanError(it.message)) }
                        return
                    }

                cached.session = newSession
                cached.lastParams = desired
                scheduleOrCloseOldSession(cached, old, "ensureInitialized-rebuild")
                flushDeferredHardCloseIfReady(cached, "ensureInitialized-post-rebuild")
            }

            model.instance = cached

            // Only clear stale hooks if truly idle (best-effort).
            if (!isInstanceBusy(cached)) {
                cleanUpHooks.remove(runtimeKeyOf(model, cached))
            }

            postToMain { onDone("") }
            return
        }

        initialize(context, model, onDone)
    }

    /**
     * Initializes an engine + session for model in a background thread.
     */
    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        val requestedKey = cacheKeyOf(model)

        val accepted = initInFlight.add(requestedKey)
        if (!accepted) {
            postToMain { onDone("Initialization already in progress for key='$requestedKey'.") }
            return
        }

        thread(name = "SLM-init-${model.name}") {
            try {
                val resolvedKey = resolveCacheKey(requestedKey)

                Log.d(
                    TAG,
                    "initialize: model='${model.name}', requestedKey='$requestedKey', resolvedKey='$resolvedKey', hasAttached=${model.instance != null}"
                )

                // Hard-evict existing runtime if safe.
                val existing = synchronized(stateLock) {
                    model.instance ?: instanceCache[resolvedKey] ?: instanceCache[requestedKey]
                }

                if (existing != null) {
                    if (isInstanceBusy(existing)) {
                        postToMain { onDone("Model '${model.name}' is busy. Try again after done=true or call cancel().") }
                        return@thread
                    }

                    val keyInCache = existing.cacheKey.takeIf { it.isNotBlank() }
                        ?: findCacheKeyForInstance(existing)
                        ?: resolvedKey

                    Log.d(TAG, "initialize: hard-evict existing runtime key='$keyInCache'")

                    synchronized(stateLock) {
                        removeAliasesPointingTo(keyInCache)
                        instanceCache.remove(keyInCache)
                        if (model.instance === existing) model.instance = null
                        cleanUpHooks.remove(keyInCache)
                    }

                    flushPendingClose(existing, "initialize-hard-evict")
                    tryCloseQuietly(existing.session)
                    safeClose(existing.engine)
                }

                val maxTokensRaw = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
                val maxTokens = maxTokensRaw.coerceIn(1, ABS_MAX_TOKENS)
                val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
                val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
                val temp = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                val backendPref = normalizedAccelerator(model)
                val preferredBackend = when (backendPref) {
                    Accelerator.CPU.label -> LlmInference.Backend.CPU
                    else -> LlmInference.Backend.GPU
                }

                Log.d(
                    TAG,
                    "initialize: opts model='${model.name}' path='${model.getPath()}', backendPref='$backendPref', " +
                            "preferredBackend=$preferredBackend, maxTokens=$maxTokens (raw=$maxTokensRaw), topK=$topK, topP=$topP, temp=$temp"
                )

                val baseOpts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(model.getPath())
                    .setMaxTokens(maxTokens)

                var actualBackend = preferredBackend
                val engine = try {
                    LlmInference.createFromOptions(
                        context,
                        baseOpts.setPreferredBackend(preferredBackend).build()
                    )
                } catch (e: Exception) {
                    if (preferredBackend == LlmInference.Backend.GPU) {
                        Log.w(TAG, "GPU init failed. Falling back to CPU: ${e.message}")
                        actualBackend = LlmInference.Backend.CPU
                        LlmInference.createFromOptions(
                            context,
                            baseOpts.setPreferredBackend(LlmInference.Backend.CPU).build()
                        )
                    } else {
                        throw e
                    }
                }

                val params = SessionParams(topK = topK, topP = topP, temperature = temp)
                val session = buildSession(engine, params)

                val actualBackendLabel = if (actualBackend == LlmInference.Backend.CPU) {
                    Accelerator.CPU.label
                } else {
                    Accelerator.GPU.label
                }

                val actualKey = cacheKeyOf(model.getPath(), actualBackendLabel, maxTokens)

                val inst = SlmModelInstance(
                    cacheKey = actualKey,
                    backendLabel = actualBackendLabel,
                    engine = engine,
                    session = session,
                    lastParams = params
                )

                synchronized(stateLock) {
                    instanceCache[actualKey] = inst
                    model.instance = inst
                    if (requestedKey != actualKey) {
                        cacheAliases[requestedKey] = actualKey
                        Log.w(TAG, "initialize: cache alias stored: '$requestedKey' -> '$actualKey'")
                    }
                }

                Log.d(TAG, "initialize: success model='${model.name}', requestedKey='$requestedKey', actualKey='$actualKey'")
                postToMain { onDone("") }
            } catch (e: Exception) {
                Log.e(TAG, "initialize failed: ${e.message}", e)
                postToMain { onDone(cleanError(e.message)) }
            } finally {
                initInFlight.remove(requestedKey)
            }
        }
    }

    /**
     * Rebuilds the LlmInferenceSession for model while keeping the engine.
     */
    fun resetSession(model: Model): Boolean {
        val inst = synchronized(stateLock) {
            model.instance ?: run {
                val requestedKey = cacheKeyOf(model)
                val resolvedKey = resolveCacheKey(requestedKey)
                instanceCache[resolvedKey] ?: instanceCache[requestedKey]
            }
        } ?: return false

        if (isInstanceBusy(inst)) return false

        val desired = paramsFromModel(model)
        Log.d(TAG, "resetSession: model='${model.name}', key='${runtimeKeyOf(model, inst)}', desired=$desired, last=${inst.lastParams}")

        val newSession = runCatching { buildSession(inst.engine, desired) }
            .getOrElse {
                Log.e(TAG, "resetSession: new session build failed: ${it.message}", it)
                return false
            }

        val oldSession: LlmInferenceSession = synchronized(stateLock) {
            val current = model.instance ?: instanceCache[inst.cacheKey] ?: run {
                tryCloseQuietly(newSession)
                return false
            }

            if (current !== inst || isInstanceBusy(current)) {
                tryCloseQuietly(newSession)
                return false
            }

            val old = current.session
            current.session = newSession
            current.lastParams = desired
            model.instance = current
            old
        }

        scheduleOrCloseOldSession(inst, oldSession, "resetSession-swap")

        synchronized(stateLock) {
            val current = model.instance ?: instanceCache[inst.cacheKey] ?: return false
            if (current !== inst) return false

            current.generationSeq.set(0L)
            current.activeGenerationId = 0L
            current.lastDoneGenerationId = 0L
            current.lastGenerateStartMs = 0L
            current.lastGenerateDoneMs = 0L
            current.state.set(RunState.IDLE)
        }

        flushPendingClose(inst, "resetSession-post")
        flushDeferredHardCloseIfReady(inst, "resetSession-post")
        return true
    }

    /**
     * Detach this model from runtime without closing engine/session.
     */
    fun release(model: Model) {
        val inst = model.instance
        model.instance = null

        if (inst != null && !isInstanceBusy(inst)) {
            cleanUpHooks.remove(runtimeKeyOf(model, inst))
        } else {
            Log.d(TAG, "release: detached only (busy or null instance).")
        }
    }

    /**
     * Completely cleans up the model's engine and session.
     *
     * Busy behavior:
     * - If busy, cleanup is deferred until safe, and guarded by a watchdog timeout.
     * - The instance is evicted from cache immediately to prevent new requests.
     */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val inst = synchronized(stateLock) {
            model.instance ?: run {
                val requestedKey = cacheKeyOf(model)
                val resolvedKey = resolveCacheKey(requestedKey)
                instanceCache[resolvedKey] ?: instanceCache[requestedKey]
            }
        } ?: run {
            postToMain { onDone() }
            return
        }

        val runtimeKey = runtimeKeyOf(model, inst)

        Log.d(
            TAG,
            "cleanUp: model='${model.name}', runtimeKey='$runtimeKey', state=${inst.state.get()}, " +
                    "active=${inst.activeGenerationId}, done=${inst.lastDoneGenerationId}"
        )

        synchronized(stateLock) {
            instanceCache.remove(runtimeKey)
            removeAliasesPointingTo(runtimeKey)
            model.instance = null

            if (!isInstanceBusy(inst)) {
                cleanUpHooks.remove(runtimeKey)
            }
        }

        if (isInstanceBusy(inst)) {
            deferHardClose(runtimeKey, inst, reason = "cleanUp-busy") {
                postToMain { onDone() }
            }
            runCatching { inst.session.cancelGenerateResponseAsync() }
                .onFailure { Log.w(TAG, "cleanUp: cancelGenerateResponseAsync failed: ${it.message}") }

            scheduleDeferredHardCloseWatchdog(runtimeKey)
            return
        }

        flushPendingClose(inst, "cleanUp-idle")
        tryCloseQuietly(inst.session)
        safeClose(inst.engine)

        postToMain { onDone() }
    }

    /**
     * Attempts to cancel the current generation for model.
     */
    fun cancel(model: Model) {
        val inst = synchronized(stateLock) {
            model.instance ?: run {
                val requestedKey = cacheKeyOf(model)
                val resolvedKey = resolveCacheKey(requestedKey)
                instanceCache[resolvedKey] ?: instanceCache[requestedKey]
            }
        } ?: return

        model.instance = inst

        val runtimeKey = runtimeKeyOf(model, inst)
        val stateBefore = inst.state.get()
        val active = inst.activeGenerationId
        val done = inst.lastDoneGenerationId
        val genBusy = active > done

        Log.d(
            TAG,
            "cancel: model='${model.name}', runtimeKey='$runtimeKey', stateBefore=$stateBefore, active=$active, done=$done, genBusy=$genBusy"
        )

        if (stateBefore == RunState.IDLE && !genBusy) {
            flushPendingClose(inst, "cancel-idle")
            flushDeferredHardCloseIfReady(inst, "cancel-idle")
            return
        }

        inst.state.set(RunState.CANCELLING)

        runCatching { inst.session.cancelGenerateResponseAsync() }
            .onFailure { Log.w(TAG, "cancelGenerateResponseAsync failed: ${it.message}") }

        if (!genBusy) {
            Log.d(TAG, "cancel: no active generation -> immediate session rebuild + IDLE")
            val old = inst.session
            val params = inst.lastParams
            val newSession = runCatching { buildSession(inst.engine, params) }.getOrNull()

            if (newSession != null) {
                inst.session = newSession
                scheduleOrCloseOldSession(inst, old, "cancel-no-active-rebuild")
            }

            inst.state.set(RunState.IDLE)
            flushPendingClose(inst, "cancel-no-active-flush")
            flushDeferredHardCloseIfReady(inst, "cancel-no-active-flush")
            return
        }

        flushPendingClose(inst, "cancel-post")
    }

    /**
     * Launches an asynchronous inference for model with input.
     */
    fun runInference(
        model: Model,
        input: String,
        listener: ResultListener,
        onClean: CleanUpListener
    ) {
        val inst = synchronized(stateLock) {
            model.instance ?: run {
                val requestedKey = cacheKeyOf(model)
                val resolvedKey = resolveCacheKey(requestedKey)
                instanceCache[resolvedKey] ?: instanceCache[requestedKey]
            }
        } ?: run {
            safeCallResult(listener, "Model not initialized.", true)
            safeCallClean(onClean)
            return
        }

        model.instance = inst

        val runtimeKey = runtimeKeyOf(model, inst)

        if (inst.activeGenerationId > inst.lastDoneGenerationId) {
            Log.w(
                TAG,
                "runInference: refused due to native gen busy model='${model.name}', key='$runtimeKey', " +
                        "active=${inst.activeGenerationId}, done=${inst.lastDoneGenerationId}"
            )
            safeCallResult(listener, "Model '${model.name}' is still processing a previous request.", true)
            safeCallClean(onClean)
            return
        }

        val once = AtomicBoolean(false)

        fun fireClean(tag: String) {
            if (!once.compareAndSet(false, true)) return

            Log.d(
                TAG,
                "runInference: onClean fired tag='$tag' model='${model.name}', key='$runtimeKey', stateBefore=${inst.state.get()}"
            )

            inst.state.set(RunState.IDLE)

            flushPendingClose(inst, "fireClean-$tag")
            flushDeferredHardCloseIfReady(inst, "fireClean-$tag")

            safeCallClean(onClean)
        }

        val desired = paramsFromModel(model)
        if (!ensureSessionParams(inst, desired)) {
            safeCallResult(listener, "Session rebuild failed.", true)
            fireClean("session-rebuild-failed")
            return
        }

        val acquired = inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)
        if (!acquired) {
            cancel(model)
            if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
                safeCallResult(listener, "Model '${model.name}' is busy.", true)
                fireClean("busy-refused")
                return
            }
        }

        Log.d(
            TAG,
            "runInference: start model='${model.name}', key='$runtimeKey', backend='${inst.backendLabel}', " +
                    "state=${inst.state.get()}, lastParams=${inst.lastParams}, input.len=${input.length}"
        )

        val text = input.trim()
        if (text.isNotEmpty()) {
            val ok = addQueryChunkWithOneRetry(model, inst, desired, text)
            if (!ok) {
                safeCallResult(listener, "Failed to add query chunk.", true)
                postToMain { fireClean("addQueryChunk-failed") }
                return
            }
        }

        val genId = markGenerationStart(inst)

        // Install generation-aware cleanup hook.
        cleanUpHooks[runtimeKey] = CleanUpHook(genId) { fireClean("cleanup-hook") }

        try {
            inst.session.generateResponseAsync { partial, done ->
                if (!done) {
                    safeCallResult(listener, partial, false)
                    return@generateResponseAsync
                }

                markGenerationDone(inst, genId)
                flushPendingClose(inst, "done-callback")

                postToMain {
                    safeCallResult(listener, partial, true)

                    // Only remove+invoke if generation matches (prevents late-done races).
                    val hook = cleanUpHooks[runtimeKey]
                    if (hook != null && hook.generationId == genId) {
                        val removed = cleanUpHooks.remove(runtimeKey, hook)
                        if (removed) {
                            runCatching { hook.callback.invoke() }
                                .onFailure { t -> Log.w(TAG, "Cleanup hook threw: ${t.message}", t) }
                        }
                    } else {
                        Log.w(TAG, "Ignoring cleanup hook mismatch: key='$runtimeKey' gen=$genId hookGen=${hook?.generationId}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateResponseAsync failed: ${e.message}", e)
            markGenerationDone(inst, genId)
            flushPendingClose(inst, "exception-generateResponseAsync")

            postToMain {
                safeCallResult(listener, cleanError(e.message), true)

                val hook = cleanUpHooks[runtimeKey]
                if (hook != null && hook.generationId == genId) {
                    val removed = cleanUpHooks.remove(runtimeKey, hook)
                    if (removed) {
                        runCatching { hook.callback.invoke() }
                            .onFailure { t -> Log.w(TAG, "Cleanup hook threw: ${t.message}", t) }
                    }
                } else {
                    fireClean("exception-fallback")
                }
            }
        }
    }

    /* --------------------------------------------------------------------- */
    /* Internal helpers                                                      */
    /* --------------------------------------------------------------------- */

    /** Dispatch work on the main thread. */
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Returns true if the instance is busy (state or native generation). */
    private fun isInstanceBusy(inst: SlmModelInstance): Boolean {
        val stateBusy = inst.state.get() != RunState.IDLE
        val genBusy = inst.activeGenerationId > inst.lastDoneGenerationId
        return stateBusy || genBusy
    }

    /** Derive session parameters from Model.config with sanitization. */
    private fun paramsFromModel(model: Model): SessionParams {
        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
        val temp = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
        return SessionParams(topK = topK, topP = topP, temperature = temp)
    }

    /** Ensure the current session matches desired params. */
    private fun ensureSessionParams(inst: SlmModelInstance, desired: SessionParams): Boolean {
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

    /** Build a new session from engine and params. */
    private fun buildSession(engine: LlmInference, params: SessionParams): LlmInferenceSession =
        LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(params.topK)
                .setTopP(params.topP)
                .setTemperature(params.temperature)
                .build()
        )

    /** Try addQueryChunk; if it fails, rebuild the session once and retry. */
    private fun addQueryChunkWithOneRetry(
        model: Model,
        inst: SlmModelInstance,
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

        if (tryAdd()) return true

        val rebuilt = synchronized(stateLock) {
            val current = model.instance ?: instanceCache[inst.cacheKey] ?: return@synchronized false
            if (current.engine != inst.engine) return@synchronized false

            val old = current.session
            val newSession = runCatching { buildSession(current.engine, desired) }
                .getOrElse {
                    Log.e(TAG, "addQueryChunk: session rebuild failed: ${it.message}", it)
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

    /** Mark the start of a new generation and return its id. */
    private fun markGenerationStart(inst: SlmModelInstance): Long {
        val id = inst.generationSeq.incrementAndGet()
        inst.activeGenerationId = id
        inst.lastGenerateStartMs = SystemClock.elapsedRealtime()
        return id
    }

    /** Mark the completion of a generation. */
    private fun markGenerationDone(inst: SlmModelInstance, id: Long) {
        if (id >= inst.lastDoneGenerationId) {
            inst.lastDoneGenerationId = id
            inst.lastGenerateDoneMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Close old session immediately if native is idle; otherwise defer until done.
     */
    private fun scheduleOrCloseOldSession(inst: SlmModelInstance, old: LlmInferenceSession?, reason: String) {
        if (old == null) return

        val genBusy = inst.activeGenerationId > inst.lastDoneGenerationId
        if (genBusy) {
            val prev = inst.pendingClose.getAndSet(old)
            Log.d(
                TAG,
                "deferClose: reason=$reason active=${inst.activeGenerationId} done=${inst.lastDoneGenerationId} " +
                        "state=${inst.state.get()} prevPending=${prev != null}"
            )

            if (prev != null && prev !== old) {
                runCatching { prev.close() }
                    .onFailure { Log.d(TAG, "deferClose: previous pending close failed: ${it.message}") }
            }
            return
        }

        tryCloseQuietly(old)
    }

    /** Flush a deferred close if it is safe to do so. */
    private fun flushPendingClose(inst: SlmModelInstance, reason: String) {
        val genBusy = inst.activeGenerationId > inst.lastDoneGenerationId
        if (genBusy) {
            Log.d(TAG, "flushPendingClose: skipped reason=$reason active=${inst.activeGenerationId} done=${inst.lastDoneGenerationId}")
            return
        }

        val pending = inst.pendingClose.getAndSet(null)
        if (pending != null) {
            Log.d(TAG, "flushPendingClose: closing deferred session reason=$reason")
            tryCloseQuietly(pending)
        }
    }

    /** Defer a hard close for a busy instance until it becomes safe. */
    private fun deferHardClose(key: String, inst: SlmModelInstance, reason: String, onDone: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        deferredHardCloses.compute(key) { _, prev ->
            val callbacks = prev?.callbacks ?: mutableListOf()
            callbacks.add(onDone)
            DeferredHardClose(
                instance = inst,
                callbacks = callbacks,
                requestedAtMs = prev?.requestedAtMs ?: now,
                reason = prev?.reason ?: reason
            )
        }
        Log.w(TAG, "deferHardClose: key='$key' reason='$reason' (busy)")
    }

    /**
     * Watchdog: if deferred hard-close sits too long, force-close.
     */
    private fun scheduleDeferredHardCloseWatchdog(key: String) {
        thread(name = "SLM-hardclose-watchdog-$key") {
            while (true) {
                val entry = deferredHardCloses[key] ?: return@thread

                val now = SystemClock.elapsedRealtime()
                val age = now - entry.requestedAtMs
                val inst = entry.instance

                // If already safe, let normal flush handle it.
                if (!isInstanceBusy(inst)) {
                    flushDeferredHardCloseIfReady(inst, "watchdog-safe")
                    return@thread
                }

                if (age >= HARD_CLOSE_TIMEOUT_MS) {
                    Log.e(TAG, "WATCHDOG: forcing hard close key='$key' ageMs=$age reason='${entry.reason}'")

                    // Best-effort cancel before forced close.
                    runCatching { inst.session.cancelGenerateResponseAsync() }

                    // One last short grace.
                    Thread.sleep(250)

                    // Force close.
                    runCatching { inst.session.close() }
                        .onFailure { Log.w(TAG, "WATCHDOG: session close failed: ${it.message}") }
                    runCatching { inst.engine.close() }
                        .onFailure { Log.w(TAG, "WATCHDOG: engine close failed: ${it.message}") }

                    deferredHardCloses.remove(key)

                    postToMain {
                        entry.callbacks.forEach { cb ->
                            runCatching { cb.invoke() }
                                .onFailure { t -> Log.w(TAG, "WATCHDOG callback failed: ${t.message}", t) }
                        }
                    }
                    return@thread
                }

                Thread.sleep(HARD_CLOSE_POLL_MS)
            }
        }
    }

    /** Flush deferred hard close if the instance is now safe to close. */
    private fun flushDeferredHardCloseIfReady(inst: SlmModelInstance, reason: String) {
        val key = inst.cacheKey.takeIf { it.isNotBlank() } ?: return
        val entry = deferredHardCloses[key] ?: return

        if (entry.instance !== inst) return

        if (isInstanceBusy(entry.instance)) {
            Log.d(TAG, "flushDeferredHardCloseIfReady: still busy key='$key' reason='$reason'")
            return
        }

        deferredHardCloses.remove(key)

        Log.w(TAG, "flushDeferredHardCloseIfReady: closing deferred engine/session key='$key' reason='$reason'")
        flushPendingClose(entry.instance, "deferred-hard-close")
        tryCloseQuietly(entry.instance.session)
        safeClose(entry.instance.engine)

        postToMain {
            entry.callbacks.forEach { cb ->
                runCatching { cb.invoke() }
                    .onFailure { t -> Log.w(TAG, "Deferred cleanup callback failed: ${t.message}", t) }
            }
        }
    }

    /** Sanitize TopK - must be >= 1. */
    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)

    /** Sanitize TopP - must be in [0, 1]. */
    private fun sanitizeTopP(p: Float): Float = p.takeIf { it in 0f..1f } ?: DEFAULT_TOP_P

    /** Sanitize Temperature - typical safe band [0, 2]. */
    private fun sanitizeTemperature(t: Float): Float = t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    /** Normalize accelerator preference string for stable cache keys. */
    private fun normalizedAccelerator(model: Model): String =
        model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)
            .trim()
            .uppercase()
            .ifBlank { Accelerator.GPU.label }

    /** Build a stable runtime key. */
    private fun cacheKeyOf(path: String, backendLabel: String, maxTokens: Int): String {
        val p = path.trim()
        val b = backendLabel.trim().uppercase().ifBlank { Accelerator.GPU.label }
        return "$p|$b|$maxTokens"
    }

    /** Build a process-wide requested cache key. */
    private fun cacheKeyOf(model: Model): String {
        val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN).coerceIn(1, ABS_MAX_TOKENS)
        val backendPref = normalizedAccelerator(model)
        return cacheKeyOf(model.getPath(), backendPref, maxTokens)
    }

    /** Resolve requested cache key through alias mapping. */
    private fun resolveCacheKey(requestedKey: String): String {
        var key = requestedKey
        repeat(4) {
            val next = cacheAliases[key] ?: return key
            if (next == key) return key
            key = next
        }
        return key
    }

    /** Remove aliases that point to targetKey. */
    private fun removeAliasesPointingTo(targetKey: String) {
        val toRemove = cacheAliases.entries
            .filter { it.value == targetKey || it.key == targetKey }
            .map { it.key }

        for (k in toRemove) cacheAliases.remove(k)
    }

    /** Stable runtime key for cleanup/listener routing. */
    private fun runtimeKeyOf(model: Model, inst: SlmModelInstance?): String {
        val k = inst?.cacheKey?.takeIf { it.isNotBlank() }
        if (k != null) return k
        val requested = cacheKeyOf(model)
        return resolveCacheKey(requested)
    }

    /** Find the cache key that maps to the given instance by identity. */
    private fun findCacheKeyForInstance(inst: SlmModelInstance): String? =
        instanceCache.entries.firstOrNull { it.value === inst }?.key

    /** Clean and compress error messages for UI. */
    private fun cleanError(msg: String?): String =
        msg
            ?.replace("INTERNAL:", "", ignoreCase = true)
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.take(ERROR_MAX_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown error"

    /** Try to cancel and close a session quietly. */
    private fun tryCloseQuietly(session: LlmInferenceSession?) {
        runCatching {
            session?.cancelGenerateResponseAsync()
            session?.close()
        }.onFailure {
            Log.w(TAG, "Session close failed: ${it.message}")
        }
    }

    /** Close an engine quietly. */
    private fun safeClose(engine: LlmInference?) {
        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "Engine close failed: ${it.message}") }
    }

    /** Invoke listener safely on Main without crashing the calling thread. */
    private fun safeCallResult(listener: ResultListener, text: String, done: Boolean) {
        postToMain {
            runCatching { listener(text, done) }
                .onFailure { Log.w(TAG, "ResultListener threw: ${it.message}", it) }
        }
    }

    /** Invoke onClean safely on Main without crashing the calling thread. */
    private fun safeCallClean(onClean: CleanUpListener) {
        postToMain {
            runCatching { onClean() }
                .onFailure { Log.w(TAG, "CleanUpListener threw: ${it.message}", it) }
        }
    }
}
