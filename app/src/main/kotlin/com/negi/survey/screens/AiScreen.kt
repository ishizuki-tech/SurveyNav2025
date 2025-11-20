/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  AI evaluation screen that renders a monotone, glass-like chat UI on
 *  top of the Survey + SLM pipeline.
 *
 *  Responsibilities:
 *   • Bind SurveyViewModel + AiViewModel to a single AI question node.
 *   • Render chat-style history with user messages and AI JSON responses.
 *   • Manage IME, focus, and auto-scroll during streaming.
 *   • Persist answers and follow-up questions back into SurveyViewModel.
 *   • Optionally accept a SpeechController to integrate speech-to-text
 *     (e.g., Whisper.cpp) into the answer composer.
 *
 *  Visual design:
 *   • Strict grayscale (no color hue) with animated neutral gradients.
 *   • Ultra-slim chat bubbles with micro tails and soft neutral rims.
 *   • Compact JSON bubble with collapsible detail and copy action.
 *
 *  Notes:
 *   • All comments use KDoc-style English descriptions.
 *   • No business logic is embedded; this screen only orchestrates VMs.
 * =====================================================================
 */

package com.negi.survey.screens

import android.annotation.SuppressLint
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.survey.slm.FollowupExtractor.extractScore
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.SurveyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.collections.ArrayDeque

/* =============================================================================
 * AI Evaluation Screen — Monotone × Glass × Chat
 * =============================================================================
 */

