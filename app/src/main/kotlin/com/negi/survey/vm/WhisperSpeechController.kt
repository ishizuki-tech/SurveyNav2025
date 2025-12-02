/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: WhisperSpeechController.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.screens.SpeechController
import com.negi.survey.utils.ExportUtils
import com.negi.survey.whisper.WhisperEngine
import com.negi.whispers.recorder.Recorder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel-based [SpeechController] implementation backed by Whisper.cpp.
 *
 * Responsibilities:
 * - Manage recording lifecycle using [Recorder].
 * - Ensure Whisper model initialization via [WhisperEngine].
 * - Run transcription and publish the final text to [partialText].
 * - Export recorded WAV files to a persistent "exports" folder
 *   using [ExportUtils.exportRecordedVoice] so another layer
 *   (e.g., GitHubUploadWorker) can upload them later.
 *
 * UI contract:
 * - [isRecording] is true while audio capture is active.
 * - [isTranscribing] is true while transcription is running.
 * - When recording stops and transcription succeeds, [partialText] is updated
 *   with the final recognized text (AiScreen already commits it to answers).
 */
class WhisperSpeechController(
    private val appContext: Context,
    private val assetModelPath: String = DEFAULT_ASSET_MODEL,
    private val languageCode: String = DEFAULT_LANGUAGE
) : ViewModel(), SpeechController {

    companion object {
        private const val TAG = "WhisperSpeechController"

        /**
         * Default language for Whisper.
         *
         * Valid values:
         * - "auto" : auto-detect language
         * - "en"   : English
         * - "ja"   : Japanese
         * - "sw"   : Swahili
         */
        private const val DEFAULT_LANGUAGE = "auto"

        /**
         * Default model path inside assets.
         *
         * Place your model at:
         *   app/src/main/assets/models/ggml-model-q4_0.bin
         * and keep this as "models/ggml-model-q4_0.bin".
         */
        private const val DEFAULT_ASSET_MODEL = "models/ggml-model-q4_0.bin"

        /**
         * Factory for use with Compose `viewModel(factory = ...)`.
         *
         * Example usage inside a @Composable:
         *
         * ```kotlin
         * val ctx = LocalContext.current.applicationContext
         * val speechVm: WhisperSpeechController = viewModel(
         *     factory = WhisperSpeechController.provideFactory(
         *         appContext = ctx,
         *         assetModelPath = "models/ggml-model-q4_0.bin",
         *         languageCode = "auto"
         *     )
         * )
         * ```
         */
        fun provideFactory(
            appContext: Context,
            assetModelPath: String = DEFAULT_ASSET_MODEL,
            languageCode: String = DEFAULT_LANGUAGE
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(WhisperSpeechController::class.java)) {
                        "Unknown ViewModel class $modelClass"
                    }
                    return WhisperSpeechController(
                        appContext = appContext.applicationContext,
                        assetModelPath = assetModelPath,
                        languageCode = languageCode
                    ) as T
                }
            }
    }

    // ---------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------

    /**
     * Dedicated WAV recorder implementation.
     *
     * Errors are forwarded into [errorMessage].
     */
    private val recorder: Recorder = Recorder(appContext) { e ->
        Log.e(TAG, "Recorder error", e)
        _error.value = e.message ?: "Recording error"
        _isRecording.value = false
    }

    /**
     * Last output WAV file produced by [recorder].
     *
     * This file lives under cache/whisper_rec and is treated as temporary.
     */
    private var outputFile: File? = null

    /**
     * Background job used for model init / recording / transcription.
     *
     * A single [workerJob] is reused; it is cancelled before starting a new
     * operation (start or stop).
     */
    private var workerJob: Job? = null

    /**
     * Optional context for naming exported voice files.
     *
     * When set, exported file names look like:
     *   voice_<surveyId>_<questionId>_YYYYMMDD_HHmmss.wav
     */
    private var currentSurveyId: String? = null
    private var currentQuestionId: String? = null

    // ---------------------------------------------------------------------
    // State exposed to the UI
    // ---------------------------------------------------------------------

    private val _isRecording = MutableStateFlow(false)
    private val _isTranscribing = MutableStateFlow(false)
    private val _partialText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    override val isRecording: StateFlow<Boolean> = _isRecording
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing
    override val partialText: StateFlow<String> = _partialText
    override val errorMessage: StateFlow<String?> = _error

    // ---------------------------------------------------------------------
    // Optional context setter
    // ---------------------------------------------------------------------

    /**
     * Update the survey/question context used when exporting voice.
     *
     * This is used only for file naming; can be null.
     */
    fun updateContext(
        surveyId: String?,
        questionId: String?
    ) {
        currentSurveyId = surveyId
        currentQuestionId = questionId
        Log.d(TAG, "updateContext: surveyId=$surveyId, questionId=$questionId")
    }

    // ---------------------------------------------------------------------
    // Recording control
    // ---------------------------------------------------------------------

    /**
     * Start capturing audio and begin recognition.
     *
     * Flow:
     * 1. Ensure Whisper model is initialized from assets.
     * 2. Create a temporary WAV file under cache/whisper_rec.
     * 3. Start [Recorder] to write audio into that file.
     */
    override fun startRecording() {
        if (_isRecording.value || _isTranscribing.value) {
            Log.d(TAG, "startRecording: busy (recording or transcribing), ignoring")
            return
        }

        Log.d(TAG, "startRecording: begin")
        _error.value = null
        _partialText.value = ""
        _isRecording.value = true

        // Cancel any previous worker (e.g., pending transcription).
        workerJob?.cancel()

        workerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1) Ensure Whisper model is loaded from assets.
                ensureModelInitializedFromAssets()

                // 2) Prepare output WAV file.
                val dir = File(appContext.cacheDir, "whisper_rec")
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IllegalStateException("Failed to create cache dir: ${dir.path}")
                }

                val wav = File.createTempFile("survey_input_", ".wav", dir)
                outputFile = wav

                Log.d(TAG, "startRecording: recorder.startRecording -> ${wav.path}")
                recorder.startRecording(
                    output = wav,
                    rates = intArrayOf(16_000, 48_000, 44_100)
                )
            } catch (t: Throwable) {
                Log.e(TAG, "startRecording: failed", t)
                _error.value = t.message ?: "Speech recognition start failed"
                _isRecording.value = false
                outputFile = null
            }
        }
    }

    /**
     * Stop capturing audio and finalize the current utterance.
     *
     * Flow:
     * 1. Stop [Recorder] and finalize the WAV header.
     * 2. Validate that the file exists and is not empty.
     * 3. Export WAV to a persistent "exports" folder for upload.
     * 4. Pass WAV file to [WhisperEngine.transcribeWaveFile].
     * 5. Publish the recognized text via [updatePartialText].
     *
     * The temporary cache WAV is deleted after export and transcription
     * to avoid unbounded cache growth.
     */
    override fun stopRecording() {
        if (!_isRecording.value) {
            Log.d(TAG, "stopRecording: not recording, ignoring")
            return
        }

        Log.d(TAG, "stopRecording: requested")
        _isRecording.value = false

        // Cancel any previous worker just in case.
        workerJob?.cancel()

        workerJob = viewModelScope.launch(Dispatchers.IO) {
            var localWav: File? = null

            try {
                // 1) Finalize WAV file.
                Log.d(TAG, "stopRecording: awaiting recorder.stopRecording()")
                recorder.stopRecording()

                val wav = outputFile
                outputFile = null
                localWav = wav

                if (wav == null || !wav.exists()) {
                    Log.w(TAG, "stopRecording: WAV file missing")
                    _error.value = "Recording file not found"
                    return@launch
                }
                if (wav.length() <= 44L) {
                    Log.w(TAG, "stopRecording: WAV too short or silent (${wav.length()} bytes)")
                    _error.value = "Recording too short or empty"
                    return@launch
                }

                val frames = (wav.length() - 44L) / 2L    // 16-bit mono, 2 bytes/frame
                val msApprox = frames * 1000.0 / 16_000.0

                Log.d(
                    TAG,
                    "stopRecording: ready to export + transcribe ${wav.path} " +
                            "(bytes=${wav.length()}, frames=$frames, ~${"%.1f".format(msApprox)} ms)"
                )

                // 2) Export WAV for later upload (e.g., SurveyExports).
                exportRecordedVoice(wav)

                // 3) Run Whisper transcription.
                _isTranscribing.value = true
                val result = WhisperEngine.transcribeWaveFile(
                    file = wav,
                    lang = languageCode,
                    translate = false,
                    printTimestamp = false,
                    targetSampleRate = 16_000
                )

                // 4) Handle result.
                result
                    .onSuccess { text ->
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) {
                            Log.w(
                                TAG,
                                "Transcription produced empty text. " +
                                        "Possible causes: silence, very low volume, or segments=0."
                            )
                        } else {
                            Log.d(TAG, "Transcription success: ${trimmed.take(80)}")
                        }
                        updatePartialText(trimmed)
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Transcription failed", e)
                        _error.value = e.message ?: "Transcription failed"
                    }
            } catch (t: Throwable) {
                Log.e(TAG, "stopRecording: failed", t)
                _error.value = t.message ?: "Speech recognition failed"
            } finally {
                _isTranscribing.value = false

                // Clean up the temporary cache WAV if it still exists.
                localWav?.let { file ->
                    if (file.exists()) {
                        runCatching {
                            if (file.delete()) {
                                Log.d(TAG, "stopRecording: deleted cache wav ${file.path}")
                            } else {
                                Log.d(TAG, "stopRecording: failed to delete cache wav ${file.path}")
                            }
                        }.onFailure { e ->
                            Log.w(TAG, "stopRecording: exception while deleting cache wav", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Convenience toggle used by the UI microphone button.
     */
    override fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Update the current partial or final transcript text.
     *
     * Typically invoked after Whisper finishes transcription.
     */
    fun updatePartialText(text: String) {
        Log.d(TAG, "updatePartialText: \"$text\"")
        _partialText.value = text
    }

    /**
     * Clear any previously reported error.
     *
     * Can be called from the UI layer when the user dismisses an error message.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Ensure Whisper model is initialized via [WhisperEngine] using assets.
     */
    private suspend fun ensureModelInitializedFromAssets() {
        val result = WhisperEngine.ensureInitializedFromAsset(
            context = appContext,
            assetPath = assetModelPath
        )
        result.onFailure { e ->
            Log.e(
                TAG,
                "ensureModelInitializedFromAssets: failed for assets/$assetModelPath",
                e
            )
            throw IllegalStateException(
                "Failed to initialize Whisper model from assets/$assetModelPath",
                e
            )
        }
    }

    /**
     * Export the recorded WAV into the shared "exports" directory
     * using [ExportUtils.exportRecordedVoice].
     *
     * Any failure is logged but considered non-fatal for transcription.
     */
    private fun exportRecordedVoice(wav: File) {
        Log.d(
            TAG,
            "exportRecordedVoice: delegating to ExportUtils " +
                    "(source=${wav.path}, surveyId=$currentSurveyId, questionId=$currentQuestionId)"
        )

        runCatching {
            val exported = ExportUtils.exportRecordedVoice(
                context = appContext,
                source = wav,
                surveyId = currentSurveyId,
                questionId = currentQuestionId
            )
            Log.d(TAG, "exportRecordedVoice: exported to ${exported.absolutePath}")
        }.onFailure { e ->
            Log.w(TAG, "exportRecordedVoice: failed, ignoring", e)
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.Main.immediate) {
            workerJob?.cancel()
            workerJob = null

            withContext(NonCancellable) {
                runCatching { WhisperEngine.release() }
                    .onFailure { e -> Log.w(TAG, "WhisperEngine.release failed", e) }

                runCatching { recorder.close() }
                    .onFailure { e -> Log.w(TAG, "Recorder.close failed", e) }
            }
        }
    }
}
