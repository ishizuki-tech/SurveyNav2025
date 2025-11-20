/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SpeechAnswerField.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * Simple abstraction for a Whisper-backed speech controller.
 *
 * Implement this with your existing Whisper.cpp wrapper.
 *
 * Contract:
 * - [isRecording]: true while microphone capture + transcription is active.
 * - [partialText]: last recognized text fragment (can be empty while waiting).
 * - [errorMessage]: last error, cleared when a new session starts.
 * - [startRecording]: begin a new speech session.
 * - [stopRecording]: stop and finalize the current session.
 */
interface SpeechController {
    val isRecording: StateFlow<Boolean>
    val partialText: StateFlow<String>
    val errorMessage: StateFlow<String?>

    fun startRecording()
    fun stopRecording()
}

/**
 * Text answer field augmented with a speech-to-text microphone button.
 *
 * Ownership:
 * - The actual answer string is owned by the caller via [text] and
 *   [onTextChange]. This composable only mirrors Whisper output into
 *   that state in a controlled way.
 *
 * Behavior:
 * - Tapping the mic icon toggles recording via [controller].
 * - While recording, recognized text is surfaced through [partialText].
 * - When recording stops, the last non-empty partial text is written
 *   into [onTextChange].
 */
@Composable
fun SpeechAnswerField(
    text: String,
    onTextChange: (String) -> Unit,
    controller: SpeechController,
    modifier: Modifier = Modifier
) {
    val isRecording by controller.isRecording.collectAsState()
    val partial by controller.partialText.collectAsState()
    val error by controller.errorMessage.collectAsState()

    // When recording ends and there is some partial text, commit it
    // into the main answer field exactly once.
    LaunchedEffect(isRecording) {
        if (!isRecording && partial.isNotBlank()) {
            onTextChange(partial)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Simple text field; you can swap this for an OutlinedTextField
            // if you already use Material text fields elsewhere.
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = false
            )

            IconButton(
                onClick = {
                    if (isRecording) {
                        controller.stopRecording()
                    } else {
                        controller.startRecording()
                    }
                }
            ) {
                Crossfade(targetState = isRecording, label = "mic-toggle") { rec ->
                    if (rec) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop recording"
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Start recording"
                        )
                    }
                }
            }
        }
    }

    // Optional status / error text under the field.
    if (isRecording || error != null) {
        Spacer(Modifier.height(4.dp))
        val statusText = when {
            error != null -> error
            isRecording -> "Listening…"
            else -> null
        }
        if (statusText != null) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
