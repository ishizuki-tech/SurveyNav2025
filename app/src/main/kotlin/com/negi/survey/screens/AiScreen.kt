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
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.negi.survey.screens

import android.annotation.SuppressLint
import android.content.ClipData
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.SurveyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/* =============================================================================
 * AI Evaluation Screen — Monotone × Glass × Chat
 * =============================================================================
 */

/**
 * Simple abstraction for a speech-to-text controller (e.g., Whisper.cpp).
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
     */
    fun toggleRecording() {
        if (isRecording.value) stopRecording() else startRecording()
    }
}

/**
 * Full-screen AI evaluation screen bound to a single survey node.
 *
 * IME stability notes:
 * - Do NOT apply imePadding to both Scaffold content and bottomBar.
 * - Apply imePadding only once to the composer container.
 * - Keep chat area in a weight(1f) container so it naturally shrinks.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    val question by remember(vmSurvey, nodeId) {
        vmSurvey.questions.map { it[nodeId].orEmpty() }
    }.collectAsState(initial = vmSurvey.getQuestion(nodeId))

    val sessionId by vmSurvey.sessionId.collectAsState()
    val surveyUuid by vmSurvey.surveyUuid.collectAsState()

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
     * Composer is scoped by (nodeId, sessionId) to prevent state leakage
     * across survey resets.
     */
    var composer by remember(nodeId, sessionId) {
        mutableStateOf(vmSurvey.getAnswer(nodeId))
    }

    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()

    /**
     * Keep composer aligned with persisted answers on node/session changes.
     */
    LaunchedEffect(nodeId, sessionId) {
        composer = vmSurvey.getAnswer(nodeId)
    }

    // ---------------------------------------------------------------------
    // Seed and focus behavior
    // ---------------------------------------------------------------------

    LaunchedEffect(nodeId, question) {
        vmAI.chatEnsureSeedQuestion(nodeId, question)
    }

    LaunchedEffect(nodeId, sessionId) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(error) {
        error?.let { snack.showSnackbar(it) }
    }

    // ---------------------------------------------------------------------
    // Typing bubble orchestration (2-step safe)
    // ---------------------------------------------------------------------

    /**
     * Update typing bubble while streaming.
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
     * Replace typing bubble with final JSON when raw arrives and loading ends.
     *
     * 2-step note:
     * - We guard with lastJsonRawKey to ensure stage2 does NOT re-trigger JSON replacement.
     * - AiViewModel in 2-step clears raw before FOLLOWUP, but we still protect UI.
     */
    var lastJsonRawKey by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(raw, loading) {
        val r = raw
        if (!r.isNullOrBlank() && !loading) {
            val key = stableKeyForJson(r)
            if (key != null && key == lastJsonRawKey) return@LaunchedEffect

            val pretty = prettyOrRaw(prettyJson, r)
            vmAI.chatReplaceTypingWith(
                nodeId,
                AiViewModel.ChatMsgVm(
                    id = "result-$nodeId-${System.nanoTime()}",
                    sender = AiViewModel.ChatSender.AI,
                    json = pretty
                )
            )
            lastJsonRawKey = key
        }
    }

    /**
     * If we stop loading without raw (cancel/error, or stage2 completion),
     * ensure typing bubble is removed.
     */
    LaunchedEffect(loading, raw) {
        if (!loading && raw.isNullOrBlank()) {
            vmAI.chatRemoveTyping(nodeId)
        }
    }

    // ---------------------------------------------------------------------
    // Follow-up persistence
    // ---------------------------------------------------------------------

    var lastFollowupLocal by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

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

    LaunchedEffect(speechRecording, speechTranscribing, speechPartial) {
        if (speechController == null) return@LaunchedEffect

        val startedThisUtterance =
            (!wasRecording && !wasTranscribing) && (speechRecording || speechTranscribing)

        if (startedThisUtterance) {
            composer = ""
            /**
             * Keep the persisted answer aligned with speech ownership.
             * This is safe because it only happens when speech starts.
             */
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
     * Animate to bottom when chat grows (new bubbles).
     */
    LaunchedEffect(chat.size) {
        delay(16)
        scroll.animateScrollTo(scroll.maxValue)
    }

    /**
     * While streaming, keep pinned (non-animated) to reduce jank.
     */
    LaunchedEffect(stream, loading) {
        if (loading && stream.isNotBlank()) {
            delay(24)
            scroll.scrollTo(scroll.maxValue)
        }
    }

    // ---------------------------------------------------------------------
    // Submit logic (2-step)
    // ---------------------------------------------------------------------

    /**
     * Avoid persisting the "post-send clear" into Survey answers.
     */
    var suppressNextPersist by remember(nodeId, sessionId) { mutableStateOf(false) }

    fun persistComposerIfAllowed(text: String) {
        if (suppressNextPersist) {
            suppressNextPersist = false
            return
        }
        vmSurvey.setAnswer(text, nodeId)
    }

    /**
     * Build 2-step prompt templates from SurveyViewModel.
     *
     * - EVAL prompt: strict JSON with score + needs_followup (expected)
     * - FOLLOWUP prompt: must include {{EVAL_JSON}} placeholder
     *
     * If SurveyViewModel does not yet provide a dedicated follow-up template,
     * we fall back to a minimal wrapper around the same prompt.
     */
    fun buildFollowupTemplateFallback(evalPrompt: String): String {
        return buildString {
            appendLine(evalPrompt.trim())
            appendLine()
            appendLine("You already produced an EVAL JSON.")
            appendLine("Given the EVAL_JSON below, output ONE follow-up question as strict JSON.")
            appendLine("""Keys: "follow_up_question"""")
            appendLine()
            appendLine("EVAL_JSON:")
            appendLine("{{EVAL_JSON}}")
        }
    }

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

            // Stage1: EVAL prompt (existing API).
            val evalPrompt = vmSurvey.getPrompt(nodeId, q, answer)

            // Stage2: FOLLOWUP template.
            // If your SurveyViewModel has a dedicated method, wire it here.
            val followupTemplate = runCatching {
                // Optional: user may add this method later.
                // vmSurvey.getFollowupPromptTemplate(nodeId, q, answer)
                null
            }.getOrNull() ?: buildFollowupTemplateFallback(evalPrompt)

            vmAI.evaluateTwoStepAsync(
                evalPrompt = evalPrompt,
                followupPromptTemplate = followupTemplate,
                gating = AiViewModel.TwoStepGating(
                    evalOkScoreThreshold = 85,
                    skipFollowupWhenOk = true,
                    forceFollowupWhenScoreBelowThreshold = true
                )
            )
        }

        suppressNextPersist = true
        composer = ""
    }

    // ---------------------------------------------------------------------
    // Visuals
    // ---------------------------------------------------------------------

    val bgBrush = animatedMonotoneBackplate()

    /**
     * IME-safe layout:
     * - No Scaffold bottomBar (avoids double insets)
     * - Composer is part of content and receives imePadding exactly once
     */
    Scaffold(
        topBar = { CompactTopBar(title = "Question • $nodeId") },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
                // Apply Scaffold padding ONCE for this screen.
                .padding(pad)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                    }
                }
        ) {
            // Chat area: occupies remaining space and shrinks naturally on IME.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
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

            // Composer area: apply IME + navigation bars padding here only.
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .neutralEdge(alpha = 0.14f, corner = 16.dp, stroke = 1.dp)
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    ChatComposer(
                        value = composer,
                        onValueChange = {
                            composer = it
                            /**
                             * Persist draft input unless the clear was initiated by submit().
                             */
                            persistComposerIfAllowed(it)
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
                        ) { Text("Back") }

                        Spacer(Modifier.weight(1f))

                        OutlinedButton(
                            onClick = {
                                vmAI.resetStates()
                                onNext()
                            }
                        ) { Text("Next") }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { vmAI.resetStates() }
    }
}

