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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOG_TAG = "WhisperEngine"

/**
 * Thin facade for integrating Whisper.cpp into the SurveyNav app.
 *
 * This object wraps [WhisperContext] and [decodeWaveFile] to provide a small,
 * suspend-friendly API:
 *
 * - [ensureInitializedFromFile]  Loads a Whisper model from a local file.
 * - [ensureInitializedFromAsset] Loads a Whisper model from app assets.
 * - [transcribeWaveFile]         Decodes a WAV file and runs transcription.
 * - [release]                    Frees native resources and resets the engine.
 *
 * Thread-safety:
 * - All operations that touch the underlying [WhisperContext] are serialized
 *   through [engineMutex]. This prevents a dangerous race where:
 *   - one coroutine is transcribing while
 *   - another coroutine releases or swaps the model.
 *
 * Performance note:
 * - WAV decoding is performed outside the mutex to minimize lock time.
 * - The actual Whisper JNI call is protected by the mutex.
 */
object WhisperEngine {

    /**
     * Current active Whisper context.
     *
     * Access must be guarded by [engineMutex].
     */
    @Volatile
    private var context: WhisperContext? = null

    /**
     * Identifier of the model used to create [context].
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

    // ---------------------------------------------------------------------
    // Initialization
    // ---------------------------------------------------------------------

    /**
     * Ensure that a Whisper model is loaded from [modelFile].
     *
     * Behavior:
     * - If the engine is already initialized with the same file path, this
     *   returns immediately with [Result.success].
     * - If a different model is active, the old context is released before
     *   creating a new one.
     *
     * @param context Android [Context]. Stored for future extension.
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

        engineMutex.withLock {
            val key = modelFile.absolutePath
            val current = this@WhisperEngine.context

            // Fast path: already initialized with the same model file.
            if (current != null && modelKey == key) {
                Log.d(LOG_TAG, "Already initialized with model file=$key")
                return@withLock Result.success(Unit)
            }

            // Release any previous context before switching models.
            if (current != null) {
                runCatching {
                    Log.i(LOG_TAG, "Releasing previous WhisperContext for $modelKey")
                    current.release()
                }.onFailure { e ->
                    Log.w(LOG_TAG, "Error while releasing previous WhisperContext", e)
                }
            }

            this@WhisperEngine.context = null
            this@WhisperEngine.modelKey = null

            val createdResult = runCatching {
                Log.i(LOG_TAG, "Creating WhisperContext from file=$key")
                WhisperContext.createContextFromFile(key)
            }.onFailure { e ->
                Log.e(LOG_TAG, "Failed to create WhisperContext from $key", e)
            }

            if (createdResult.isFailure) {
                return@withLock Result.failure(createdResult.exceptionOrNull()!!)
            }

            val created = createdResult.getOrThrow()

            this@WhisperEngine.context = created
            this@WhisperEngine.modelKey = key

            Log.i(LOG_TAG, "WhisperEngine initialized with model file=$key")
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
     */
    suspend fun ensureInitializedFromAsset(
        context: Context,
        assetPath: String
    ): Result<Unit> = withContext(Dispatchers.Default) {

        engineMutex.withLock {
            val key = "asset:$assetPath"
            val current = this@WhisperEngine.context

            // Fast path: already initialized with the same asset model.
            if (current != null && modelKey == key) {
                Log.d(LOG_TAG, "Already initialized with asset model=$assetPath")
                return@withLock Result.success(Unit)
            }

            // Release any previous context before switching models.
            if (current != null) {
                runCatching {
                    Log.i(LOG_TAG, "Releasing previous WhisperContext for $modelKey")
                    current.release()
                }.onFailure { e ->
                    Log.w(LOG_TAG, "Error while releasing previous WhisperContext", e)
                }
            }

            this@WhisperEngine.context = null
            this@WhisperEngine.modelKey = null

            val createdResult = runCatching {
                Log.i(LOG_TAG, "Creating WhisperContext from assets/$assetPath")
                WhisperContext.createContextFromAsset(context.assets, assetPath)
            }.onFailure { e ->
                Log.e(LOG_TAG, "Failed to create WhisperContext from assets/$assetPath", e)
            }

            if (createdResult.isFailure) {
                return@withLock Result.failure(createdResult.exceptionOrNull()!!)
            }

            val created = createdResult.getOrThrow()

            this@WhisperEngine.context = created
            this@WhisperEngine.modelKey = key

            Log.i(LOG_TAG, "WhisperEngine initialized with asset model=$assetPath")
            Result.success(Unit)
        }
    }

    // ---------------------------------------------------------------------
    // Transcription
    // ---------------------------------------------------------------------

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
     *   trimmed result is returned as success. This allows callers to decide
     *   how to handle "no speech" or near-silence cases.
     *
     * Thread-safety:
     * - The Whisper JNI call is protected by [engineMutex] to prevent concurrent
     *   release/model swap.
     *
     * @param file Input WAV file (PCM16 or Float32). Must exist.
     * @param lang Language code ("en", "ja", "sw", or "auto").
     * @param translate If true, runs Whisper in translation-to-English mode.
     * @param printTimestamp If true, appends timestamps to each output line.
     * @param targetSampleRate Target sample rate for decoding, default 16 kHz.
     */
    suspend fun transcribeWaveFile(
        file: File,
        lang: String,
        translate: Boolean = false,
        printTimestamp: Boolean = false,
        targetSampleRate: Int = 16_000
    ): Result<String> = withContext(Dispatchers.Default) {

        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Input WAV file does not exist: ${file.path}")
            )
        }

        // Decode WAV into normalized mono float PCM outside the mutex.
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

        // Basic PCM stats for debugging (helps spot near-silence / clipping).
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

        // Protect the WhisperContext usage from concurrent release/swap.
        engineMutex.withLock {

            val ctx = context
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

                Log.d(
                    LOG_TAG,
                    "Whisper result for lang=$code: length=${trimmed.length}, " +
                            "preview=\"${trimmed.take(80)}\""
                )

                if (trimmed.isNotEmpty()) {
                    return@withLock Result.success(trimmed)
                }

                // Keep the last empty success so we can return it if all attempts are empty.
                lastEmptySuccess = trimmed
                Log.w(
                    LOG_TAG,
                    "Empty transcript for lang=$code; will try next language (if any)."
                )
            }

            // All attempts either failed or were empty.
            when {
                lastEmptySuccess != null -> Result.success(lastEmptySuccess!!)
                lastFailure != null -> Result.failure(lastFailure!!)
                else -> Result.failure(
                    IllegalStateException("Transcription produced no usable result.")
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------

    /**
     * Release the active Whisper context, if any, and reset the engine.
     *
     * This is safe to call multiple times.
     * After calling [release], you must call one of the ensureInitialized*
     * methods again before using [transcribeWaveFile].
     */
    suspend fun release() {
        engineMutex.withLock {
            val ctx = context
            if (ctx == null) {
                modelKey = null
                return
            }

            this@WhisperEngine.context = null
            val oldKey = modelKey
            this@WhisperEngine.modelKey = null

            runCatching {
                Log.i(LOG_TAG, "Releasing WhisperContext for $oldKey")
                ctx.release()
            }.onFailure { e ->
                Log.w(LOG_TAG, "Error while releasing WhisperContext", e)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    /**
     * Simple statistics container for PCM buffers.
     *
     * This is used for logging only.
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
