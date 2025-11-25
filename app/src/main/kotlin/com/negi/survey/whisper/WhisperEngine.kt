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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

private const val LOG_TAG = "WhisperEngine"

/**
 * Thin facade for integrating Whisper.cpp into the SurveyNav app.
 *
 * This object wraps [WhisperContext] and [decodeWaveFile] to offer a simple,
 * suspend-friendly API:
 *
 * - [ensureInitializedFromFile] — load a Whisper model from a local file.
 * - [ensureInitializedFromAsset] — load a Whisper model from app assets.
 * - [transcribeWaveFile] — decode a WAV file to mono PCM and run transcription.
 * - [release] — free native resources and reset the engine.
 *
 * All heavy work is dispatched onto [Dispatchers.Default]. The underlying
 * [WhisperContext] already runs JNI calls on its own single-threaded
 * dispatcher, so this facade only coordinates model switching and error
 * handling.
 */
object WhisperEngine {

    /**
     * Current active Whisper context. Access must be guarded by [initMutex].
     */
    @Volatile
    private var context: WhisperContext? = null

    /**
     * Identifier of the model that [context] was created from.
     *
     * For file-based models it is the absolute file path.
     * For asset-based models it uses a synthetic key "asset:<path>".
     */
    @Volatile
    private var modelKey: String? = null

    /**
     * Mutex to serialize initialization and model switching.
     */
    private val initMutex = Mutex()

    // ---------------------------------------------------------------------
    // Initialization
    // ---------------------------------------------------------------------

    /**
     * Ensure that a Whisper model is loaded from [modelFile].
     *
     * If the engine is already initialized with the same file path, this
     * function returns immediately with [Result.success]. If a different
     * model is active, the old context is released before creating a new one.
     *
     * @param context Android [Context], kept for future extension.
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

        initMutex.withLock {
            val key = modelFile.absolutePath

            // Fast path: already initialized with the same model file.
            val current = this@WhisperEngine.context
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

            // Create a new context from model file.
            val created = runCatching {
                Log.i(LOG_TAG, "Creating WhisperContext from file=$key")
                WhisperContext.createContextFromFile(key)
            }.onFailure { e ->
                Log.e(LOG_TAG, "Failed to create WhisperContext from $key", e)
            }.getOrElse { error ->
                return@withLock Result.failure(error)
            }

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
     * This behaves similarly to [ensureInitializedFromFile], but uses
     * [WhisperContext.createContextFromAsset] under the hood and stores
     * a synthetic "asset:<path>" key in [modelKey].
     */
    suspend fun ensureInitializedFromAsset(
        context: Context,
        assetPath: String
    ): Result<Unit> = withContext(Dispatchers.Default) {
        initMutex.withLock {
            val key = "asset:$assetPath"

            // Fast path: already initialized with the same asset model.
            val current = this@WhisperEngine.context
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

            // Create a new context from assets.
            val created = runCatching {
                Log.i(LOG_TAG, "Creating WhisperContext from assets/$assetPath")
                WhisperContext.createContextFromAsset(context.assets, assetPath)
            }.onFailure { e ->
                Log.e(LOG_TAG, "Failed to create WhisperContext from assets/$assetPath", e)
            }.getOrElse { error ->
                return@withLock Result.failure(error)
            }

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
     * This function:
     * 1. Decodes the WAV file to a mono float buffer via [decodeWaveFile].
     * 2. Logs basic PCM statistics (size, min, max, RMS) for debugging.
     * 3. Calls [WhisperContext.transcribeData] on one or more languages:
     *    - If [lang] == "auto", it tries "auto" first, then "en", "ja", "sw".
     *    - Otherwise, it only tries the specified [lang].
     *
     * The first non-empty trimmed transcript is returned as success.
     * If all attempts produce empty text but no exception, the last attempt's
     * (possibly empty) text is returned as success so that callers can decide
     * how to handle the "no speech" case.
     *
     * @param file Input WAV file (PCM16 or Float32). Must exist.
     * @param lang Language code ("en", "ja", "sw", or "auto" for auto-detect).
     * @param translate If true, runs Whisper in translation-to-English mode.
     * @param printTimestamp If true, append `[t0 - t1]` to each line.
     * @param targetSampleRate Target sample rate for decoding, default 16 kHz.
     */
    suspend fun transcribeWaveFile(
        file: File,
        lang: String,
        translate: Boolean = false,
        printTimestamp: Boolean = false,
        targetSampleRate: Int = 16_000
    ): Result<String> = withContext(Dispatchers.Default) {
        val ctx = context
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "WhisperEngine is not initialized. " +
                            "Call ensureInitializedFromFile() or ensureInitializedFromAsset() first."
                )
            )

        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Input WAV file does not exist: ${file.path}")
            )
        }

        // Decode WAV into normalized mono float PCM and log statistics.
        val pcm: FloatArray = runCatching {
            decodeWaveFile(
                file = file,
                targetSampleRate = targetSampleRate
            )
        }.onFailure { e ->
            Log.e(LOG_TAG, "Failed to decode WAV file=${file.path}", e)
        }.getOrElse { error ->
            return@withContext Result.failure(error)
        }

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

        // Language attempts:
        // - If lang == "auto", try "auto" first, then common fixed languages.
        // - Otherwise, use only the explicitly requested language.
        val languageAttempts: List<String> =
            if (lang.lowercase() == "auto") {
                listOf("auto", "en", "ja", "sw")
            } else {
                listOf(lang)
            }

        var lastResult: Result<String>? = null

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

            lastResult = result

            result.onSuccess { raw ->
                val trimmed = raw.trim()
                Log.d(
                    LOG_TAG,
                    "Whisper result for lang=$code: length=${trimmed.length}, " +
                            "preview=\"${trimmed.take(80)}\""
                )
                if (trimmed.isNotEmpty()) {
                    // First non-empty transcript → use it.
                    return@withContext Result.success(trimmed)
                } else {
                    Log.w(
                        LOG_TAG,
                        "Empty transcript for lang=$code; " +
                                "will try next language (if any)."
                    )
                }
            }

            // If result is failure, loop continues to the next language.
        }

        // All attempts failed or produced empty text.
        // Return the last result (success or failure) so callers can inspect it.
        lastResult ?: Result.failure(
            IllegalStateException("Transcription did not run: no language attempts?")
        )
    }

    // ---------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------

    /**
     * Release the active Whisper context, if any, and reset the engine.
     *
     * This is safe to call multiple times; extra calls are ignored.
     * After calling [release], you must call one of the ensureInitialized*
     * methods again before using [transcribeWaveFile].
     */
    suspend fun release() {
        initMutex.withLock {
            val ctx = context
            if (ctx == null) {
                modelKey = null
                return
            }

            this@WhisperEngine.context = null
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

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    /**
     * Simple statistics helper for PCM buffers.
     *
     * Used only for logging / debugging.
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