/**
 * Full-screen AI evaluation screen bound to a single survey node.
 *
 * This composable:
 *  - Reads the question text and persisted answer for [nodeId] from [vmSurvey].
 *  - Streams AI evaluation and renders it as chat bubbles via [vmAI].
 *  - Updates survey answer and follow-up questions back into [vmSurvey].
 *  - Manages IME focus, background tap-to-dismiss, and auto-scrolling.
 *  - Optionally integrates a [SpeechController] to allow microphone-based
 *    answer input.
 *
 * The screen does not perform any AI logic itself; all evaluation is delegated
 * to [AiViewModel.evaluateAsync].
 *
 * @param nodeId Graph node identifier for the current AI question.
 * @param vmSurvey Survey-level ViewModel providing questions and answers.
 * @param vmAI AI-specific ViewModel for streaming and chat state.
 * @param onNext Callback invoked when the user presses the "Next" button.
 * @param onBack Callback invoked when the user presses the "Back" button.
 * @param speechController Optional speech controller backing the composer mic.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSerializationApi::class)
@Composable
fun AiScreen(
    nodeId: String,
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
    speechController: SpeechController? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    // Survey state (question text for this node).
    val question by remember(vmSurvey, nodeId) {
        vmSurvey.questions.map { it[nodeId].orEmpty() }
    }.collectAsState(initial = vmSurvey.getQuestion(nodeId))

    // AI state.
    val loading by vmAI.loading.collectAsState()
    val stream by vmAI.stream.collectAsState()
    val raw by vmAI.raw.collectAsState()
    val error by vmAI.error.collectAsState()
    val followup by vmAI.followupQuestion.collectAsState()

    // Chat history for this node.
    val chat by remember(nodeId) { vmAI.chatFlow(nodeId) }.collectAsState()

    // Local UI state.
    var composer by remember(nodeId) { mutableStateOf(vmSurvey.getAnswer(nodeId)) }
    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()

    // Optional speech controller derived state.
    val (speechRecording, speechPartial, speechError) = if (speechController != null) {
        val isRec by speechController.isRecording.collectAsState()
        val partial by speechController.partialText.collectAsState()
        val err by speechController.errorMessage.collectAsState()
        Triple(isRec, partial, err)
    } else {
        Triple(false, "", null)
    }

    val speechStatusText: String? = when {
        speechError != null -> speechError
        speechController != null && speechRecording -> "Listening…"
        else -> null
    }
    val speechStatusIsError = speechError != null

    // Seed the first question and focus composer (IME stays open).
    LaunchedEffect(nodeId, question) {
        vmAI.chatEnsureSeedQuestion(nodeId, question)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // Keep composer text when returning to this screen.
    LaunchedEffect(nodeId) {
        composer = vmSurvey.getAnswer(nodeId)
    }

    // Errors → snackbars.
    LaunchedEffect(error) {
        error?.let { snack.showSnackbar(it) }
    }

    // Maintain/update typing bubble during streaming.
    LaunchedEffect(loading, stream) {
        if (loading) {
            val txt = stream.ifBlank { "…" }
            vmAI.chatUpsertTyping(
                nodeId,
                AiViewModel.ChatMsgVm(
                    id = "typing-$nodeId",
                    sender = AiViewModel.ChatSender.AI,
                    text = txt,
                    isTyping = true
                )
            )
        }
    }

    // Replace typing bubble with pretty JSON when the final result arrives.
    LaunchedEffect(raw, loading) {
        if (!raw.isNullOrBlank() && !loading) {
            val jsonPretty = Json {
                prettyPrint = true
                prettyPrintIndent = " "
                ignoreUnknownKeys = true
            }
            val pretty = prettyOrRaw(jsonPretty, raw!!)
            vmAI.chatReplaceTypingWith(
                nodeId,
                AiViewModel.ChatMsgVm(
                    id = "result-$nodeId-${System.nanoTime()}",
                    sender = AiViewModel.ChatSender.AI,
                    json = pretty
                )
            )
        }
    }

    // Append follow-up question when AI is idle; also persist to SurveyViewModel.
    LaunchedEffect(followup, loading) {
        val fu = followup
        if (fu != null && !loading) {
            vmAI.chatAppend(
                nodeId,
                AiViewModel.ChatMsgVm(
                    id = "fu-$nodeId-${System.nanoTime()}",
                    sender = AiViewModel.ChatSender.AI,
                    text = fu
                )
            )
            vmSurvey.addFollowupQuestion(nodeId, fu)
            vmSurvey.setQuestion(fu, nodeId)
        }
    }

    // If streaming stops without a final raw result (cancel/error), clear typing bubble.
    LaunchedEffect(loading, raw) {
        if (!loading && raw.isNullOrBlank()) {
            vmAI.chatRemoveTyping(nodeId)
        }
    }

    // When speech recording ends with a non-empty transcript, commit it into the answer.
    LaunchedEffect(speechRecording) {
        if (!speechRecording && speechPartial.isNotBlank() && speechController != null) {
            composer = speechPartial
            vmSurvey.setAnswer(speechPartial, nodeId)
        }
    }

    // Auto-scroll to bottom when chat size changes.
    LaunchedEffect(chat.size) {
        delay(16)
        scroll.animateScrollTo(scroll.maxValue)
    }

    // Keep view pinned to the bottom while streaming grows.
    LaunchedEffect(stream) {
        if (loading) {
            delay(24)
            scroll.scrollTo(scroll.maxValue)
        }
    }

    /**
     * Submit current answer (keeps IME open).
     */
    fun submit() {
        val answer = composer.trim()
        if (answer.isBlank() || loading) return

        vmSurvey.setAnswer(answer, nodeId)
        vmSurvey.answerLastFollowup(nodeId, answer)

        vmAI.chatAppend(
            nodeId,
            AiViewModel.ChatMsgVm(
                id = "u-$nodeId-${System.nanoTime()}",
                sender = AiViewModel.ChatSender.USER,
                text = answer
            )
        )

        scope.launch {
            val q = vmSurvey.getQuestion(nodeId)
            val prompt = vmSurvey.getPrompt(nodeId, q, answer)
            vmAI.evaluateAsync(prompt)
        }

        composer = ""
    }

    // Animated monotone background.
    val bgBrush = animatedMonotoneBackplate()

    Scaffold(
        topBar = { CompactTopBar(title = "Question • $nodeId") },
        snackbarHost = { SnackbarHost(snack) },
        // Root container does not follow IME; only the composer does.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.neutralEdge(alpha = 0.14f, corner = 16.dp, stroke = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.ime)
                        .padding(top = 6.dp)
                ) {
                    ChatComposer(
                        value = composer,
                        onValueChange = {
                            composer = it
                            vmSurvey.setAnswer(it, nodeId)
                        },
                        onSend = ::submit,
                        enabled = !loading,
                        focusRequester = focusRequester,
                        speechEnabled = speechController != null,
                        speechRecording = speechRecording,
                        speechStatusText = speechStatusText,
                        speechStatusIsError = speechStatusIsError,
                        onToggleSpeech = speechController?.let { sc ->
                            {
                                // Single toggle entry point; controller decides
                                // whether to start or stop recording.
                                sc.toggleRecording()
                            }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier,
                        thickness = DividerDefaults.Thickness,
                        color = DividerDefaults.color
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                vmAI.resetStates()
                                onBack()
                            }
                        ) {
                            Text("Back")
                        }

                        Spacer(Modifier.weight(1f))

                        OutlinedButton(
                            onClick = {
                                vmAI.resetStates()
                                onNext()
                            }
                        ) {
                            Text("Next")
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .background(bgBrush)
                .pointerInput(Unit) {
                    // Background tap clears focus and hides IME.
                    detectTapGestures {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                chat.forEach { m ->
                    val isAi = m.sender != AiViewModel.ChatSender.USER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                    ) {
                        if (m.json != null) {
                            JsonBubbleMono(pretty = m.json, snack = snack)
                        } else {
                            BubbleMono(
                                text = m.text.orEmpty(),
                                isAi = isAi,
                                isTyping = m.isTyping
                            )
                        }
                    }
                }
            }
        }
    }

    // Clear transient AI state when leaving this screen (chat history is kept in VM).
    DisposableEffect(Unit) {
        onDispose { vmAI.resetStates() }
    }
}

