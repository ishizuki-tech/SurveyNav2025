/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: WhisperEngine.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.whisper

import android.content.Context
import android.util.Log
import com.negi.whispers.media.decodeWaveFile
import com.whispercpp.whisper.WhisperContext
import java.io.File
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOG_TAG = "WhisperEngine"
private const val DEFAULT_TARGET_SAMPLE_RATE = 16_000
private const val ASSET_KEY_PREFIX = "asset:"

/**
 * Thin facade for integrating Whisper.cpp into the SurveyNav app.
 *
 * This object wraps [WhisperContext] and [decodeWaveFile] to provide a small,
 * suspend-friendly API.
 *
 * Key design:
 * - The engine is a process-wide singleton.
 * - Initialization is idempotent per model key.
 * - All operations touching the underlying [WhisperContext] are serialized
 *   through a single [engineMutex].
 *
 * Two-level cleanup:
 * - [detach] is a soft UI-level detach that keeps the native context alive.
 * - [release] is a hard cleanup that closes native resources.
 *
 * This split prevents unnecessary re-initialization when Compose screens
 * are disposed and recreated during navigation or "restart" flows.
 */
object WhisperEngine {

    /**
     * Current active Whisper context.
     *
     * Access must be guarded by [engineMutex] for mutation.
     */
    @Volatile
    private var whisperContext: WhisperContext? = null

    /**
     * Identifier of the model used to create [whisperContext].
     *
     * - For file-based models: absolute file path.
     * - For asset-based models: synthetic key "asset:<path>".
     */
    @Volatile
    private var modelKey: String? = null

    /**
     * Single mutex to serialize init / transcribe / release.
     */
    private val engineMutex = Mutex()

    /**
     * Returns the currently bound model key, if any.
     */
    fun currentModelKey(): String? = modelKey

    /**
     * Returns true if a Whisper context is currently alive.
     */
    fun isInitialized(): Boolean = whisperContext != null

    /**
     * Returns true if initialized with the given file path.
     */
    fun isInitializedForFile(modelFile: File): Boolean =
        whisperContext != null && modelKey == modelFile.absolutePath

    /**
     * Returns true if initialized with the given asset path.
     */
    fun isInitializedForAsset(assetPath: String): Boolean =
        whisperContext != null && modelKey == ASSET_KEY_PREFIX + assetPath

    /**
     * Ensure that a Whisper model is loaded from [modelFile].
     *
     * Behavior:
     * - If the engine is already initialized with the same file path,
     *   returns immediately with [Result.success].
     * - If a different model is active, the old context is released before
     *   creating a new one.
     *
     * @param context Android [Context]. Currently used for parity with asset init.
     * @param modelFile Local Whisper model file (GGML/GGUF). Must exist.
     */
    suspend fun ensureInitializedFromFile(
        context: Context,
        modelFile: File
    ): Result<Unit> = withContext(Dispatchers.Default) {

        if (!modelFile.exists() || !modelFile.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException(
                    "Whisper model file does not exist: ${modelFile.path}"
                )
            )
        }

        val key = modelFile.absolutePath

        /**
         * Fast path without locking.
         */
        if (whisperContext != null && modelKey == key) {
            Log.d(LOG_TAG, "Already initialized with model file=$key")
            return@withContext Result.success(Unit)
        }

