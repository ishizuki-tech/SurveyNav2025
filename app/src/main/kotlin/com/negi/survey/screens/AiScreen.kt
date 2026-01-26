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
 *  Notes:
 *   • No business logic is embedded; this screen only orchestrates VMs.
 *
 *  Update (2026-01):
 *  ---------------------------------------------------------------------
 *   • Two-step debug visibility improvements:
 *     - Add structured logs with stable requestId, phase, and gating signals.
 *     - Add optional on-screen debug badge for quick confirmation.
 *     - Add prompt presence diagnostics (EVAL/FOLLOWUP prompt availability).
 *
 *  Update (2026-01.21):
 *  ---------------------------------------------------------------------
 *   • RunId correlation: prevent stale/late outputs from being misclassified.
 *   • Phase guard: prevent double-submit before loading flips true.
 *   • Heavy JSON formatting moved off main thread.
 *   • LazyColumn chat list + animate gradient only for typing bubbles.
 *
 *  Update (2026-01.22):
 *  ---------------------------------------------------------------------
 *   • IME safety: prevent keyboard from covering the composer.
 *     - Apply imePadding() on the Scaffold root.
 *     - Make focus effect run once per node/session (not on question updates).
 *     - Auto-scroll to bottom when IME becomes visible.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package com.negi.survey.screens

import android.annotation.SuppressLint
import android.content.ClipData
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.survey.BuildConfig
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.SurveyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.collections.ArrayDeque

private const val TAG = "AiScreen"

// Prompt preview logs (careful: might include user data).
private const val LOG_PROMPTS = false

// Two-step logs (recommended ON in DEBUG builds).
private const val LOG_TWO_STEP = true

// Optional on-screen debug badge (recommended ON while debugging).
private const val SHOW_TWO_STEP_BADGE = true

/**
 * Extra delay before starting follow-up generation.
 *
 * Rationale:
 * Even with ViewModel cooldown, some native backends (LiteRT-LM/GPU) can crash
 * on immediate sequential calls. This is a second line of defense.
 */
private const val FOLLOWUP_EXTRA_DELAY_MS = 180L

/**
 * Debounce window to avoid false "empty raw" resets caused by state update ordering.
 */
private const val EMPTY_RAW_DEBOUNCE_MS = 180L

/**
 * Two-step policy for EVAL -> Follow-up gating.
 *
 * IMPORTANT:
 * Default enabled=false for backward compatibility. If config has no two_step section,
 * we must not accidentally run two-step.
 */
data class TwoStepPolicy(
    val enabled: Boolean = false,
    val okScoreThreshold: Int = 85,
    val skipFollowupWhenOk: Boolean = true
)

/**
 * Optional capability interface for two-step prompting.
 */
interface TwoStepPromptProvider {

    /** Build the EVAL prompt for the given node. */
    fun buildEvalPrompt(nodeId: String, question: String, answer: String): String

    /**
     * Build the Follow-up prompt for the given node, using the EVAL JSON result.
     */
    fun buildFollowupPrompt(
        nodeId: String,
        question: String,
        answer: String,
        evalJsonPretty: String
    ): String

    /** Two-step gating policy per node. */
    fun twoStepPolicy(nodeId: String): TwoStepPolicy = TwoStepPolicy()
}

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

    /** Update the context used for correlating speech with the survey run. */
    fun updateContext(surveyId: String?, questionId: String?) { /* no-op */ }

    /** Start capturing audio and producing partial or final text. */
    fun startRecording()

    /** Stop capturing audio and finalize the current utterance. */
    fun stopRecording()

    /** Convenience toggle that switches between start/stop. */
    fun toggleRecording() {
        if (isRecording.value) stopRecording() else startRecording()
    }
}

/** Internal phase for two-step orchestration. */
private enum class TwoStepPhase {
    Idle,
    EvalRunning,

    // Follow-up prompt is ready, but inference not started yet.
    FollowupPending,

    FollowupRunning
}

