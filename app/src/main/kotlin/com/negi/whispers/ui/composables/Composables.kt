/*
 * ================================================================
 *  IshizukiTech LLC — Whisper Integration Framework
 *  ------------------------------------------------
 *  File: WhisperUIComposables.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * ================================================================
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the “Software”), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 * ================================================================
 */

/**
 * Whisper UI Composables
 * -----------------------
 * Defines all user interface components used in the Whisper App.
 *
 * ## Components
 * - Styled buttons and configuration dialogs
 * - Language/model dropdown selectors
 * - Recording list with animations and swipe-to-delete
 * - Top bar with app info, language, and model display
 *
 * All composables follow Kotlin’s official documentation style (KDoc),
 * ensuring readability, maintainability, and visual consistency.
 */

package com.negi.whispers.ui.composables

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.whispers.ui.main.MainScreenViewModel
import com.negi.whispers.ui.main.MyRecord
import kotlinx.coroutines.launch
import java.io.File

// ============================================================================
// Styled UI Elements
// ============================================================================

/**
 * Displays a rounded, elevated button using Material3 primary color by default.
 *
 * @param text Label text displayed on the button.
 * @param onClick Callback triggered when the button is tapped.
 * @param modifier Optional [Modifier] for layout customization.
 * @param enabled Enables or disables button interactivity.
 * @param color Custom background color (defaults to [MaterialTheme.colorScheme.primary]).
 */
@Composable
fun StyledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        elevation = ButtonDefaults.buttonElevation(6.dp),
        modifier = modifier
    ) {
        Text(text)
    }
}

// ============================================================================
// Settings Dialog and Dropdown Selectors
// ============================================================================

/**
 * Renders the settings button and dialog for language, model, and translation options.
 *
 * @param viewModel The [MainScreenViewModel] managing configuration state.
 */
@Composable
fun ConfigButtonWithDialog(viewModel: MainScreenViewModel) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select language")
                    val languages = listOf("en" to "English", "ja" to "Japanese", "sw" to "Swahili")
                    DropdownSelector(
                        currentValue = viewModel.selectedLanguage,
                        options = languages,
                        onSelect = { viewModel.updateSelectedLanguage(it) }
                    )

                    Spacer(Modifier.height(8.dp))

                    Text("Select model")
                    val models = listOf(
                        "ggml-tiny-q5_1.bin",
                        "ggml-base-q5_1.bin",
                        "ggml-small-q5_1.bin",
                        "ggml-model-q4_0.bin"
                    )
                    DropdownSelector(
                        currentValue = viewModel.selectedModel,
                        options = models.map { it to it },
                        onSelect = { viewModel.updateSelectedModel(it) }
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewModel.translateToEnglish,
                            onCheckedChange = { viewModel.updateTranslate(it) }
                        )
                        Text("Translate to English")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * Generic dropdown selector used inside dialogs.
 *
 * @param currentValue The currently selected key.
 * @param options List of key–label pairs to display.
 * @param onSelect Invoked when a new value is selected.
 */
@Composable
private fun DropdownSelector(
    currentValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == currentValue }?.second ?: "Select"

    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selectedLabel)
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}

// ============================================================================
// Recording List
// ============================================================================

/**
 * Displays a scrollable, animated list of recordings.
 *
 * Includes:
 * - Swipe-to-delete gesture
 * - Tap-to-select
 * - Double-tap-to-retranscribe
 *
 * @param viewModel Reference to [MainScreenViewModel].
 * @param records List of recorded items.
 * @param listState Scroll state of the [LazyColumn].
 * @param selectedIndex Index of the currently selected recording.
 * @param canTranscribe Indicates if transcription is available.
 * @param onSelect Called when a record is tapped.
 * @param onCardClick Called when a record is double-tapped (re-transcribe).
 * @param onDeleteRequest Invoked when a swipe-to-delete occurs.
 * @param modifier Optional [Modifier] for layout control.
 */