/**
 * Minimal top bar for the AI screen.
 *
 * Uses a horizontal neutral gradient and status-bar insets, and keeps the
 * layout height small to avoid competing with the content area.
 */
@Composable
private fun CompactTopBar(
    title: String,
    height: Dp = 32.dp
) {
    val cs = MaterialTheme.colorScheme
    val topBrush = Brush.horizontalGradient(
        listOf(
            cs.surface.copy(alpha = 0.96f),
            Color(0xFF1A1A1A).copy(alpha = 0.75f)
        )
    )
    Surface(color = Color.Transparent, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBrush)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(height)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                color = cs.onSurface
            )
        }
    }
}

/* ───────────────────────────── Chat bubbles ─────────────────────────────── */

/**
 * Monotone chat bubble with micro tail.
 *
 * Behavior:
 *  - AI messages use a darker neutral gradient and left-aligned tail.
 *  - User messages use a lighter neutral gradient and right-aligned tail.
 *  - When [isTyping] is true and [text] is blank, a typing indicator is shown.
 *
 * Rendering:
 *  - The fill and tail are drawn in [drawBehind] to avoid extra layers.
 *  - An ultra-soft radial highlight simulates inner depth.
 */
@Composable
private fun BubbleMono(
    text: String,
    isAi: Boolean,
    isTyping: Boolean,
    maxWidth: Dp = 520.dp
) {
    val cs = MaterialTheme.colorScheme

    val corner = 12.dp
    val padH = 10.dp
    val padV = 7.dp
    val tailW = 7f
    val tailH = 6f

    // Neutral gradients (strict grayscale). AI is darker; User is lighter.
    val stops = if (isAi) {
        listOf(Color(0xFF111111), Color(0xFF1E1E1E), Color(0xFF2A2A2A))
    } else {
        listOf(Color(0xFFEDEDED), Color(0xFFD9D9D9), Color(0xFFC8C8C8))
    }

    // Subtle motion to keep the surface visually alive.
    val t = rememberInfiniteTransition(label = "bubble-mono")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "p"
    )
    val grad = Brush.linearGradient(
        colors = stops.map { c -> lerp(c, cs.surface, 0.12f) },
        start = Offset(0f, 0f),
        end = Offset(400f + 220f * p, 360f - 180f * p)
    )

    val textColor = if (isAi) Color(0xFFECECEC) else Color(0xFF111111)
    val shape = RoundedCornerShape(
        topStart = corner,
        topEnd = corner,
        bottomStart = if (isAi) 4.dp else corner,
        bottomEnd = if (isAi) corner else 4.dp
    )

    Surface(
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        shape = shape,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .drawBehind {
                val cr = CornerRadius(corner.toPx(), corner.toPx())
                // Main fill.
                drawRoundRect(brush = grad, cornerRadius = cr)
                // Tail.
                val x = if (isAi) 12f else size.width - 12f
                val dir = if (isAi) -1 else 1
                drawPath(
                    path = Path().apply {
                        moveTo(x, size.height)
                        lineTo(x + dir * tailW, size.height - tailH)
                        lineTo(x + dir * tailW * 0.4f, size.height - tailH * 0.6f)
                        close()
                    },
                    brush = grad
                )
                // Soft inner highlight for depth.
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = size.minDimension * 0.54f
                    ),
                    cornerRadius = cr
                )
            }
            .neutralEdge(alpha = 0.18f, corner = corner, stroke = 0.8.dp)
    ) {
        Box(Modifier.padding(horizontal = padH, vertical = padV)) {
            if (isTyping && text.isBlank()) {
                TypingDots(color = textColor)
            } else {
                Text(
                    text = text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)
                )
            }
        }
    }
}

