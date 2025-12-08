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
 *  on Android. Responsibilities:
 *
 *    - Initialize and configure LlmInference / LlmInferenceSession.
 *    - Stream responses via generateResponseAsync with partial tokens.
 *    - Provide cancellation and cleanup hooks with session reuse.
 *    - Expose simple busy-state checks for higher-level watchdogs.
 *
 *  This object is designed to be called from a single coordinator
 *  (for example, SlmDirectRepository) that serializes requests across
 *  the app process.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 */
data class LlmModelInstance(
    val engine: LlmInference,
    @Volatile var session: LlmInferenceSession,
    val state: AtomicReference<RunState> = AtomicReference(RunState.IDLE),
    @Volatile var lastParams: SessionParams = SessionParams()
)

/**
 * Safe Language Model inference helper.
 */
object SLM {

    /** Per-model cleanup listeners keyed by model identity. */
    private val cleanUpListeners = ConcurrentHashMap<String, () -> Unit>()

    /**
     * Returns true when the model has an instance and its run state is not [RunState.IDLE].
     */
    fun isBusy(model: Model): Boolean =
        model.instance?.state?.get()?.let { it != RunState.IDLE } == true

    /**
     * Initializes an engine + session for [model].
     */
    @Synchronized
    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        var oldEngine: LlmInference? = null
        var oldSession: LlmInferenceSession? = null

        model.instance?.let { inst ->
            if (inst.state.get() != RunState.IDLE) {
                onDone("Model '${model.name}' is busy. Try again after done=true or call cancel().")
                return
            }

            oldSession = inst.session
            oldEngine = inst.engine
            cleanUpListeners.remove(keyOf(model))
            model.instance = null
        }

        tryCloseQuietly(oldSession)
        safeClose(oldEngine)

        val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
        val temp = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
        val backendPref = model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)

        val backend = when (backendPref) {
            Accelerator.CPU.label -> LlmInference.Backend.CPU
            else -> LlmInference.Backend.GPU
        }

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
            val session = buildSession(engine, params)

            model.instance = LlmModelInstance(
                engine = engine,
                session = session,
                lastParams = params
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
     */
    fun resetSession(model: Model): Boolean {
        val snap = synchronized(this) {
            val inst = model.instance ?: return false
            if (inst.state.get() != RunState.IDLE) return false

            val params = paramsFromModel(model)
            Snap(inst.engine, inst.session, params)
        }

        tryCloseQuietly(snap.oldSession)

        val newSession = try {
            buildSession(snap.engine, snap.params)
        } catch (e: Exception) {
            Log.e(TAG, "Session reset failed: ${e.message}", e)
            return false
        }

        synchronized(this) {
            val inst = model.instance ?: return false.also { tryCloseQuietly(newSession) }
            if (inst.engine != snap.engine || inst.state.get() != RunState.IDLE) {
                tryCloseQuietly(newSession)
                return false
            }
            inst.session = newSession
            inst.lastParams = snap.params
        }
        return true
    }

    /**
     * Completely cleans up the model's engine and session and disposes resources.
     */
    @Synchronized
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val inst = model.instance ?: run {
            onDone()
            return
        }

        cleanUpListeners.remove(keyOf(model))?.invoke()

        runCatching {
            if (inst.state.get() != RunState.IDLE) {
                inst.session.cancelGenerateResponseAsync()
            }
        }

        inst.state.set(RunState.IDLE)

        model.instance = null
        tryCloseQuietly(inst.session)
        safeClose(inst.engine)

        onDone()
    }

    /**
     * Attempts to cancel the current generation for [model].
     */
    @Synchronized
    fun cancel(model: Model) {
        val inst = model.instance ?: return
        if (inst.state.get() == RunState.IDLE) return

        inst.state.set(RunState.CANCELLING)

        runCatching { inst.session.cancelGenerateResponseAsync() }
            .onFailure { Log.w(TAG, "cancelGenerateResponseAsync failed: ${it.message}") }

        inst.state.set(RunState.IDLE)

        cleanUpListeners.remove(keyOf(model))?.invoke()
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
        val key = keyOf(model)
        val once = AtomicBoolean(false)

        fun fireClean(tag: String) {
            if (once.compareAndSet(false, true)) {
                Log.d(TAG, "onClean fired: $tag (model='${model.name}')")
                model.instance?.state?.set(RunState.IDLE)
                onClean()
            }
        }

        val inst = synchronized(this) {
            model.instance
        } ?: run {
            listener("Model not initialized.", true)
            return
        }

        val acquired = inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)
        if (!acquired) {
            cancel(model)
            if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
                listener("Model '${model.name}' is busy.", true)
                fireClean("busy-refused")
                return
            }
        }

        cleanUpListeners[key] = { fireClean("cleanup-listener") }

        val text = input.trim()
        if (text.isNotEmpty()) {
            val desired = paramsFromModel(model)
            val ok = addQueryChunkWithOneRetry(model, inst, desired, text)
            if (!ok) {
                listener("Failed to add query chunk.", true)
                cleanUpListeners.remove(key)?.invoke()
                return
            }
        }

        try {
            inst.session.generateResponseAsync { partial, done ->
                val preview =
                    if (partial.length > 256) {
                        partial.take(128) + " … " + partial.takeLast(64)
                    } else {
                        partial
                    }

                Log.d(TAG, "partial[len=${partial.length}, done=$done]: $preview")

                if (!done) {
                    listener(partial, false)
                } else {
                    listener(partial, true)
                    cleanUpListeners.remove(key)?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateResponseAsync failed: ${e.message}", e)
            listener(cleanError(e.message), true)
            cleanUpListeners.remove(key)?.invoke()
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

        if (tryAdd()) return true

        val rebuilt = synchronized(this) {
            val current = model.instance ?: return@synchronized false
            if (current.engine != inst.engine) return@synchronized false

            val old = current.session
            val newSession = runCatching { buildSession(current.engine, desired) }
                .getOrElse {
                    Log.e(TAG, "Session rebuild failed: ${it.message}", it)
                    return@synchronized false
                }

            current.session = newSession
            current.lastParams = desired

            tryCloseQuietly(old)
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
     * Build a stable identity key for per-model listeners.
     */
    private fun keyOf(model: Model): String =
        "${model.name}#${System.identityHashCode(model)}"

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
        val engine: LlmInference,
        val oldSession: LlmInferenceSession,
        val params: SessionParams
    )
}