/**
 * Full-screen AI evaluation screen bound to a single survey node.
 *
 * The screen does not perform any AI logic itself; all evaluation is delegated
 * to [AiViewModel.startSingleStepAsync].
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

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    /* ───────────────────────── Survey state ───────────────────────── */

    val question by remember(vmSurvey, nodeId) {
        vmSurvey.questions.map { it[nodeId].orEmpty() }
    }.collectAsState(initial = vmSurvey.getQuestion(nodeId))

    val sessionId by vmSurvey.sessionId.collectAsState()
    val surveyUuid by vmSurvey.surveyUuid.collectAsState()

    LaunchedEffect(nodeId, sessionId, surveyUuid, speechController) {
        speechController?.updateContext(surveyId = surveyUuid, questionId = nodeId)
    }

    /* ───────────────────────── Speech state ───────────────────────── */

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

    /* ───────────────────────── AI state ───────────────────────── */

    val loading by vmAI.loading.collectAsState()
    val stream by vmAI.stream.collectAsState()
    val raw by vmAI.raw.collectAsState()
    val rawRunId by vmAI.rawRunId.collectAsState()
    val error by vmAI.error.collectAsState()

    val chat by remember(vmAI, nodeId) { vmAI.chatFlow(nodeId) }.collectAsState()

    /* ───────────────────────── Local UI state ───────────────────────── */

    val prettyJson = remember {
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
        }
    }

    var composer by remember(nodeId, sessionId) {
        mutableStateOf(vmSurvey.getAnswer(nodeId))
    }

    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(nodeId, sessionId) {
        composer = vmSurvey.getAnswer(nodeId)
    }

    /* ───────────────────────── Two-step memory ───────────────────────── */

    val twoStepProvider = remember(vmSurvey) { vmSurvey as? TwoStepPromptProvider }

    val twoStepPolicy = remember(twoStepProvider, nodeId, sessionId) {
        twoStepProvider?.twoStepPolicy(nodeId) ?: TwoStepPolicy(enabled = false)
    }
    val twoStepActive = (twoStepProvider != null) && twoStepPolicy.enabled

    var phase by remember(nodeId, sessionId) { mutableStateOf(TwoStepPhase.Idle) }

    /** Holds the submission context needed for follow-up prompt building. */
    var pendingQuestion by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }
    var pendingAnswer by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    /** Stores follow-up prompt while we are in FollowupPending. */
    var pendingFollowupPrompt by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    /** Distinct raw tracking to avoid confusing EVAL output and FOLLOWUP output. */
    var lastEvalRaw by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }
    var lastFollowupRaw by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    /** Remembers whether the current in-flight request is actually using two-step. */
    var inFlightTwoStep by remember(nodeId, sessionId) { mutableStateOf(false) }

    /** Local follow-up memory to avoid double-appends. */
    var lastFollowupLocal by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    // Structured debug: request correlation id for 2-step submit.
    var reqId by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    // Structured debug: last gating decision snapshot (for badge + logs).
    var lastGateSummary by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    // Structured debug: prompt availability snapshot (for quick config sanity).
    var lastPromptPresence by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    // RunId correlation for EVAL and FU (prevents misclassification).
    var evalRunId by remember(nodeId, sessionId) { mutableStateOf(0L) }
    var fuRunId by remember(nodeId, sessionId) { mutableStateOf(0L) }

    /* ───────────────────────── Debug logging helpers ───────────────────────── */

    fun dbgEnabled(): Boolean = BuildConfig.DEBUG && LOG_TWO_STEP

    fun shortId(x: String?): String =
        x?.takeLast(6)?.padStart(6, '0') ?: "------"

    fun shortRun(x: Long): String =
        if (x <= 0L) "------" else x.toString().takeLast(6).padStart(6, '0')

    fun log2(event: String, msg: String) {
        if (!dbgEnabled()) return
        val rid = shortId(reqId)
        Log.d(TAG, "[2STEP][$event][rid=$rid][node=$nodeId][phase=$phase][eval=${shortRun(evalRunId)}][fu=${shortRun(fuRunId)}] $msg")
    }

    fun log2w(event: String, msg: String) {
        if (!dbgEnabled()) return
        val rid = shortId(reqId)
        Log.w(TAG, "[2STEP][$event][rid=$rid][node=$nodeId][phase=$phase][eval=${shortRun(evalRunId)}][fu=${shortRun(fuRunId)}] $msg")
    }

    fun snapshotPromptPresence(evalPrompt: String?, fuPrompt: String?) {
        val e = if (evalPrompt.isNullOrBlank()) "MISSING" else "OK"
        val f = if (fuPrompt.isNullOrBlank()) "MISSING" else "OK"
        lastPromptPresence = "twoStep=$twoStepActive eval=$e fu=$f"
        log2("PROMPTS", lastPromptPresence.orEmpty())
    }

    /* ───────────────────────── Safety reset on node/session change ───────────────────────── */

    LaunchedEffect(nodeId, sessionId) {
        vmAI.resetStates()
        vmAI.chatRemoveTyping(nodeId)

        phase = TwoStepPhase.Idle
        pendingQuestion = null
        pendingAnswer = null
        pendingFollowupPrompt = null
        inFlightTwoStep = false
        lastEvalRaw = null
        lastFollowupRaw = null
        lastFollowupLocal = null
        reqId = null
        lastGateSummary = null
        lastPromptPresence = null
        evalRunId = 0L
        fuRunId = 0L

        log2("RESET", "screen reset for node/session")
    }

    /* ───────────────────────── Seed and focus behavior ───────────────────────── */

    // Seed question when it changes, but DO NOT re-request focus on every question update.
    LaunchedEffect(nodeId, question, sessionId) {
        val q = question.trim()
        if (q.isNotBlank()) {
            vmAI.chatEnsureSeedQuestion(nodeId, q)
        }
    }

    // Focus should run once per node/session to avoid IME churn.
    LaunchedEffect(nodeId, sessionId) {
        runCatching {
            withFrameNanos { /* wait for layout */ }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // When IME becomes visible, force-scroll to bottom so composer stays visible.
    LaunchedEffect(imeVisible, chat.size, nodeId, sessionId) {
        if (!imeVisible) return@LaunchedEffect
        if (chat.isEmpty()) return@LaunchedEffect
        runCatching {
            delay(80)
            listState.animateScrollToItem(index = chat.lastIndex)
        }
    }

    LaunchedEffect(error, nodeId, sessionId) {
        error?.let { snack.showSnackbar(it) }
    }

    /**
     * Safety: reset phase and typing bubble on error to avoid “stuck running”.
     */
    LaunchedEffect(error, loading, phase, nodeId, sessionId) {
        if (loading) return@LaunchedEffect
        if (error.isNullOrBlank()) return@LaunchedEffect
        if (phase == TwoStepPhase.Idle) return@LaunchedEffect

        log2w("ERROR", "phase reset by error: err='$error'")

        phase = TwoStepPhase.Idle
        pendingQuestion = null
        pendingAnswer = null
        pendingFollowupPrompt = null
        inFlightTwoStep = false
        lastEvalRaw = null
        lastFollowupRaw = null
        lastGateSummary = "error"
        evalRunId = 0L
        fuRunId = 0L

        vmAI.chatRemoveTyping(nodeId)
    }

    /**
     * Defensive: handle pathological case where a run ends with no output.
     *
     * IMPORTANT:
     * Do not reset immediately. ViewModel may flip loading=false before raw is published.
     */
    LaunchedEffect(loading, raw, stream, phase, nodeId, sessionId) {
        if (loading) return@LaunchedEffect
        if (phase == TwoStepPhase.Idle) return@LaunchedEffect

        // If we still have partial stream, don't reset.
        if (!stream.isNullOrBlank()) return@LaunchedEffect

        if (raw.isNullOrBlank()) {
            delay(EMPTY_RAW_DEBOUNCE_MS)

            // Re-check after debounce.
            val r2 = raw
            val s2 = stream
            if (!loading && phase != TwoStepPhase.Idle && s2.isNullOrBlank() && r2.isNullOrBlank()) {
                log2w("EMPTY", "phase reset by empty raw (debounced)")
                vmAI.chatRemoveTyping(nodeId)

                phase = TwoStepPhase.Idle
                pendingQuestion = null
                pendingAnswer = null
                pendingFollowupPrompt = null
                inFlightTwoStep = false
                lastEvalRaw = null
                lastFollowupRaw = null
                lastGateSummary = "emptyRaw"
                evalRunId = 0L
                fuRunId = 0L
            }
        }
    }

    /* ───────────────────────── Typing bubble orchestration ───────────────────────── */

    LaunchedEffect(loading, stream, nodeId, sessionId) {
        if (!loading) return@LaunchedEffect
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

    /* ───────────────────────── Speech → Composer commit logic ───────────────────────── */

    var wasRecording by remember(nodeId, sessionId) { mutableStateOf(false) }
    var wasTranscribing by remember(nodeId, sessionId) { mutableStateOf(false) }
    var lastCommitted by remember(nodeId, sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(speechRecording, speechTranscribing, speechPartial, nodeId, sessionId) {
        if (speechController == null) return@LaunchedEffect

        val startedThisUtterance =
            (!wasRecording && !wasTranscribing) && (speechRecording || speechTranscribing)

        if (startedThisUtterance) {
            composer = ""
            vmSurvey.clearAnswer(nodeId)
            lastCommitted = null
        }

        val finishedThisUtterance =
            (wasRecording || wasTranscribing) && !speechRecording && !speechTranscribing

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

    /* ───────────────────────── Auto-scroll ───────────────────────── */

    LaunchedEffect(chat.size, nodeId, sessionId) {
        if (chat.isEmpty()) return@LaunchedEffect
        runCatching {
            withFrameNanos { /* wait for list measurement */ }
            listState.animateScrollToItem(index = chat.lastIndex)
        }
    }

    LaunchedEffect(stream, loading, nodeId, sessionId) {
        if (!loading) return@LaunchedEffect
        if (chat.isEmpty()) return@LaunchedEffect
        runCatching {
            delay(24)
            listState.scrollToItem(index = chat.lastIndex)
        }
    }

    /* ───────────────────────── Two-step execution: start follow-up when pending ───────────────────────── */

    LaunchedEffect(phase, pendingFollowupPrompt, nodeId, sessionId) {
        if (phase != TwoStepPhase.FollowupPending) return@LaunchedEffect

        val fuPrompt = pendingFollowupPrompt.orEmpty().trim()
        if (fuPrompt.isBlank()) {
            log2w("FU_PENDING", "followup.pending: prompt empty -> idle")
            phase = TwoStepPhase.Idle
            pendingFollowupPrompt = null
            pendingQuestion = null
            pendingAnswer = null
            inFlightTwoStep = false
            lastGateSummary = "fuPromptEmpty"
            fuRunId = 0L
            return@LaunchedEffect
        }

        // Delay to reduce native backend re-entry crashes.
        delay(FOLLOWUP_EXTRA_DELAY_MS)

        // CRITICAL: Clear stale raw/stream before starting the next inference.
        vmAI.resetStates()
        vmAI.chatRemoveTyping(nodeId)

        lastFollowupRaw = null
        phase = TwoStepPhase.FollowupRunning

        log2("FU_START", "prompt.len=${fuPrompt.length}")

        val handle = vmAI.startSingleStepAsync(fuPrompt)
        fuRunId = handle.runId
    }

    /* ───────────────────────── Two-step result handling ───────────────────────── */

    LaunchedEffect(
        raw,
        rawRunId,
        loading,
        phase,
        inFlightTwoStep,
        twoStepPolicy,
        twoStepActive,
        nodeId,
        sessionId
    ) {
        val r = raw
        if (loading) return@LaunchedEffect
        if (r.isNullOrBlank()) return@LaunchedEffect

        // Ignore outputs that don't belong to the expected run for the current phase.
        when (phase) {
            TwoStepPhase.EvalRunning -> if (evalRunId <= 0L || rawRunId != evalRunId) return@LaunchedEffect
            TwoStepPhase.FollowupRunning -> if (fuRunId <= 0L || rawRunId != fuRunId) return@LaunchedEffect
            TwoStepPhase.FollowupPending,
            TwoStepPhase.Idle -> return@LaunchedEffect
        }

        when (phase) {
            TwoStepPhase.EvalRunning -> {
                if (lastEvalRaw == r) return@LaunchedEffect
                lastEvalRaw = r

                log2("EVAL_DONE", "raw.len=${r.length} twoStepActive=$twoStepActive inFlight=$inFlightTwoStep rawRunId=$rawRunId")

                val pretty = withContext(Dispatchers.Default) {
                    prettyOrRaw(prettyJson, r)
                }

                // 1) Materialize EVAL/BASE JSON bubble.
                runCatching {
                    vmAI.chatReplaceTypingWith(
                        nodeId,
                        AiViewModel.ChatMsgVm(
                            id = "result-$nodeId-${System.nanoTime()}",
                            sender = AiViewModel.ChatSender.AI,
                            json = pretty
                        )
                    )
                }.onFailure {
                    vmAI.chatAppend(
                        nodeId,
                        AiViewModel.ChatMsgVm(
                            id = "result-$nodeId-${System.nanoTime()}",
                            sender = AiViewModel.ChatSender.AI,
                            json = pretty
                        )
                    )
                }

                // Always remove residual typing bubble (defensive).
                vmAI.chatRemoveTyping(nodeId)

                // 2) Single-step path: done.
                if (!inFlightTwoStep) {
                    lastGateSummary = "singleStep"
                    log2("GATE", "single-step -> idle")
                    phase = TwoStepPhase.Idle
                    pendingQuestion = null
                    pendingAnswer = null
                    pendingFollowupPrompt = null
                    reqId = null
                    evalRunId = 0L
                    fuRunId = 0L
                    return@LaunchedEffect
                }

                // 3) Two-step: decide follow-up.
                val decision = parseEvalDecision(pretty)

                val explicitNeeds = decision.needsFollowup
                val score = decision.score
                val fuFromEval = decision.followupFromEval?.trim().orEmpty()

                val scoreOk = score?.let { it >= twoStepPolicy.okScoreThreshold } ?: false
                val scoreLow = score?.let { it < twoStepPolicy.okScoreThreshold } ?: false
                val hasFuSuggestion = fuFromEval.isNotBlank()

                val shouldFollowup = when (explicitNeeds) {
                    true -> true
                    false -> false
                    null -> {
                        when {
                            hasFuSuggestion -> true
                            scoreLow -> true
                            twoStepPolicy.skipFollowupWhenOk && scoreOk -> false
                            else -> false
                        }
                    }
                }

                lastGateSummary =
                    "score=$score needs=$explicitNeeds hasFu=$hasFuSuggestion okTh=${twoStepPolicy.okScoreThreshold} -> shouldFU=$shouldFollowup"

                log2("GATE", lastGateSummary.orEmpty())

                if (!shouldFollowup) {
                    phase = TwoStepPhase.Idle
                    pendingQuestion = null
                    pendingAnswer = null
                    pendingFollowupPrompt = null
                    inFlightTwoStep = false
                    reqId = null
                    evalRunId = 0L
                    fuRunId = 0L
                    return@LaunchedEffect
                }

                // 4) Build follow-up prompt; if unavailable, fall back to eval's follow-up (if present).
                val pq = pendingQuestion
                val pa = pendingAnswer

                val fuPrompt = runCatching {
                    if (pq != null && pa != null) {
                        twoStepProvider?.buildFollowupPrompt(nodeId, pq, pa, pretty)
                    } else null
                }.getOrNull().orEmpty().trim()

                snapshotPromptPresence(evalPrompt = "OK", fuPrompt = fuPrompt.ifBlank { null })

                if (fuPrompt.isBlank()) {
                    log2w("FU_BUILD", "follow-up prompt empty; fallback to EVAL follow-up if present")

                    if (fuFromEval.isNotBlank() && fuFromEval != lastFollowupLocal) {
                        lastFollowupLocal = fuFromEval
                        vmAI.chatAppend(
                            nodeId,
                            AiViewModel.ChatMsgVm(
                                id = "fu-$nodeId-${System.nanoTime()}",
                                sender = AiViewModel.ChatSender.AI,
                                text = fuFromEval
                            )
                        )
                        vmSurvey.addFollowupQuestion(nodeId, fuFromEval)
                    } else {
                        snack.showSnackbar("Follow-up prompt is empty (fallback unavailable).")
                    }

                    phase = TwoStepPhase.Idle
                    pendingQuestion = null
                    pendingAnswer = null
                    pendingFollowupPrompt = null
                    inFlightTwoStep = false
                    reqId = null
                    evalRunId = 0L
                    fuRunId = 0L
                    return@LaunchedEffect
                }

                // 5) Move to FollowupPending.
                pendingFollowupPrompt = fuPrompt
                phase = TwoStepPhase.FollowupPending
            }

            TwoStepPhase.FollowupRunning -> {
                if (lastFollowupRaw == r) return@LaunchedEffect
                lastFollowupRaw = r

                log2("FU_DONE", "raw.len=${r.length} rawRunId=$rawRunId")

                val fu = withContext(Dispatchers.Default) {
                    extractFollowupQuestionFromAny(r)?.trim().orEmpty()
                }

                if (fu.isBlank()) {
                    vmAI.chatRemoveTyping(nodeId)
                    snack.showSnackbar("No follow-up question extracted.")
                    lastGateSummary = "fuExtractEmpty"
                    phase = TwoStepPhase.Idle
                    pendingQuestion = null
                    pendingAnswer = null
                    pendingFollowupPrompt = null
                    inFlightTwoStep = false
                    reqId = null
                    evalRunId = 0L
                    fuRunId = 0L
                    return@LaunchedEffect
                }

                if (fu != lastFollowupLocal) {
                    lastFollowupLocal = fu

                    runCatching {
                        vmAI.chatReplaceTypingWith(
                            nodeId,
                            AiViewModel.ChatMsgVm(
                                id = "fu-$nodeId-${System.nanoTime()}",
                                sender = AiViewModel.ChatSender.AI,
                                text = fu
                            )
                        )
                    }.onFailure {
                        vmAI.chatAppend(
                            nodeId,
                            AiViewModel.ChatMsgVm(
                                id = "fu-$nodeId-${System.nanoTime()}",
                                sender = AiViewModel.ChatSender.AI,
                                text = fu
                            )
                        )
                    }

                    vmAI.chatRemoveTyping(nodeId)
                    vmSurvey.addFollowupQuestion(nodeId, fu)
                } else {
                    vmAI.chatRemoveTyping(nodeId)
                }

                phase = TwoStepPhase.Idle
                pendingQuestion = null
                pendingAnswer = null
                pendingFollowupPrompt = null
                inFlightTwoStep = false
                reqId = null
                evalRunId = 0L
                fuRunId = 0L
            }

            TwoStepPhase.FollowupPending,
            TwoStepPhase.Idle -> Unit
        }
    }

    /* ───────────────────────── Submit logic ───────────────────────── */

    fun submit() {
        val answer = composer.trim()
        if (answer.isBlank()) return
        if (speechRecording || speechTranscribing) return

        // Phase guard prevents double submit before loading flips true.
        if (phase != TwoStepPhase.Idle) {
            log2w("SUBMIT", "ignored: phase=$phase")
            return
        }
        if (loading) return

        // CRITICAL: Clear stale raw/stream before moving phase to running.
        vmAI.resetStates()
        vmAI.chatRemoveTyping(nodeId)

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

        val q = vmSurvey.getQuestion(nodeId)
        pendingQuestion = q
        pendingAnswer = answer
        pendingFollowupPrompt = null
        lastEvalRaw = null
        lastFollowupRaw = null

        inFlightTwoStep = twoStepActive
        phase = TwoStepPhase.EvalRunning

        // Correlation id for this submit.
        reqId = "${System.currentTimeMillis()}-${(0..9999).random()}"

        // Reset runIds for this submission.
        evalRunId = 0L
        fuRunId = 0L

        // Quick prompt presence probe.
        val evalProbe = runCatching {
            if (inFlightTwoStep) {
                twoStepProvider?.buildEvalPrompt(nodeId, q, answer).orEmpty()
            } else {
                vmSurvey.getPrompt(nodeId, q, answer)
            }
        }.getOrNull()

        snapshotPromptPresence(
            evalPrompt = evalProbe?.takeIf { it.isNotBlank() },
            fuPrompt = null
        )

        log2(
            "SUBMIT",
            "twoStepActive=$twoStepActive inFlightTwoStep=$inFlightTwoStep answer.len=${answer.length}"
        )

        scope.launch {
            val evalPrompt = runCatching {
                if (inFlightTwoStep) {
                    twoStepProvider?.buildEvalPrompt(nodeId, q, answer).orEmpty()
                } else {
                    vmSurvey.getPrompt(nodeId, q, answer)
                }
            }.getOrElse { e ->
                if (BuildConfig.DEBUG) Log.e(TAG, "buildPrompt failed", e)
                ""
            }.trim()

            if (LOG_PROMPTS && BuildConfig.DEBUG) {
                val preview = evalPrompt.replace("\n", " ").take(240)
                Log.d(TAG, "submit: prompt.len=${evalPrompt.length} twoStep=$inFlightTwoStep preview='$preview…'")
            }

            if (evalPrompt.isBlank()) {
                log2w("SUBMIT", "evalPrompt is empty -> idle")
                snack.showSnackbar("Prompt is empty.")
                vmAI.chatRemoveTyping(nodeId)
                phase = TwoStepPhase.Idle
                pendingQuestion = null
                pendingAnswer = null
                pendingFollowupPrompt = null
                inFlightTwoStep = false
                reqId = null
                lastGateSummary = "evalPromptEmpty"
                evalRunId = 0L
                fuRunId = 0L
                return@launch
            }

            log2("EVAL_START", "prompt.len=${evalPrompt.length}")
            val handle = vmAI.startSingleStepAsync(evalPrompt)
            evalRunId = handle.runId
        }

        composer = ""
    }

    /* ───────────────────────── Visuals ───────────────────────── */

    val bgBrush = animatedMonotoneBackplate()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(), // ✅ Key fix: lift entire layout above the keyboard.
        topBar = { CompactTopBar(title = "Question • $nodeId") },
        snackbarHost = { SnackbarHost(snack) },
        contentWindowInsets = zeroInsetsSafe(),
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding() // ✅ Avoid nav bar overlap.
                    .neutralEdge(alpha = 0.14f, corner = 16.dp, stroke = 1.dp)
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
                        onToggleSpeech = speechController?.let { sc -> { sc.toggleRecording() } }
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
                                runCatching { speechController?.stopRecording() }
                                vmAI.chatRemoveTyping(nodeId)
                                vmAI.stopInFlightSilently()
                                vmAI.resetStates()
                                onBack()
                            }
                        ) { Text("Back") }

                        Spacer(Modifier.weight(1f))

                        OutlinedButton(
                            onClick = {
                                runCatching { speechController?.stopRecording() }
                                vmAI.chatRemoveTyping(nodeId)
                                vmAI.stopInFlightSilently()
                                vmAI.resetStates()
                                onNext()
                            }
                        ) { Text("Next") }
                    }
                }
            }
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .consumeWindowInsets(pad)
                .fillMaxSize()
                .background(bgBrush)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (BuildConfig.DEBUG && SHOW_TWO_STEP_BADGE) {
                    TwoStepDebugBadge(
                        nodeId = nodeId,
                        phase = phase,
                        loading = loading,
                        twoStepActive = twoStepActive,
                        inFlightTwoStep = inFlightTwoStep,
                        policy = twoStepPolicy,
                        reqId = reqId,
                        promptPresence = lastPromptPresence,
                        gateSummary = lastGateSummary,
                        evalRunId = evalRunId,
                        fuRunId = fuRunId,
                        rawRunId = rawRunId
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = chat, key = { it.id }) { m ->
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
    }

    DisposableEffect(nodeId, sessionId, speechController) {
        onDispose {
            runCatching { speechController?.stopRecording() }
            vmAI.chatRemoveTyping(nodeId)
            vmAI.stopInFlightSilently()
            vmAI.resetStates()
        }
    }
}