@Composable
fun RecordingList(
    viewModel: MainScreenViewModel,
    records: List<MyRecord>,
    listState: LazyListState,
    selectedIndex: Int,
    canTranscribe: Boolean,
    onSelect: (Int) -> Unit,
    onCardClick: (String, Int) -> Unit,
    onDeleteRequest: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Auto-scroll to newest recording
    LaunchedEffect(records.size) {
        try {
            if (records.isNotEmpty()) listState.animateScrollToItem(records.lastIndex)
        } catch (e: Exception) {
            Log.w("UI", "Scroll error: ${e.message}")
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "globalPulse")
    val pulsingScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        itemsIndexed(records, key = { i, r -> r.absolutePath.ifBlank { "rec_$i" } }) { index, record ->
            val swipeState = rememberSwipeToDismissBoxState()
            val scope = rememberCoroutineScope()

            // Swipe-to-delete
            LaunchedEffect(swipeState.targetValue) {
                if (swipeState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                    onDeleteRequest(index)
                    swipeState.snapTo(SwipeToDismissBoxValue.Settled)
                }
            }

            val isSelected = index == selectedIndex
            val animatedCorner by animateDpAsState(
                targetValue = if (isSelected && !canTranscribe) 48.dp else 16.dp,
                animationSpec = tween(400),
                label = "cornerAnim"
            )
            val effectiveScale = if (isSelected && !canTranscribe) pulsingScale else 1f

            val lastTapTime = remember(index) { mutableStateOf(0L) }

            val tapModifier = if (canTranscribe) {
                Modifier.pointerInput(index) {
                    detectTapGestures(
                        onTap = { onSelect(index) },
                        onDoubleTap = {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime.value < 800) return@detectTapGestures
                            lastTapTime.value = now

                            scope.launch {
                                viewModel.stopPlayback()
                                val file = File(record.absolutePath)
                                if (file.exists()) viewModel.reTranscribe(index)
                                else onCardClick(record.absolutePath, index)
                            }
                        }
                    )
                }
            } else Modifier

            SwipeToDismissBox(
                state = swipeState,
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = false,
                backgroundContent = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(start = 20.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(scaleX = effectiveScale, scaleY = effectiveScale)
                        .then(tapModifier),
                    shape = RoundedCornerShape(animatedCorner),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            canTranscribe -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Text(
                        record.logs,
                        modifier = Modifier.padding(16.dp),
                        softWrap = true
                    )
                }
            }
        }
    }
}

// ============================================================================
// Dialogs and Top Bar
// ============================================================================

/** Confirmation dialog shown when deleting a recording. */
@Composable
fun ConfirmDeleteDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete Recording") },
        text = { Text("Are you sure you want to delete this recording?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

/**
 * Displays the current language and model as a compact label.
 */
@Composable
private fun LanguageLabel(languageCode: String, selectedModel: String) {
    val code = languageCode.ifBlank { "—" }
    val model = selectedModel.ifBlank { "—" }
    Text(
        text = "[$code · $model]",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Displays the top application bar with title, info, and settings icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(viewModel: MainScreenViewModel) {
    var showAboutDialog by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Mic", tint = Color(0xFF2196F3))
                Text(
                    text = "Whisper App",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.Black
                )
                LanguageLabel(viewModel.selectedLanguage, viewModel.selectedModel)
            }
        },
        actions = {
            IconButton(onClick = { showAboutDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = "Info")
            }
            ConfigButtonWithDialog(viewModel)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFFFFF176),
            titleContentColor = Color.Black
        )
    )

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About this App") },
            text = {
                Column {
                    Text("Whisper App v0.0.1")
                    Spacer(Modifier.height(8.dp))
                    Text("Offline speech recognition powered by Whisper.cpp.")
                    Spacer(Modifier.height(4.dp))
                    Text("Supported languages: English / Japanese / Swahili")
                    Spacer(Modifier.height(8.dp))
                    Text("Developer: Shu Ishizuki")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Close") }
            }
        )
    }
}
