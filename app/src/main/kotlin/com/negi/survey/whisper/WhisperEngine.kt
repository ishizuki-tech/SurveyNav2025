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

private const val LOG_TAG = "WhisperEngine"

/**
 * Thin facade for integrating Whisper.cpp into the SurveyNav app.
 *
 * This object wraps [WhisperContext] and [decodeWaveFile] to offer a simple,
 * suspend-friendly API:
 *
 * - [ensureInitializedFromFile] — load a Whisper model from a local file.
 * - [transcribeWaveFile] — decode a WAV file to mono PCM and run transcription.
 * - [release] — free native resources and close the internal dispatcher.
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
     * Absolute path of the model file that [context] was created from.
     */
    @Volatile
    private var modelPath: String? = null

    /**
     * Mutex to serialize initialization and model switching.
     */
    private val initMutex = Mutex()

    /**
     * Ensure that a Whisper model is loaded from [modelFile].
     *
     * If the engine is already initialized with the same file path, this
     * function returns immediately with [Result.success]. If a different
     * model is active, the old context is released before creating a new one.
     *
     * @param context Android [Context], kept for future extension (e.g. if
     *   we later want to support asset-backed models). Currently unused.
     * @param modelFile Local Whisper model file (GGML/GGUF). Must exist.
     */
    suspend fun ensureInitializedFromFile(
        context: Context,
        modelFile: File
    ): Result<Unit> = withContext(Dispatchers.Default) {
        if (!modelFile.exists() || !modelFile.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Whisper model file does not exist: ${modelFile.path}")
            )
        }

        initMutex.withLock {
            val absPath = modelFile.absolutePath

            // Fast path: already initialized with the same model.
            val current = this@WhisperEngine.context
            if (current != null && modelPath == absPath) {
                Log.d(LOG_TAG, "WhisperEngine already initialized for $absPath")
                return@withLock Result.success(Unit)
            }

            // Release any previous context before switching models.
            if (current != null) {
                runCatching {
                    Log.i(LOG_TAG, "Releasing previous WhisperContext for $modelPath")
                    current.release()
                }.onFailure { e ->
                    Log.w(LOG_TAG, "Error while releasing previous WhisperContext", e)
                }
            }

            this@WhisperEngine.context = null
            this@WhisperEngine.modelPath = null

            // Create a new context from model file.
            val created = runCatching {
                WhisperContext.createContextFromFile(absPath)
            }.onFailure { e ->
                Log.e(LOG_TAG, "Failed to create WhisperContext from $absPath", e)
            }.getOrElse { error ->
                return@withLock Result.failure(error)
            }

            this@WhisperEngine.context = created
            this@WhisperEngine.modelPath = absPath

            Log.i(LOG_TAG, "WhisperEngine initialized with model=$absPath")
            Result.success(Unit)
        }
    }

    /**
     * Transcribe the given WAV [file] and return plain-text output.
     *
     * This function:
     * 1. Decodes the WAV file to a mono float buffer via [decodeWaveFile].
     * 2. Calls [WhisperContext.transcribeData] on the active context.
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
                IllegalStateException("WhisperEngine is not initialized. Call ensureInitializedFromFile() first.")
            )

        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Input WAV file does not exist: ${file.path}")
            )
        }

        runCatching {
            // 1) Decode WAV into normalized mono float PCM.
            val pcm: FloatArray = decodeWaveFile(
                file = file,
                targetSampleRate = targetSampleRate
            )

            if (pcm.isEmpty()) {
                error("Decoded PCM buffer is empty for file: ${file.name}")
            }

            // 2) Run Whisper transcription on the dedicated JNI thread.
            ctx.transcribeData(
                data = pcm,
                lang = lang,
                translate = translate,
                printTimestamp = printTimestamp
            )
        }.onFailure { e ->
            Log.e(LOG_TAG, "Whisper transcription failed for file=${file.path}", e)
        }
    }

    /**
     * Release the active Whisper context, if any, and reset the engine.
     *
     * This is safe to call multiple times; extra calls are ignored.
     * After calling [release], you must call [ensureInitializedFromFile]
     * again before using [transcribeWaveFile].
     */
    suspend fun release() {
        initMutex.withLock {
            val ctx = context
            if (ctx == null) {
                modelPath = null
                return
            }

            this@WhisperEngine.context = null
            val oldPath = modelPath
            modelPath = null

            runCatching {
                Log.i(LOG_TAG, "Releasing WhisperContext for $oldPath")
                ctx.release()
            }.onFailure { e ->
                Log.w(LOG_TAG, "Error while releasing WhisperContext", e)
            }
        }
    }
}
