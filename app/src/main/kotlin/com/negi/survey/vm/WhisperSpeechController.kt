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

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.screens.SpeechController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Simple ViewModel-based SpeechController implementation.
 *
 * This controller is responsible only for:
 * - Tracking recording state.
 * - Exposing the latest recognized text as flows.
 * - Forwarding audio to the underlying Whisper bridge (not implemented here).
 *
 * The actual Whisper.cpp integration (JNI, WAV encoding, etc.) should be
 * wired inside [startRecording] and [stopRecording], or delegated to helper
 * classes invoked from this ViewModel.
 */
class WhisperSpeechController : ViewModel(), SpeechController {

    companion object {
        private const val TAG = "WhisperSpeechController"

        /**
         * Factory for use with Compose `viewModel(factory = ...)`.
         *
         * Using an explicit factory avoids reflection-based constructor lookup
         * and guarantees that a new instance can always be created.
         */
        fun provideFactory(): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(WhisperSpeechController::class.java)) {
                        "Unknown ViewModel class $modelClass"
                    }
                    return WhisperSpeechController() as T
                }
            }
    }

    private val _isRecording = MutableStateFlow(false)
    private val _partialText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    override val isRecording: StateFlow<Boolean> = _isRecording
    override val partialText: StateFlow<String> = _partialText
    override val errorMessage: StateFlow<String?> = _error

    /**
     * Start capturing audio and begin recognition.
     *
     * Current implementation only flips flags and logs. Wire your real
     * Whisper.cpp bridge here (AudioRecord + JNI + transcription).
     */
    override fun startRecording() {
        if (_isRecording.value) return
        Log.d(TAG, "startRecording: begin")
        _error.value = null
        _partialText.value = ""
        _isRecording.value = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "startRecording: background job started")

                // TODO:
                //  - Start AudioRecord and capture PCM into a buffer.
                //  - Save to a WAV file or feed directly into Whisper.cpp JNI.
                //  - When final transcript is ready, call updatePartialText(...).
                //
                // For now, we simulate a short delay + fake transcript so that
                // the Ui pipeline can be verified end-to-end.
                /*
                delay(1500)
                updatePartialText("Dummy transcript from WhisperSpeechController")
                */

            } catch (t: Throwable) {
                Log.e(TAG, "startRecording: failed", t)
                _error.value = t.message ?: "Speech recognition failed"
                _isRecording.value = false
            }
        }
    }

    /**
     * Stop capturing audio and finalize the current utterance.
     *
     * This should:
     * - Stop AudioRecord (or your WaveRecorder).
     * - Trigger final Whisper decoding if needed.
     * - Eventually call [updatePartialText] with the final text.
     */
    override fun stopRecording() {
        if (!_isRecording.value) return
        Log.d(TAG, "stopRecording: requested")
        // TODO: stop your AudioRecord / WaveRecorder here and finalize decoding.
        _isRecording.value = false
    }

    /**
     * Update the current partial or final transcript text.
     *
     * This is expected to be invoked from the Whisper JNI callback or from
     * your WAV-file transcriber after decoding completes.
     */
    fun updatePartialText(text: String) {
        Log.d(TAG, "updatePartialText: \"$text\"")
        _partialText.value = text
    }

    /**
     * Convenience toggle used by the UI microphone button.
     *
     * Delegates to [startRecording] and [stopRecording] based on the current
     * [isRecording] value.
     */
    override fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }
}