/* ───────────────────────────── Top bar ─────────────────────────────────── */

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
                .statusBarsPadding()
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
 * Single dot for typing indicator.
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

@Composable
private fun JsonBubbleMono(
    pretty: String,
    collapsedMaxHeight: Dp = 92.dp,
    snack: SnackbarHostState? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    /**
     * Clipboard API (suspend-friendly).
     */
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val clip = RoundedCornerShape(10.dp)
    val score = remember(pretty) { extractScoreFallback(pretty) }
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
                val scoreText = score?.let { "$it / 100" } ?: "—"
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
                        scope.launch {
                            val ok = runCatching {
                                clipboard.setPlainText(label = "json", text = pretty)
                                true
                            }.getOrElse { false }

                            if (ok) {
                                snack?.showSnackbar("JSON copied")
                            } else {
                                snack?.showSnackbar("Copy failed")
                            }
                        }
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
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
 */
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

/* ───────────────────────────── Clipboard helpers ────────────────────────── */

/**
 * Set clipboard content as plain text using the new Clipboard API.
 *
 * The new API uses suspend functions and ClipEntry.
 */
private suspend fun androidx.compose.ui.platform.Clipboard.setPlainText(label: String, text: String) {
    setClipEntry(
        ClipData
            .newPlainText(label, text)
            .toClipEntry()
    )
}

/* ───────────────────────────── JSON helpers ─────────────────────────────── */

private fun prettyOrRaw(json: Json, raw: String): String {
    val stripped = stripCodeFence(raw)
    val element = parseJsonLenient(json, stripped) ?: return raw

    return runCatching {
        json.encodeToString(JsonElement.serializer(), element)
    }.getOrElse {
        // Fallback keeps a valid JSON-ish payload even if serializer variants differ.
        element.toString()
    }
}

private fun buildJsonPreview(pretty: String): String {
    val json = Json { ignoreUnknownKeys = true }
    val stripped = stripCodeFence(pretty)
    val element = parseJsonLenient(json, stripped)

    val obj = element as? JsonObject
    if (obj != null) {
        val analysis = obj["analysis"]?.toString()?.trim('"')
        val fu = obj["follow_up_question"]?.toString()?.trim('"')
            ?: obj["follow-up question"]?.toString()?.trim('"')
        val expected = obj["expected_answer"]?.toString()?.trim('"')
            ?: obj["expected answer"]?.toString()?.trim('"')

        val lines = buildList {
            if (!analysis.isNullOrBlank()) add("analysis: $analysis")
            if (!expected.isNullOrBlank()) add("expected: $expected")
            if (!fu.isNullOrBlank()) add("follow-up: $fu")
        }
        if (lines.isNotEmpty()) return lines.joinToString("\n")
    }

    val t = stripped.trim()
    return if (t.length <= 180) t else t.take(180).trimEnd() + "…"
}

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

private fun stripCodeFence(text: String): String {
    val t = text.trim()
    if (!t.startsWith("```")) return t
    val closing = t.indexOf("```", startIndex = 3)
    if (closing == -1) return t
    val newline = t.indexOf('\n', startIndex = 3)
    val contentStart = if (newline in 4 until closing) newline + 1 else 3
    return t.substring(contentStart, closing).trim()
}

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

/**
 * Fallback score extractor that does not rely on external helpers.
 *
 * Supported JSON keys:
 * - "score": 88
 * - "score": "88"
 */
private fun extractScoreFallback(text: String): Int? {
    val t = stripCodeFence(text)
    val regex = Regex("""(?i)"score"\s*:\s*"?(\d{1,3})"?""")
    val m = regex.find(t) ?: return null
    val v = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
    return v.coerceIn(0, 100)
}

/**
 * Create a stable key for guarding against duplicate JSON bubble replacement.
 *
 * The key uses a short prefix of the payload to avoid heavy hashing while
 * still being stable enough for UI gating.
 */
private fun stableKeyForJson(raw: String): String? {
    val t = stripCodeFence(raw).trim()
    if (t.isBlank()) return null
    val head = if (t.length <= 240) t else t.take(240)
    return "${t.length}:${head.hashCode()}"
}

/* ───────────────────────────── Preview ─────────────────────────────────── */

@SuppressLint("RememberInComposition")
@Preview(showBackground = true, name = "Monotone Chat Preview")
@Composable
private fun ChatPreview() {
    MaterialTheme {
        val scroll = rememberScrollState()
        val bg = animatedMonotoneBackplate()

        val fake = listOf(
            FakeMsg(senderAi = true, text = "How much yield do you lose because of FAW?"),
            FakeMsg(senderAi = false, text = "About 10% over 3 seasons."),
            FakeMsg(
                senderAi = true,
                json = """
                {
                  "analysis": "Clear unit",
                  "expected_answer": "~10% avg loss over 3 seasons",
                  "follow_up_question": "Is 10% per season or overall?",
                  "score": 88
                }
                """.trimIndent()
            ),
            FakeMsg(senderAi = true, text = "Is that 10% per season or overall?")
        )

        Scaffold(
            topBar = { CompactTopBar(title = "Question • Q1") }
        ) { pad ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .padding(pad)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    fake.forEach { m ->
                        val isAi = m.senderAi
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                        ) {
                            if (m.json != null) {
                                JsonBubbleMono(pretty = m.json)
                            } else {
                                BubbleMono(text = m.text.orEmpty(), isAi = isAi, isTyping = false)
                            }
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .neutralEdge(alpha = 0.14f, corner = 16.dp, stroke = 1.dp)
                ) {
                    ChatComposer(
                        value = "",
                        onValueChange = {},
                        onSend = {},
                        enabled = true,
                        focusRequester = FocusRequester(),
                        speechEnabled = true,
                        speechRecording = false,
                        speechTranscribing = false,
                        onToggleSpeech = {}
                    )
                }
            }
        }
    }
}

private data class FakeMsg(
    val senderAi: Boolean,
    val text: String? = null,
    val json: String? = null
)