/* ───────────────────────────── Two-step badge ───────────────────────────── */

@Composable
private fun TwoStepDebugBadge(
    nodeId: String,
    phase: TwoStepPhase,
    loading: Boolean,
    twoStepActive: Boolean,
    inFlightTwoStep: Boolean,
    policy: TwoStepPolicy,
    reqId: String?,
    promptPresence: String?,
    gateSummary: String?,
    evalRunId: Long,
    fuRunId: Long,
    rawRunId: Long
) {
    val cs = MaterialTheme.colorScheme
    val rid = reqId?.takeLast(6)?.padStart(6, '0') ?: "------"
    fun sr(x: Long) = if (x <= 0L) "------" else x.toString().takeLast(6).padStart(6, '0')

    val line1 = "2-step: active=$twoStepActive inFlight=$inFlightTwoStep phase=$phase loading=$loading rid=$rid"
    val line2 = "runs: eval=${sr(evalRunId)} fu=${sr(fuRunId)} raw=${sr(rawRunId)}"
    val line3 = "policy: okTh=${policy.okScoreThreshold} skipOk=${policy.skipFollowupWhenOk}"
    val line4 = promptPresence?.let { "prompts: $it" } ?: "prompts: (n/a)"
    val line5 = gateSummary?.let { "gate: $it" } ?: "gate: (n/a)"

    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .neutralEdge(alpha = 0.12f, corner = 10.dp, stroke = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(line1, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(line2, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(line3, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(line4, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(line5, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
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

    // Perf: animate gradient only for typing bubbles.
    val p = if (isTyping) {
        val t = rememberInfiniteTransition(label = "bubble-mono")
        val pp by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "p"
        )
        pp
    } else 0f

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

@Composable
private fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "typing")
    val a1 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900, delayMillis = 0, easing = LinearEasing)),
        label = "a1"
    )
    val a2 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900, delayMillis = 150, easing = LinearEasing)),
        label = "a2"
    )
    val a3 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900, delayMillis = 300, easing = LinearEasing)),
        label = "a3"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(alpha = a1, color = color)
        Dot(alpha = a2, color = color)
        Dot(alpha = a3, color = color)
    }
}

