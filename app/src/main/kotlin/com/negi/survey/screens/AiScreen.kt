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

@file:Suppress("MemberVisibilityCanBePrivate")

package com.negi.survey.screens

import android.annotation.SuppressLint
import android.util.Log
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
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.SurveyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
 * Simple abstraction for a speech-to-text controller (e.g., Whisper.cpp).
 *
 * Implementations are expected to:
 *  - Expose recording state, transcription state, and latest recognized text
 *    as [StateFlow]s.
 *  - Provide [startRecording] and [stopRecording] controls.
 *  - Optionally rely on [toggleRecording] as a convenience entry point.
 *  - Accept optional context for export naming/correlation.
 */
interface SpeechController {

    /** True while microphone capture is running. */
    val isRecording: StateFlow<Boolean>

    /** True while the captured audio is being transcribed. */
    val isTranscribing: StateFlow<Boolean>

    /** Latest recognized text (partial or final). */
    val partialText: StateFlow<String>

    /** Optional human-readable error message. */
    val errorMessage: StateFlow<String?>

    /**
     * Update the context used for correlating speech with the survey run.
     *
     * Implementations that export voice files may embed these values into
     * file names or sidecar metadata.
     *
     * Default implementation is a no-op so non-export controllers remain valid.
     *
     * @param surveyId UUID of the active survey run.
     * @param questionId Node ID for the current question.
     */
    fun updateContext(
        surveyId: String?,
        questionId: String?
    ) {
        // no-op
    }

    /** Start capturing audio and producing partial or final text. */
    fun startRecording()

    /** Stop capturing audio and finalize the current utterance. */
    fun stopRecording()

