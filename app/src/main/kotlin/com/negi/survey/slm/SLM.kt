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
import java.util.concurrent.atomic.AtomicReference

/**
 * Hardware accelerator options for inference (CPU or GPU).
 */
enum class Accelerator(val label: String) { CPU("CPU"), GPU("GPU") }

/**
 * Configuration keys for LLM inference.
 */
enum class ConfigKey { MAX_TOKENS, TOP_K, TOP_P, TEMPERATURE, ACCELERATOR }

// Default values for model parameters
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
 * Holds the initialized engine and session for a model.
 *
 * @property engine Underlying LlmInference engine instance.
 * @property session Active LlmInferenceSession that can be rebuilt.
 * @property state Current run state for this model instance.
 */
data class LlmModelInstance(
    val engine: LlmInference,
    @Volatile var session: LlmInferenceSession,
    val state: AtomicReference<RunState> = AtomicReference(RunState.IDLE),
)

/**
 * Safe Language Model inference helper.
 *
 * High-level contract:
 * - [initialize] prepares an engine + session; idempotent when idle.
 * - [runInference] streams partial results and eventually calls [CleanUpListener].
 * - [cancel] attempts to stop the current generation and synthesize cleanup.
 * - [resetSession] rebuilds the session while reusing the engine when idle.
 * - [cleanUp] fully disposes engine/session and clears listeners.
 */
object SLM {

    /** Per-model cleanup listeners keyed by model identity. */
    private val cleanUpListeners = ConcurrentHashMap<String, CleanUpListener>()

    /**
     * Returns true when the model has an instance and its run state is not [RunState.IDLE].
     */
    fun isBusy(model: Model): Boolean =
        model.instance?.state?.get()?.let { it != RunState.IDLE } == true

    /**
     * Initializes an engine + session for [model].
     *
     * Behavior:
     * - If the existing instance is not idle, the call fails and [onDone] receives a message.
     * - If idle, the old session/engine are closed and replaced by a fresh instance.
     * - On success, [onDone] receives an empty string.
     * - On failure, [onDone] receives a cleaned error message.
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
            cleanUpListeners.remove(keyOf(model))?.invoke()
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
            val session = buildSessionFromModel(engine, topK, topP, temp)
            model.instance = LlmModelInstance(engine, session)
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
     * Requirements:
     * - The model must have an existing instance.
     * - The instance must be idle.
     *
     * @return true on successful reset; false if conditions are not met or reset failed.
     */
    fun resetSession(model: Model): Boolean {
        val snap = synchronized(this) {
            val inst = model.instance ?: return false
            if (inst.state.get() != RunState.IDLE) return false
            Snap(
                inst.engine,
                inst.session,
                sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K)),
                sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P)),
                sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
            )
        }

        tryCloseQuietly(snap.oldSession)
        val newSession = try {
            buildSessionFromModel(snap.engine, snap.topK, snap.topP, snap.temperature)
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
        }
        return true
    }

    /**
     * Completely cleans up the model's engine and session and disposes resources.
     *
     * Behavior:
     * - If busy, attempts to cancel generation and synthesize cleanup.
     * - Clears any pending cleanup listener.
     * - Closes session and engine and clears [Model.instance].
     */
    @Synchronized
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val inst = model.instance ?: run {
            onDone()
            return
        }

        if (inst.state.get() != RunState.IDLE) {
            inst.session.cancelGenerateResponseAsync()
            inst.state.set(RunState.IDLE)
            cleanUpListeners.remove(keyOf(model))?.invoke()
        } else {
            cleanUpListeners.remove(keyOf(model))?.invoke()
        }

        model.instance = null
        tryCloseQuietly(inst.session)
        safeClose(inst.engine)
        onDone()
    }

    /**
     * Attempts to cancel the current generation for [model].
     *
     * Behavior:
     * - If the instance is busy, switches state to [RunState.CANCELLING],
     *   calls cancel on the session, and then marks state idle.
     * - Synthesizes an onClean callback via the stored cleanup listener.
     */
    @Synchronized
    fun cancel(model: Model) {
        val inst = model.instance ?: return
        if (inst.state.get() != RunState.IDLE) {
            inst.state.set(RunState.CANCELLING)
            runCatching { inst.session.cancelGenerateResponseAsync() }
                .onFailure { Log.w(TAG, "cancelGenerateResponseAsync failed: ${it.message}") }
            inst.state.set(RunState.IDLE)
            cleanUpListeners.remove(keyOf(model))?.invoke()
        }
    }

    /**
     * Launches an asynchronous inference for [model] with [input].
     *
     * Contract:
     * - [listener] receives zero or more partial results and a final callback with done=true.
     * - [onClean] is invoked exactly once per call when the session is considered clean
     *   for this inference (either normal completion or cancellation).
     *
     * Error handling:
     * - If the model is not initialized, [listener] is called with an error and done=true.
     * - If starting generation throws, [listener] is called once with an error and done=true,
     *   and [onClean] is invoked via the cleanup listener.
     */
    fun runInference(
        model: Model,
        input: String,
        listener: ResultListener,
        onClean: CleanUpListener
    ) {
        val inst = model.instance ?: run {
            listener("Model not initialized.", true)
            return
        }

        if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
            cancel(model)
            inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)
        }

        Log.d(TAG, "runInference called with model='${model.name}', input.length=${input.length}")

        cleanUpListeners[keyOf(model)] = {
            inst.state.set(RunState.IDLE)
            onClean()
        }

        val text = input.trim()
        if (text.isNotEmpty()) {
            runCatching { inst.session.addQueryChunk(text) }
                .onFailure {
                    Log.e(TAG, "addQueryChunk failed: ${it.message}", it)
                    listener(cleanError(it.message), true)
                    cleanUpListeners.remove(keyOf(model))?.invoke()
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
                    cleanUpListeners.remove(keyOf(model))?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateResponseAsync failed: ${e.message}", e)
            listener(cleanError(e.message), true)
            cleanUpListeners.remove(keyOf(model))?.invoke()
        }
    }

    /* --------------------------------------------------------------------- */
    /* Internal helpers                                                      */
    /* --------------------------------------------------------------------- */

    private fun buildSessionFromModel(
        engine: LlmInference,
        topK: Int,
        topP: Float,
        temp: Float
    ): LlmInferenceSession =
        LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(topK)
                .setTopP(topP)
                .setTemperature(temp)
                .build()
        )

    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)

    private fun sanitizeTopP(p: Float): Float =
        p.takeIf { it in 0f..1f } ?: DEFAULT_TOP_P

    private fun sanitizeTemperature(t: Float): Float =
        t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    private fun keyOf(model: Model): String = "${model.name}#${System.identityHashCode(model)}"

    private fun cleanError(msg: String?): String =
        msg
            ?.replace("INTERNAL:", "")
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown error"

    private fun tryCloseQuietly(session: LlmInferenceSession?) {
        runCatching {
            session?.cancelGenerateResponseAsync()
            session?.close()
        }.onFailure {
            Log.w(TAG, "Session close failed: ${it.message}")
        }
    }

    private fun safeClose(engine: LlmInference?) {
        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "Engine close failed: ${it.message}") }
    }

    private data class Snap(
        val engine: LlmInference,
        val oldSession: LlmInferenceSession,
        val topK: Int,
        val topP: Float,
        val temperature: Float
    )
}
