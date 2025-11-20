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
 * - Allocate or reuse a Whisper context.
 * - Capture microphone PCM.
 * - Run transcription and update [partialText].
 */
class WhisperSpeechController : ViewModel(), SpeechController {

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
                // TODO:
                //  - Start AudioRecord capture.
                //  - Feed PCM chunks into Whisper.cpp.
                //  - Call updatePartialText(...) with interim results.
                //  - Update [_partialText] with the final transcript.
            } catch (t: Throwable) {
                _error.value = t.message ?: "Speech recognition failed"
                _isRecording.value = false
            }
        }
    }

    override fun stopRecording() {
        if (!_isRecording.value) return
        // TODO:
        //  - Stop AudioRecord.
        //  - Optionally finalize Whisper.cpp decoding if needed.
        _isRecording.value = false
    }

    /**
     * Updates the current partial or final transcript text.
     *
     * Call this from the Whisper JNI callback when new text is available.
     */
    fun updatePartialText(text: String) {
        _partialText.value = text
    }
}