        engineMutex.withLock {
            val current = whisperContext

            /**
             * Double-check inside the lock.
             */
            if (current != null && modelKey == key) {
                Log.d(LOG_TAG, "Already initialized with model file=$key (locked)")
                return@withLock Result.success(Unit)
            }

            /**
             * Release any previous context before switching models.
             */
            if (current != null) {
                runCatching {
                    Log.i(LOG_TAG, "Releasing previous WhisperContext for $modelKey")
                    current.release()
                }.onFailure { e ->
                    Log.w(LOG_TAG, "Error while releasing previous WhisperContext", e)
                }
            }

            whisperContext = null
            modelKey = null

            var created: WhisperContext? = null
            val elapsed = measureTimeMillis {
                val createdResult = runCatching {
                    Log.i(LOG_TAG, "Creating WhisperContext from file=$key")
                    WhisperContext.createContextFromFile(key)
                }.onFailure { e ->
                    Log.e(LOG_TAG, "Failed to create WhisperContext from $key", e)
                }

                if (createdResult.isFailure) {
                    return@withLock Result.failure(createdResult.exceptionOrNull()!!)
                }

                created = createdResult.getOrThrow()
            }

            whisperContext = created
            modelKey = key

            Log.i(LOG_TAG, "WhisperEngine initialized with model file=$key (${elapsed}ms)")
            Result.success(Unit)
        }
    }

    /**
     * Ensure that a Whisper model is loaded from app assets at [assetPath].
     *
     * Example:
     * - assetPath = "models/ggml-small-q5_1.bin"
     *
     * Behavior mirrors [ensureInitializedFromFile] but uses
     * [WhisperContext.createContextFromAsset].
     *
     * @param context Android [Context] used for asset access.
     * @param assetPath Relative asset path under the APK assets directory.
     */
    suspend fun ensureInitializedFromAsset(
        context: Context,
        assetPath: String
    ): Result<Unit> = withContext(Dispatchers.Default) {

        /**
         * Lightweight asset existence check.
         */
        val assetOk = runCatching {
            context.assets.open(assetPath).use { }
            true
        }.getOrElse { false }

        if (!assetOk) {
            return@withContext Result.failure(
                IllegalArgumentException(
                    "Whisper asset model does not exist: assets/$assetPath"
                )
            )
        }

        val key = ASSET_KEY_PREFIX + assetPath

        /**
         * Fast path without locking.
         */
        if (whisperContext != null && modelKey == key) {
            Log.d(LOG_TAG, "Already initialized with asset model=$assetPath")
            return@withContext Result.success(Unit)
        }

        engineMutex.withLock {
            val current = whisperContext

            /**
             * Double-check inside the lock.
             */
            if (current != null && modelKey == key) {
                Log.d(LOG_TAG, "Already initialized with asset model=$assetPath (locked)")
                return@withLock Result.success(Unit)
            }

            /**
             * Release any previous context before switching models.
             */
            if (current != null) {
                runCatching {
                    Log.i(LOG_TAG, "Releasing previous WhisperContext for $modelKey")
                    current.release()
                }.onFailure { e ->
                    Log.w(LOG_TAG, "Error while releasing previous WhisperContext", e)
                }
            }

            whisperContext = null
            modelKey = null

            var created: WhisperContext? = null
            val elapsed = measureTimeMillis {
                val createdResult = runCatching {
                    Log.i(LOG_TAG, "Creating WhisperContext from assets/$assetPath")
                    WhisperContext.createContextFromAsset(context.assets, assetPath)
                }.onFailure { e ->
                    Log.e(LOG_TAG, "Failed to create WhisperContext from assets/$assetPath", e)
                }

                if (createdResult.isFailure) {
                    return@withLock Result.failure(createdResult.exceptionOrNull()!!)
                }

                created = createdResult.getOrThrow()
            }

            whisperContext = created
            modelKey = key

            Log.i(LOG_TAG, "WhisperEngine initialized with asset model=$assetPath (${elapsed}ms)")
            Result.success(Unit)
        }
    }

    /**
     * Transcribe the given WAV [file] and return plain-text output.
     *
     * Steps:
     * 1) Decode WAV to mono float PCM via [decodeWaveFile].
     * 2) Log basic PCM statistics (size, min, max, RMS).
     * 3) Call [WhisperContext.transcribeData] for one or more languages:
     *    - If [lang] == "auto", tries "auto" then "en", "ja", "sw".
     *    - Otherwise, tries only the specified [lang].
     *
     * Return policy:
     * - The first non-empty trimmed transcript is returned as success.
     * - If all attempts produce empty text without throwing, the last empty
     *   trimmed result is returned as success.
     *
     * Thread-safety:
     * - The Whisper JNI call is protected by [engineMutex].
     *
     * @param file Input WAV file (PCM16 or Float32). Must exist.
     * @param lang Language code ("en", "ja", "sw", or "auto").
     * @param translate If true, runs Whisper in translation-to-English mode.
     * @param printTimestamp If true, appends timestamps to each output line.
     * @param targetSampleRate Target sample rate for decoding.
     */
    suspend fun transcribeWaveFile(
        file: File,
        lang: String,
        translate: Boolean = false,
        printTimestamp: Boolean = false,
        targetSampleRate: Int = DEFAULT_TARGET_SAMPLE_RATE
    ): Result<String> = withContext(Dispatchers.Default) {

        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Input WAV file does not exist: ${file.path}")
            )
        }

        val pcmResult = runCatching {
            decodeWaveFile(
                file = file,
                targetSampleRate = targetSampleRate
            )
        }.onFailure { e ->
            Log.e(LOG_TAG, "Failed to decode WAV file=${file.path}", e)
        }

        if (pcmResult.isFailure) {
            return@withContext Result.failure(pcmResult.exceptionOrNull()!!)
        }

        val pcm = pcmResult.getOrThrow()

        if (pcm.isEmpty()) {
            Log.w(LOG_TAG, "Decoded PCM buffer is empty for file: ${file.name}")
            return@withContext Result.failure(
                IllegalStateException("Decoded PCM buffer is empty for file: ${file.name}")
            )
        }

        val stats = computePcmStats(pcm)
        Log.d(
            LOG_TAG,
            "PCM stats for file=${file.name}: " +
                    "samples=${pcm.size}, min=${stats.min}, max=${stats.max}, rms=${stats.rms}"
        )

        val languageAttempts: List<String> =
            if (lang.lowercase() == "auto") {
                listOf("auto", "en", "ja", "sw")
            } else {
                listOf(lang)
            }

        engineMutex.withLock {

            val ctx = whisperContext
                ?: return@withLock Result.failure(
                    IllegalStateException(
                        "WhisperEngine is not initialized. " +
                                "Call ensureInitializedFromFile() or ensureInitializedFromAsset() first."
                    )
                )

            var lastEmptySuccess: String? = null
            var lastFailure: Throwable? = null

            for (code in languageAttempts) {
                val result = runCatching {
                    Log.i(
                        LOG_TAG,
                        "Transcribing with lang=$code translate=$translate " +
                                "printTimestamp=$printTimestamp samples=${pcm.size}"
                    )
                    ctx.transcribeData(
                        data = pcm,
                        lang = code,
                        translate = translate,
                        printTimestamp = printTimestamp
                    )
                }.onFailure { e ->
                    Log.e(
                        LOG_TAG,
                        "Whisper transcription failed for file=${file.path} lang=$code",
                        e
                    )
                }

                if (result.isFailure) {
                    lastFailure = result.exceptionOrNull()
                    continue
                }

                val trimmed = result.getOrThrow().trim()

                val preview =
                    if (trimmed.length > 120) trimmed.take(90) + " … " + trimmed.takeLast(20)
                    else trimmed

                Log.d(
                    LOG_TAG,
                    "Whisper result for lang=$code: length=${trimmed.length}, preview=\"$preview\""
                )

                if (trimmed.isNotEmpty()) {
                    return@withLock Result.success(trimmed)
                }

                lastEmptySuccess = trimmed
                Log.w(
                    LOG_TAG,
                    "Empty transcript for lang=$code; will try next language (if any)."
                )
            }

            when {
                lastEmptySuccess != null -> Result.success(lastEmptySuccess!!)
                lastFailure != null -> Result.failure(lastFailure!!)
                else -> Result.failure(
                    IllegalStateException("Transcription produced no usable result.")
                )
            }
        }
    }

    /**
     * Softly detach the engine from UI lifecycle without releasing native resources.
     *
     * This is intended for:
     * - Compose screen disposal.
     * - Navigation resets.
     * - "Restart" flows that should not invalidate the heavy model load.
     *
     * The underlying [WhisperContext] remains alive in the process.
     */
    suspend fun detach() {
        engineMutex.withLock {
            Log.d(LOG_TAG, "Detach called. Keeping native context alive for key=$modelKey")
        }
    }

    /**
     * Release the active Whisper context, if any, and reset the engine.
     *
     * This is a hard cleanup:
     * - Frees native resources.
     * - Clears [modelKey].
     *
     * After calling [release], callers must re-initialize before transcription.
     */
    suspend fun release() {
        engineMutex.withLock {
            val ctx = whisperContext
            if (ctx == null) {
                modelKey = null
                return
            }

            whisperContext = null
            val oldKey = modelKey
            modelKey = null

            runCatching {
                Log.i(LOG_TAG, "Releasing WhisperContext for $oldKey")
                ctx.release()
            }.onFailure { e ->
                Log.w(LOG_TAG, "Error while releasing WhisperContext", e)
            }
        }
    }

    /**
     * Alias of [release] for clarity when a hard cleanup is explicitly desired.
     */
    suspend fun cleanUp() {
        release()
    }

    /**
     * Simple statistics container for PCM buffers.
     */
    private data class PcmStats(
        val min: Float,
        val max: Float,
        val rms: Double
    )

    /**
     * Compute [PcmStats] for the given [pcm] buffer.
     */
    private fun computePcmStats(pcm: FloatArray): PcmStats {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sumSq = 0.0

        for (v in pcm) {
            if (v < min) min = v
            if (v > max) max = v
            sumSq += v.toDouble() * v.toDouble()
        }

        val rms = if (pcm.isNotEmpty()) sqrt(sumSq / pcm.size) else 0.0

        return PcmStats(
            min = if (min.isFinite()) min else 0f,
            max = if (max.isFinite()) max else 0f,
            rms = rms
        )
    }
}