    /**
     * Convenience toggle that switches between start/stop.
     *
     * This method reads [isRecording.value] directly. Implementations should
     * ensure their [StateFlow] values are updated promptly to avoid UI drift.
     */
    fun toggleRecording() {
        if (isRecording.value) stopRecording() else startRecording()
    }
}

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

    // ---------------------------------------------------------------------
    // Survey state
    // ---------------------------------------------------------------------

    /**
     * Question text stream for this node.
     *
     * We default the initial state to the ViewModel snapshot to avoid a
     * transient blank state when the Flow is still cold.
     */
    val question by remember(vmSurvey, nodeId) {
        vmSurvey.questions.map { it[nodeId].orEmpty() }
    }.collectAsState(initial = vmSurvey.getQuestion(nodeId))

    /**
     * Session id increments when the survey is reset. Used to scope local UI
     * memory so previous run artifacts do not leak into a new run.
     */
    val sessionId by vmSurvey.sessionId.collectAsState()

    /**
     * Stable UUID for the active survey run.
     *
     * This is the preferred identifier for export correlation and should
     * be propagated to any speech controller that writes voice files.
     */
    val surveyUuid by vmSurvey.surveyUuid.collectAsState()

    /**
     * Keep speech controller context in sync with the active survey run + node.
     *
     * This allows voice exports to embed a stable UUID/question association.
     */
    LaunchedEffect(nodeId, sessionId, surveyUuid, speechController) {
        speechController?.updateContext(
            surveyId = surveyUuid,
            questionId = nodeId
        )
    }

    // ---------------------------------------------------------------------
    // Speech state with null-safe fallbacks
    // ---------------------------------------------------------------------

    val recFlow = remember(speechController) { speechController?.isRecording ?: flowOf(false) }
    val transFlow = remember(speechController) { speechController?.isTranscribing ?: flowOf(false) }
    val partialFlow = remember(speechController) { speechController?.partialText ?: flowOf("") }
    val errFlow = remember(speechController) { speechController?.errorMessage ?: flowOf<String?>(null) }

    val speechRecording by recFlow.collectAsState(initial = false)
    val speechTranscribing by transFlow.collectAsState(initial = false)
    val speechPartial by partialFlow.collectAsState(initial = "")
    val speechError by errFlow.collectAsState(initial = null)

    /**
     * Text field should be disabled while recording or transcribing.
     *
     * This prevents the user from fighting with live STT updates and keeps
     * the state machine simple: either the user types or the mic owns input.
     */
    val textFieldEnabled = !speechRecording && !speechTranscribing

    val speechStatusText: String? = when {
        speechError != null -> speechError
        speechController != null && speechTranscribing -> "Transcribing…"
        speechController != null && speechRecording -> "Listening…"
        else -> null
    }
    val speechStatusIsError = speechError != null

    // ---------------------------------------------------------------------
    // AI state
    // ---------------------------------------------------------------------

    val loading by vmAI.loading.collectAsState()
    val stream by vmAI.stream.collectAsState()
    val raw by vmAI.raw.collectAsState()
    val error by vmAI.error.collectAsState()
    val followup by vmAI.followupQuestion.collectAsState()

    /**
     * Chat history for this node.
     *
     * This is a ViewModel-backed stream to ensure chat remains stable across
     * recompositions and survives configuration changes.
     */
    val chat by remember(vmAI, nodeId) { vmAI.chatFlow(nodeId) }.collectAsState()

    // ---------------------------------------------------------------------
    // Local UI state
    // ---------------------------------------------------------------------

    val prettyJson = remember {
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
        }
    }

    /**
     * Local composer value scoped by (nodeId, sessionId).
     *
     * This ensures a brand-new survey run does not inherit draft input from
     * a previous run, even if the screen object is still in memory.
     */
    var composer by remember(nodeId, sessionId) {
        mutableStateOf(vmSurvey.getAnswer(nodeId))
    }

    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()

    /**
     * Sync composer with persisted Survey answers at node/session boundaries.
     */
    LaunchedEffect(nodeId, sessionId) {
        composer = vmSurvey.getAnswer(nodeId)
    }

    // ---------------------------------------------------------------------
    // Seed and focus behavior
    // ---------------------------------------------------------------------

    /**
     * Ensure the first AI bubble contains the question and request focus.
     *
     * We intentionally keep the keyboard open to support rapid iterative
     * answering when the AI asks follow-up questions.
     */
    LaunchedEffect(nodeId, question) {
        vmAI.chatEnsureSeedQuestion(nodeId, question)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    /**
     * Surface AI errors as snackbars.
     */
    LaunchedEffect(error) {
        error?.let { snack.showSnackbar(it) }
    }

    // ---------------------------------------------------------------------
    // Typing bubble orchestration
    // ---------------------------------------------------------------------

    /**
     * Maintain/update typing bubble during streaming.
     */
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

    /**
     * Replace typing bubble with pretty JSON when the final result arrives.
     */
    LaunchedEffect(raw, loading) {
        if (!raw.isNullOrBlank() && !loading) {
            val pretty = prettyOrRaw(prettyJson, raw!!)
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

    /**
     * If streaming stops without a final raw result (cancel/error),
     * ensure the typing bubble is removed to avoid "stuck typing" UX.
     */
    LaunchedEffect(loading, raw) {
        if (!loading && raw.isNullOrBlank()) {
            vmAI.chatRemoveTyping(nodeId)
        }
    }

    // ---------------------------------------------------------------------
    // Follow-up persistence
    // ---------------------------------------------------------------------

    /**
     * Track the last follow-up appended by this screen instance to avoid
     * re-appending a stable follow-up on recomposition.
     */
    var lastFollowupLocal by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    /**
     * Append follow-up question when AI is idle; also persist to SurveyViewModel.
     */
    LaunchedEffect(followup, loading) {
        val fu = followup
        if (fu != null && !loading && fu != lastFollowupLocal) {
            lastFollowupLocal = fu

            vmAI.chatAppend(
                nodeId,
                AiViewModel.ChatMsgVm(
                    id = "fu-$nodeId-${System.nanoTime()}",
                    sender = AiViewModel.ChatSender.AI,
                    text = fu
                )
            )
            vmSurvey.addFollowupQuestion(nodeId, fu)
        }
    }

    // ---------------------------------------------------------------------
    // Speech → Composer commit logic
    // ---------------------------------------------------------------------

    var wasRecording by remember(nodeId, sessionId) { mutableStateOf(false) }
    var wasTranscribing by remember(nodeId, sessionId) { mutableStateOf(false) }
    var lastCommitted by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    /**
     * Commit recognized speech only when an utterance finishes.
     *
     * Design choice:
     * - When a new utterance begins, we clear the current composer and
     *   persisted answer for this node. This favors "speech owns the answer"
     *   semantics and prevents accidental concatenation of multiple utterances.
     *
     * If you want a "speech appends to typed draft" experience instead,
     * remove the clear step and append the final text to [composer].
     */
    LaunchedEffect(speechRecording, speechTranscribing, speechPartial) {
        if (speechController == null) return@LaunchedEffect

        val startedThisUtterance =
            (!wasRecording && !wasTranscribing) &&
                    (speechRecording || speechTranscribing)

        if (startedThisUtterance) {
            composer = ""
            vmSurvey.clearAnswer(nodeId)
            lastCommitted = null
        }

        val finishedThisUtterance =
            (wasRecording || wasTranscribing) &&
                    !speechRecording &&
                    !speechTranscribing

        if (finishedThisUtterance) {
            val text = speechPartial.trim()
            if (text.isNotEmpty() && text != lastCommitted) {
                composer = text
                vmSurvey.setAnswer(text, nodeId)
                lastCommitted = text
            }
        }

        wasRecording = speechRecording
        wasTranscribing = speechTranscribing
    }

    // ---------------------------------------------------------------------
    // Auto-scroll
    // ---------------------------------------------------------------------

    /**
     * Auto-scroll to bottom when chat size changes.
     */
    LaunchedEffect(chat.size) {
        delay(16)
        scroll.animateScrollTo(scroll.maxValue)
    }

    /**
     * Keep view pinned to the bottom while streaming grows.
     */
    LaunchedEffect(stream, loading) {
        if (loading) {
            delay(24)
            scroll.scrollTo(scroll.maxValue)
        }
    }

    // ---------------------------------------------------------------------
    // Submit logic
    // ---------------------------------------------------------------------

    /**
     * Submit current answer and trigger AI evaluation.
     *
     * This keeps IME open for rapid back-and-forth rounds.
     */
    fun submit() {
        val answer = composer.trim()
        if (answer.isBlank() || loading) return
        if (speechRecording || speechTranscribing) return

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
            Log.d("AiScreen", "Submitting: $prompt")
            vmAI.evaluateAsync(prompt)
        }

        // Clear only the local composer to show immediate "sent" feedback.
        composer = ""
    }

    // ---------------------------------------------------------------------
    // Visuals
    // ---------------------------------------------------------------------

    val bgBrush = animatedMonotoneBackplate()

    Scaffold(
        topBar = { CompactTopBar(title = "Question • $nodeId") },
        snackbarHost = { SnackbarHost(snack) },
        /**
         * Keep window inset handling centralized so different Compose versions
         * can be supported by swapping this value if needed.
         */
        contentWindowInsets = zeroInsetsSafe(),
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
                        enabled = textFieldEnabled && !loading,
                        focusRequester = focusRequester,
                        speechEnabled = speechController != null,
                        speechRecording = speechRecording,
                        speechTranscribing = speechTranscribing,
                        speechStatusText = speechStatusText,
                        speechStatusIsError = speechStatusIsError,
                        onToggleSpeech = speechController?.let { sc ->
                            { sc.toggleRecording() }
                        }
                    )

                    HorizontalDivider(
                        thickness = DividerDefaults.Thickness,
                        color = DividerDefaults.color
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
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

    /**
     * Clear transient AI state when leaving this screen.
     *
     * Chat history is intentionally preserved inside [AiViewModel].
     */
    DisposableEffect(Unit) {
        onDispose { vmAI.resetStates() }
    }
}

/* ───────────────────────────── Top bar ─────────────────────────────────── */

/**
 * Minimal top bar for the AI screen.
 *
 * @param title Display title for the current node.
 * @param height Height of the bar content area.
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
 * Monotone chat bubble with a subtle glass edge and micro-tail.
 *
 * @param text Bubble text content.
 * @param isAi True for AI/system bubbles, false for user bubbles.
 * @param isTyping True when this bubble is a typing indicator.
 * @param maxWidth Maximum visual width for a bubble.
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

    val stops = if (isAi) {
        listOf(Color(0xFF111111), Color(0xFF1E1E1E), Color(0xFF2A2A2A))
    } else {
        listOf(Color(0xFFEDEDED), Color(0xFFD9D9D9), Color(0xFFC8C8C8))
    }

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
                drawRoundRect(brush = grad, cornerRadius = cr)

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

                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = (kotlin.math.min(size.width, size.height)) * 0.54f
                    ),
                    cornerRadius = cr
                )

                drawRoundRect(
                    brush = Brush.sweepGradient(
                        0f to Color(0xFF101010).copy(alpha = 0.12f),
                        0.5f to Color(0xFF7A7A7A).copy(alpha = 0.10f),
                        1f to Color(0xFF101010).copy(alpha = 0.12f)
                    ),
                    style = Stroke(width = 0.8.dp.toPx()),
                    cornerRadius = cr
                )
            }
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
 * Three-dot typing indicator for AI bubbles.
 *
 * @param color Base color for dots.
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

/**
 * Single dot for the typing indicator.
 *
 * @param alpha Current animated alpha.
 * @param color Base color.
 */
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
 * Compact JSON result bubble with expand/collapse and copy action.
 *
 * @param pretty Pretty-printed JSON, or raw fallback text.
 * @param collapsedMaxHeight Max height for the collapsed preview.
 * @param snack Optional snackbar host for UX feedback on copy.
 */
@Composable
private fun JsonBubbleMono(
    pretty: String,
    collapsedMaxHeight: Dp = 92.dp,
    snack: SnackbarHostState? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val clip = RoundedCornerShape(10.dp)

    val headerScore = remember(pretty) { FollowupExtractor.extractScore(pretty) }
    val previewText = remember(pretty) { buildJsonPreview(pretty) }

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
            ) { expanded = !expanded }
    ) {
        Column {
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
                val scoreText = headerScore?.let { "$it / 100" } ?: "—"
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
                    text = previewText,
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
 * Chat composer row with optional microphone control.
 *
 * @param value Current composer text.
 * @param onValueChange Text change callback.
 * @param onSend Submit callback.
 * @param enabled Global enable state for user input.
 * @param focusRequester Focus requester for the text field.
 * @param speechEnabled True when a [SpeechController] is supplied.
 * @param speechRecording True while microphone capture is running.
 * @param speechTranscribing True while STT decoding is running.
 * @param speechStatusText Optional status line under the composer.
 * @param speechStatusIsError True when the status line should use the error color.
 * @param onToggleSpeech Recorder toggle callback.
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
    speechTranscribing: Boolean = false,
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
                enabled = enabled,
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
                /**
                 * Mic button is disabled while transcribing to avoid overlapping
                 * utterance sessions in controllers that do not support re-entrancy.
                 */
                val micEnabled = (enabled || speechRecording) && !speechTranscribing

                IconButton(
                    onClick = onToggleSpeech,
                    enabled = micEnabled
                ) {
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
                color = if (speechStatusIsError) cs.error else cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

/* ─────────────────────────── Visual utilities ───────────────────────────── */

/**
 * Animated monotone backplate.
 *
 * This creates slow, neutral gradients that keep the screen firmly in
 * grayscale territory while still providing subtle motion cues.
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
 * Draw a subtle monochrome edge highlight around a surface.
 *
 * @param alpha Base alpha of the edge gradient.
 * @param corner Corner radius used to compute the stroke rounding.
 * @param stroke Stroke width.
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

/**
 * Provide a "zero insets" value for Scaffold in a single place.
 *
 * Some Compose versions offer a direct constructor. If your build complains,
 * replace this with a version-appropriate alternative.
 */
private fun zeroInsetsSafe(): WindowInsets {
    return WindowInsets(0, 0, 0, 0)
}

/* ───────────────────────────── JSON helpers ─────────────────────────────── */

/**
 * Try to pretty-print a JSON-like response; fall back to raw text.
 *
 * The model sometimes wraps JSON in Markdown fences or emits extra natural
 * language around it. We attempt lenient extraction of the first valid JSON
 * object/array found in the text.
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
 * Build a compact preview string for the collapsed JSON bubble.
 *
 * We prefer the three high-signal keys used by the evaluation contract:
 * - "analysis"
 * - "expected answer"
 * - "follow-up question"
 */
private fun buildJsonPreview(pretty: String): String {
    val json = Json { ignoreUnknownKeys = true }
    val stripped = stripCodeFence(pretty)
    val element = parseJsonLenient(json, stripped)

    val obj = element as? kotlinx.serialization.json.JsonObject
    if (obj != null) {
        val analysis = obj["analysis"]?.toString()?.trim('"')
        val fu = obj["follow-up question"]?.toString()?.trim('"')
        val expected = obj["expected answer"]?.toString()?.trim('"')

        val lines = buildList {
            if (!analysis.isNullOrBlank()) add("analysis: $analysis")
            if (!expected.isNullOrBlank()) add("expected: $expected")
            if (!fu.isNullOrBlank()) add("follow-up: $fu")
        }

        if (lines.isNotEmpty()) {
            return lines.joinToString("\n")
        }
    }

    val t = stripped.trim()
    return if (t.length <= 180) t else t.take(180).trimEnd() + "…"
}

/**
 * Lenient JSON parser that attempts to locate the first valid JSON payload
 * in a mixed string.
 */
private fun parseJsonLenient(json: Json, text: String): JsonElement? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    parseOrNull(json, trimmed)?.let { return it }

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
 * Strip a single Markdown code fence layer if present.
 *
 * Supports:
 *  ```json
 *  { ... }
 *  ```
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
 * Find the matching closing boundary for a JSON object or array.
 *
 * This is a lightweight parser that respects string escaping rules.
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