@Composable
private fun Dot(alpha: Float, color: Color) {
    Box(modifier = Modifier.size(8.dp).background(color.copy(alpha = alpha), CircleShape))
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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clip = RoundedCornerShape(10.dp)

    val headerScore = remember(pretty) { parseEvalDecision(pretty).score }
    val previewText = remember(pretty) { buildJsonPreview(pretty) }

    var suppressToggle by remember { mutableStateOf(false) }

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
                if (suppressToggle) {
                    suppressToggle = false
                    return@clickable
                }
                expanded = !expanded
            }
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
                    text = if (expanded) "Result JSON  •  Score $scoreText  (tap to collapse)" else "Score $scoreText  •  tap to expand",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        suppressToggle = true
                        scope.launch {
                            val clipData = ClipData.newPlainText("json", pretty)
                            clipboard.setClipEntry(clipData.toClipEntry())
                            snack?.showSnackbar("JSON copied")
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

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
                .background(
                    brush = Brush.linearGradient(listOf(cs.surfaceVariant.copy(alpha = 0.65f), cs.surface.copy(alpha = 0.65f))),
                    shape = CircleShape
                )
                .neutralEdge(alpha = 0.14f, corner = 999.dp, stroke = 1.dp)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
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
                val micEnabled = (enabled || speechRecording) && !speechTranscribing

                IconButton(onClick = onToggleSpeech, enabled = micEnabled) {
                    Crossfade(targetState = speechRecording, label = "mic-toggle-composer") { rec ->
                        if (rec) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop recording", tint = tint)
                        } else {
                            Icon(Icons.Filled.Mic, contentDescription = "Start recording", tint = tint)
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
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
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

private fun zeroInsetsSafe(): WindowInsets = WindowInsets(0, 0, 0, 0)

/* ───────────────────────────── JSON helpers ─────────────────────────────── */

private fun prettyOrRaw(json: Json, raw: String): String {
    val stripped = stripCodeFence(raw)
    val element = parseJsonLenient(json, stripped)
    return if (element != null) {
        json.encodeToString(JsonElement.serializer(), element)
    } else stripped
}

private fun buildJsonPreview(pretty: String): String {
    val json = Json { ignoreUnknownKeys = true }
    val stripped = stripCodeFence(pretty)
    val element = parseJsonLenient(json, stripped)

    val obj = element as? JsonObject
    if (obj != null) {
        val analysis = obj.firstStringOf("analysis")
        val expected = obj.firstStringOf("expected_answer", "expectedAnswer", "expected answer")
        val fu = obj.firstStringOf(
            "follow_up_question",
            "followUpQuestion",
            "followup_question",
            "follow-up question",
            "follow up question"
        )

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

private data class EvalDecision(
    val score: Int?,
    val needsFollowup: Boolean?,
    val followupFromEval: String?
)

private fun parseEvalDecision(pretty: String): EvalDecision {
    val json = Json { ignoreUnknownKeys = true }
    val stripped = stripCodeFence(pretty)
    val element = parseJsonLenient(json, stripped)

    val obj = element as? JsonObject
    if (obj != null) {
        val score = obj.firstIntOf("score")
        val needs = obj.firstBoolOf("needs_followup", "needsFollowup", "needsFollowUp", "needs_follow_up")
        val fu = obj.firstStringOf(
            "follow_up_question",
            "followUpQuestion",
            "followup_question",
            "follow-up question",
            "follow up question"
        )
        return EvalDecision(score = score, needsFollowup = needs, followupFromEval = fu)
    }
    return EvalDecision(score = null, needsFollowup = null, followupFromEval = null)
}

private fun extractFollowupQuestionFromAny(raw: String): String? {
    val json = Json { ignoreUnknownKeys = true }
    val stripped = stripCodeFence(raw).trim()
    if (stripped.isBlank()) return null

    val element = parseJsonLenient(json, stripped)
    when (element) {
        is JsonObject -> {
            val fu = element.firstStringOf(
                "follow_up_question",
                "followUpQuestion",
                "followup_question",
                "follow-up question",
                "follow up question"
            )
            if (!fu.isNullOrBlank()) return fu
        }
        is JsonPrimitive -> {
            val s = element.contentOrNullSafe()?.trim()
            if (!s.isNullOrBlank()) return s
        }
        else -> Unit
    }

    val t = stripped.trim()
    if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) {
        return t.substring(1, t.length - 1).trim().ifBlank { null }
    }
    return t.ifBlank { null }
}

private fun JsonObject.firstStringOf(vararg keys: String): String? {
    for (k in keys) {
        val v = (this[k] as? JsonPrimitive)?.contentOrNullSafe()?.trim()
        if (!v.isNullOrBlank()) return v
    }
    return null
}

private fun JsonObject.firstIntOf(vararg keys: String): Int? {
    for (k in keys) {
        val p = this[k] as? JsonPrimitive ?: continue
        val v = p.contentOrNullSafe()?.trim().orEmpty()
        val i = v.toIntOrNull()
        if (i != null) return i
    }
    return null
}

private fun JsonObject.firstBoolOf(vararg keys: String): Boolean? {
    for (k in keys) {
        val p = this[k] as? JsonPrimitive ?: continue
        val v = p.contentOrNullSafe()?.trim()?.lowercase().orEmpty()
        when (v) {
            "true", "1", "yes", "y" -> return true
            "false", "0", "no", "n" -> return false
        }
    }
    return null
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { this.content }.getOrNull()

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
                      "expected_answer": "~10% avg loss over 3 seasons",
                      "needs_followup": true,
                      "follow_up_question": "Is 10% per season or overall?",
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

        val listState = rememberLazyListState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedMonotoneBackplate())
                .padding(16.dp)
        ) {
            TwoStepDebugBadge(
                nodeId = "Q1",
                phase = TwoStepPhase.EvalRunning,
                loading = false,
                twoStepActive = true,
                inFlightTwoStep = true,
                policy = TwoStepPolicy(enabled = true, okScoreThreshold = 85, skipFollowupWhenOk = true),
                reqId = "1700000000000-1234",
                promptPresence = "twoStep=true eval=OK fu=(n/a)",
                gateSummary = "score=88 needs=true hasFu=true okTh=85 -> shouldFU=true",
                evalRunId = 123456,
                fuRunId = 123457,
                rawRunId = 123456
            )

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = fake, key = { it.id }) { m ->
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