/**
 * Three animated dots with phase-shifted alpha.
 *
 * A single [rememberInfiniteTransition] drives all dots, keeping cost low
 * while still providing a smooth typing indicator.
 */
@Composable
private fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "typing")
    val a1 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, delayMillis = 0, easing = LinearEasing)
        ),
        label = "a1"
    )
    val a2 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, delayMillis = 150, easing = LinearEasing)
        ),
        label = "a2"
    )
    val a3 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, delayMillis = 300, easing = LinearEasing)
        ),
        label = "a3"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(alpha = a1, color = color)
        Dot(alpha = a2, color = color)
        Dot(alpha = a3, color = color)
    }
}

@Composable
private fun Dot(alpha: Float, color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

/* ───────────────────────────── JSON bubble ──────────────────────────────── */

/**
 * Collapsible JSON bubble with neutral header and copy action.
 *
 * Behavior:
 *  - In collapsed mode, shows a compact "Analysis preview…" snippet to keep
 *    the conversation readable.
 *  - In expanded mode, renders [pretty] with monospaced font and horizontal
 *    scroll so arbitrary JSON width is supported.
 *
 * @param pretty JSON string to display (already pretty-printed).
 * @param collapsedMaxHeight Maximum height for the collapsed preview text.
 * @param snack Optional [SnackbarHostState] for copy feedback.
 */
@Composable
private fun JsonBubbleMono(
    pretty: String,
    collapsedMaxHeight: Dp = 72.dp,
    snack: SnackbarHostState? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val clip = RoundedCornerShape(10.dp)

    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.60f),
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        shape = clip,
        modifier = Modifier
            .widthIn(max = 580.dp)
            .animateContentSize()
            .neutralEdge(alpha = 0.16f, corner = 10.dp, stroke = 1.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                expanded = !expanded
            }
    ) {
        Column {
            // Neutral header with score and copy button.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF1F1F1F).copy(alpha = 0.22f),
                                Color(0xFF3A3A3A).copy(alpha = 0.22f),
                                Color(0xFF6A6A6A).copy(alpha = 0.22f)
                            )
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val scoreText = extractScore(pretty)?.let { "$it / 100" } ?: "—"
                Text(
                    text = if (expanded) {
                        "Result JSON  •  Score $scoreText  (tap to collapse)"
                    } else {
                        "Score $scoreText  •  tap to expand"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(pretty))
                        scope.launch { snack?.showSnackbar("JSON copied") }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        tint = cs.onSurfaceVariant
                    )
                }
            }

            if (expanded) {
                SelectionContainer {
                    Text(
                        text = pretty,
                        color = cs.onSurface,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        modifier = Modifier
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState())
                    )
                }
            } else {
                Text(
                    text = "Analysis preview…",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = collapsedMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/* ───────────────────────────── Composer ─────────────────────────────────── */

/**
 * Slim glass composer that follows IME.
 *
 * Layout:
 *  - Monotone pill container with subtle neutral rim.
 *  - Single-line primary action button for sending the answer.
 *  - Optional microphone button for speech-to-text integration.
 *
 * @param value Current text in the composer.
 * @param onValueChange Callback invoked when the user edits the text.
 * @param onSend Callback invoked when the user presses the send button.
 * @param enabled Whether the send action is currently allowed.
 * @param focusRequester External [FocusRequester] to control IME focus.
 * @param speechEnabled Whether the microphone UI should be shown.
 * @param speechRecording Whether a speech session is currently active.
 * @param speechStatusText Optional status text displayed under the composer.
 * @param speechStatusIsError Whether [speechStatusText] represents an error.
 * @param onToggleSpeech Optional callback toggling speech start/stop.
 */
@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
    speechEnabled: Boolean = false,
    speechRecording: Boolean = false,
    speechStatusText: String? = null,
    speechStatusIsError: Boolean = false,
    onToggleSpeech: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            cs.surfaceVariant.copy(alpha = 0.65f),
                            cs.surface.copy(alpha = 0.65f)
                        )
                    ),
                    shape = CircleShape
                )
                .neutralEdge(alpha = 0.14f, corner = 999.dp, stroke = 1.dp)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text("Type your answer…") },
                minLines = 1,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            if (speechEnabled && onToggleSpeech != null) {
                val tint = cs.onSurfaceVariant
                IconButton(onClick = onToggleSpeech) {
                    Crossfade(
                        targetState = speechRecording,
                        label = "mic-toggle-composer"
                    ) { rec ->
                        if (rec) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop recording",
                                tint = tint
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Start recording",
                                tint = tint
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send"
                )
            }
        }

        if (speechStatusText != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = speechStatusText,
                style = MaterialTheme.typography.labelSmall,
                color = if (speechStatusIsError) {
                    cs.error
                } else {
                    cs.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

/* ─────────────────────────── Visual utilities ───────────────────────────── */

/**
 * Animated monotone background brush.
 *
 * Implementation:
 *  - Uses a linear gradient with four grayscale stops blended with
 *    [MaterialTheme.colorScheme.surface] for subtle theme integration.
 *  - The gradient end point slowly drifts over time; only luminance changes,
 *    never hue, to keep the appearance strictly neutral.
 */
@Composable
private fun animatedMonotoneBackplate(): Brush {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "bg-mono")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgp"
    )

    val c0 = lerp(Color(0xFF0F0F10), cs.surface, 0.10f)
    val c1 = lerp(Color(0xFF1A1A1B), cs.surface, 0.12f)
    val c2 = lerp(Color(0xFF2A2A2B), cs.surface, 0.14f)
    val c3 = lerp(Color(0xFF3A3A3B), cs.surface, 0.16f)

    val endX = 1200f + 240f * p
    val endY = 820f - 180f * p

    return Brush.linearGradient(
        colors = listOf(c0, c1, c2, c3),
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}

/**
 * Neutral rim decoration for surfaces.
 *
 * Draws a sweep gradient border around the composable, using multiple gray
 * stops to create a subtle, premium-looking rim without strong contrast.
 *
 * @param alpha Overall opacity of the rim.
 * @param corner Corner radius to match the decorated container.
 * @param stroke Stroke width in dp (recommended range: 0.8–1.2 dp).
 */
@Composable
private fun Modifier.neutralEdge(
    alpha: Float = 0.16f,
    corner: Dp = 12.dp,
    stroke: Dp = 1.dp
): Modifier = this.then(
    Modifier.drawBehind {
        val cr = CornerRadius(corner.toPx(), corner.toPx())
        val sweep = Brush.sweepGradient(
            0f to Color(0xFF101010).copy(alpha = alpha),
            0.25f to Color(0xFF3A3A3A).copy(alpha = alpha),
            0.5f to Color(0xFF7A7A7A).copy(alpha = alpha * 0.9f),
            0.75f to Color(0xFF3A3A3A).copy(alpha = alpha),
            1f to Color(0xFF101010).copy(alpha = alpha)
        )
        drawRoundRect(
            brush = sweep,
            style = Stroke(width = stroke.toPx()),
            cornerRadius = cr
        )
    }
)

/* ───────────────────────────── JSON helpers ─────────────────────────────── */

/**
 * Best-effort JSON pretty-printer.
 *
 * Tries to:
 *  - Strip Markdown code fences.
 *  - Parse JSON directly; if that fails, search for the first balanced JSON
 *    object/array substring.
 *  - Pretty-print via [json] if parsing succeeds; otherwise returns [raw].
 */
private fun prettyOrRaw(json: Json, raw: String): String {
    val stripped = stripCodeFence(raw)
    val element = parseJsonLenient(json, stripped)
    return if (element != null) {
        json.encodeToString(JsonElement.serializer(), element)
    } else {
        raw
    }
}

/**
 * Parse JSON in a lenient way by scanning for the first balanced JSON
 * object or array.
 *
 * Returns null if no parseable JSON fragment is found.
 */
private fun parseJsonLenient(json: Json, text: String): JsonElement? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    // Direct parse first.
    parseOrNull(json, trimmed)?.let { return it }

    // Scan for first balanced object/array.
    var i = 0
    while (i < trimmed.length) {
        when (trimmed[i]) {
            '{', '[' -> {
                val end = findMatchingJsonBoundary(trimmed, i)
                if (end != -1) {
                    val candidate = trimmed.substring(i, end + 1)
                    parseOrNull(json, candidate)?.let { return it }
                    i = end
                }
            }
        }
        i++
    }
    return null
}

