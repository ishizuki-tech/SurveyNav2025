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

package com.negi.survey.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.survey.screens.SpeechController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Simple ViewModel-based SpeechController implementation.
 *
 * Wire this class to your actual Whisper.cpp JNI bridge:
 * - Allocate / reuse a Whisper context.
 * - Capture microphone PCM.
 * - Run transcription and update [partialText].
 */
class WhisperSpeechController(
    // Inject your own dependencies here, e.g. appContext, modelPath, whisperContext, etc.
) : ViewModel(), SpeechController {

    private val _isRecording = MutableStateFlow(false)
    private val _partialText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    override val isRecording: StateFlow<Boolean> = _isRecording
    override val partialText: StateFlow<String> = _partialText
    override val errorMessage: StateFlow<String?> = _error

    override fun startRecording() {
        if (_isRecording.value) return
        _error.value = null
        _partialText.value = ""
        _isRecording.value = true

        // Example: start microphone + Whisper on a background dispatcher.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // TODO: start AudioRecord loop + Whisper streaming here
                // and call updatePartialText(...) with interim results.
            } catch (t: Throwable) {
                _error.value = t.message ?: "Speech recognition failed"
                _isRecording.value = false
            }
        }
    }

    override fun stopRecording() {
        if (!_isRecording.value) return
        // TODO: stop AudioRecord + finalize Whisper transcription.
        _isRecording.value = false
    }

    /**
     * Call this from your Whisper callback whenever new text is available.
     */
    fun updatePartialText(text: String) {
        _partialText.value = text
    }
}
