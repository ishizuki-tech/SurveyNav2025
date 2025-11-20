/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AsrViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.negi.survey.whisper.WhisperEngine
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * High-level UI state for the ASR flow.
 *
 * The state is intentionally simple and focused on the parts that matter
 * for survey integration:
 *
 * - [Idle]          : nothing happening.
 * - [Transcribing]  : Whisper is running on a WAV file.
 * - [Done]          : transcription finished successfully.
 * - [Error]         : something went wrong.
 *
 * Recording itself (microphone capture and WAV creation) is not modeled
 * here yet; that is left to a separate layer or future extension.
 */
sealed class AsrUiState {

    /**
     * No work is in progress.
     */
    data object Idle : AsrUiState()

    /**
     * Whisper is currently transcribing an audio file.
     */
    data object Transcribing : AsrUiState()

    /**
     * Transcription finished successfully.
     *
     * @property text Final recognized text.
     */
    data class Done(val text: String) : AsrUiState()

    /**
     * Transcription failed with an error.
     *
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : AsrUiState()
}

/**
 * ViewModel that wires the WhisperEngine into a survey-friendly ASR API.
 *
 * Responsibilities:
 * - Hold a reference to the on-device Whisper model file.
 * - Ensure WhisperEngine is initialized before first use.
 * - Execute transcription jobs and expose [AsrUiState] to the UI.
 *
 * This ViewModel does not perform microphone recording. Instead, it
 * expects callers to provide a ready-to-use WAV file and focuses on
 * orchestration around WhisperEngine.
 */
class AsrViewModel(
    app: Application,
    private val whisperModelFile: File
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<AsrUiState>(AsrUiState.Idle)

    /**
     * Publicly observable ASR state.
     *
     * Typical UI pattern:
     * - Show mic button when [AsrUiState.Idle].
     * - Show progress indicator when [AsrUiState.Transcribing].
     * - On [AsrUiState.Done], inject [Done.text] into the answer field and
     *   then call [reset] to go back to [AsrUiState.Idle].
     */
    val state: StateFlow<AsrUiState> = _state

    /**
     * Last transcription job, if any.
     *
     * Used to avoid launching overlapping transcriptions and to allow
     * future cancellation logic.
     */
    private var activeJob: Job? = null

    /**
     * Reset the ASR state back to [AsrUiState.Idle].
     *
     * This does not cancel any running transcription job; it is purely
     * a UI-level reset and should be called after the UI has consumed
     * a [AsrUiState.Done] or [AsrUiState.Error] value.
     */
    fun reset() {
        _state.value = AsrUiState.Idle
    }

    /**
     * Request a transcription of the given [wavFile].
     *
     * Behavior:
     * - If another transcription is already running, the call is ignored.
     * - Ensures WhisperEngine is initialized with [whisperModelFile].
     * - On success, emits [AsrUiState.Done] with the recognized text.
     * - On failure, emits [AsrUiState.Error] with a human-readable message.
     *
     * @param wavFile Input WAV file to be transcribed.
     * @param lang Language code passed to Whisper ("en", "ja", "sw", or "auto").
     * @param translate Whether to enable Whisper's translate-to-English mode.
     * @param printTimestamp Whether to include timestamps in the raw output.
     */
    fun transcribeWavFile(
        wavFile: File,
        lang: String,
        translate: Boolean = false,
        printTimestamp: Boolean = false
    ) {
        // Avoid launching overlapping transcription jobs.
        val currentJob = activeJob
        if (currentJob != null && currentJob.isActive) {
            return
        }

        activeJob = viewModelScope.launch {
            _state.value = AsrUiState.Transcribing

            // 1) Ensure Whisper model is ready.
            val initResult = WhisperEngine.ensureInitializedFromFile(
                context = getApplication(),
                modelFile = whisperModelFile
            )

            if (initResult.isFailure) {
                val msg = initResult.exceptionOrNull()?.message
                    ?: "Failed to initialize Whisper model"
                _state.value = AsrUiState.Error(msg)
                return@launch
            }

            // 2) Run transcription for the given WAV file.
            val asrResult = WhisperEngine.transcribeWaveFile(
                file = wavFile,
                lang = lang,
                translate = translate,
                printTimestamp = printTimestamp
            )

            _state.value = asrResult.fold(
                onSuccess = { text ->
                    AsrUiState.Done(text)
                },
                onFailure = { error ->
                    AsrUiState.Error(error.message ?: "Whisper transcription failed")
                }
            )
        }
    }

    /**
     * Optional: cancel the currently running transcription job, if any.
     *
     * The UI can expose this as a "Cancel" button while in the
     * [AsrUiState.Transcribing] state.
     */
    fun cancelTranscription() {
        val job = activeJob
        if (job != null && job.isActive) {
            job.cancel()
        }
        activeJob = null
        _state.value = AsrUiState.Error("Transcription canceled")
    }
}