private fun parseOrNull(json: Json, value: String): JsonElement? =
    runCatching { json.parseToJsonElement(value) }.getOrNull()

/**
 * Strip Markdown-style code fences from a JSON snippet.
 *
 * Recognizes:
 *  ```json
 *  { ... }
 *  ```
 * and returns only the inner content.
 */
private fun stripCodeFence(text: String): String {
    val t = text.trim()
    if (!t.startsWith("```")) return t
    val closing = t.indexOf("```", startIndex = 3)
    if (closing == -1) return t
    val newline = t.indexOf('\n', startIndex = 3)
    val contentStart = if (newline in 4 until closing) newline + 1 else 3
    return t.substring(contentStart, closing).trim()
}

/**
 * Find the index of the matching closing brace/bracket for a JSON object
 * or array starting at [start].
 *
 * Accounts for nested structures and quoted strings. Returns -1 on mismatch
 * or if the structure is not fully closed.
 */
private fun findMatchingJsonBoundary(text: String, start: Int): Int {
    if (start !in text.indices) return -1
    val open = text[start]
    if (open != '{' && open != '[') return -1

    val stack = ArrayDeque<Char>()
    stack.addLast(open)

    var i = start + 1
    var inString = false
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\' && i + 1 < text.length) {
                i += 2
                continue
            }
            if (c == '"') inString = false
        } else {
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}' -> if (stack.isEmpty() || stack.removeLast() != '{') return -1
                ']' -> if (stack.isEmpty() || stack.removeLast() != '[') return -1
            }
        }
        if (stack.isEmpty()) return i
        i++
    }
    return -1
}

/* ───────────────────────────── Preview ─────────────────────────────────── */

/**
 * Preview that renders a static example chat using the monotone theme.
 *
 * Uses fake [AiViewModel.ChatMsgVm] instances to verify the layout and
 * visual balance of bubbles, JSON card, and composer.
 */
@SuppressLint("RememberInComposition")
@Preview(showBackground = true, name = "Chat — Monotone Chic Preview")
@Composable
private fun ChatPreview() {
    MaterialTheme {
        val fake = listOf(
            AiViewModel.ChatMsgVm(
                id = "q",
                sender = AiViewModel.ChatSender.AI,
                text = "How much yield do you lose because of FAW?"
            ),
            AiViewModel.ChatMsgVm(
                id = "u1",
                sender = AiViewModel.ChatSender.USER,
                text = "About 10% over 3 seasons."
            ),
            AiViewModel.ChatMsgVm(
                id = "r1",
                sender = AiViewModel.ChatSender.AI,
                json = """
                    {
                      "analysis": "Clear unit",
                      "expected answer": "~10% avg loss over 3 seasons",
                      "follow-up question": "Is 10% per season or overall?",
                      "score": 88
                    }
                """.trimIndent()
            ),
            AiViewModel.ChatMsgVm(
                id = "fu",
                sender = AiViewModel.ChatSender.AI,
                text = "Is that 10% per season or overall?"
            )
        )
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedMonotoneBackplate())
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                fake.forEach { m ->
                    val isAi = m.sender != AiViewModel.ChatSender.USER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                    ) {
                        if (m.json != null) {
                            JsonBubbleMono(pretty = m.json)
                        } else {
                            BubbleMono(
                                text = m.text.orEmpty(),
                                isAi = isAi,
                                isTyping = false
                            )
                        }
                    }
                }
            }
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                enabled = true,
                focusRequester = FocusRequester()
            )
        }
    }
}
