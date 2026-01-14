/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.slm.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * ViewModel dedicated to AI-related operations and chat persistence.
 *
 * Responsibilities:
 * - Build prompts and evaluate text via [Repository].
 * - Stream partial outputs to UI.
 * - Extract and keep score / follow-up questions (top-3).
 * - Persist chat history per nodeId.
 * - Provide robust timeout/cancel handling.
 *
 * Concurrency model:
 * - At most one evaluation is allowed at a time.
 * - The single-flight guarantee is enforced by [running].
 * - The active evaluation coroutine is tracked by [evalJob] so UI can cancel it.
 */
class AiViewModel(
    private val repo: Repository,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "AiViewModel"
        private const val DEBUG_LOGS = true
        private const val DEFAULT_TIMEOUT_MS = 120_000L

        /** Placeholder used in FOLLOWUP prompt template to inject EVAL JSON. */
        private const val EVAL_JSON_PLACEHOLDER = "{{EVAL_JSON}}"
    }

    /**
     * Two-step gating knobs.
     *
     * - EVAL returns strict JSON containing:
     *   - score (0..100)
     *   - needs_followup (boolean)
     * - FOLLOWUP runs only when gating decides it is necessary.
     */
    data class TwoStepGating(
        val evalOkScoreThreshold: Int = 85,
        val skipFollowupWhenOk: Boolean = true,
        val forceFollowupWhenScoreBelowThreshold: Boolean = true
    )

    // ───────────────────────── UI state ─────────────────────────

    private val _loading = MutableStateFlow(false)

    /** True while an evaluation is in progress. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _score = MutableStateFlow<Int?>(null)

    /** Parsed evaluation score (0..100) or null when unavailable. */
    val score: StateFlow<Int?> = _score.asStateFlow()

    private val _stream = MutableStateFlow("")

    /** Live concatenation of streamed tokens from the model. */
    val stream: StateFlow<String> = _stream.asStateFlow()

    private val _raw = MutableStateFlow<String?>(null)

    /** Final raw output used for JSON bubble (EVAL stage). */
    val raw: StateFlow<String?> = _raw.asStateFlow()

    private val _followupQuestion = MutableStateFlow<String?>(null)

    /** First follow-up question extracted from the model output. */
    val followupQuestion: StateFlow<String?> = _followupQuestion.asStateFlow()

    private val _followups = MutableStateFlow<List<String>>(emptyList())

    /** All extracted follow-up questions (up to top-3). */
    val followups: StateFlow<List<String>> = _followups.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /**
     * Last error string:
     * - "timeout"
     * - "cancelled"
     * - other human-readable message
     *
     * Null means no surface-worthy error is present.
     */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _events = MutableSharedFlow<AiEvent>(extraBufferCapacity = 32)

    /** Event stream for fine-grained UI reactions (toasts, effects, etc.). */
    val events: SharedFlow<AiEvent> = _events.asSharedFlow()

    // ─────────────────────── Chat persistence ───────────────────────

    /** Chat message sender role. */
    enum class ChatSender { USER, AI }

    /**
     * ViewModel-level representation of a chat bubble.
     *
     * @param id Stable identifier for diffing.
     * @param sender Author of the message.
     * @param text Plain text bubble content for normal messages.
     * @param json Raw JSON content (for final result bubbles).
     * @param isTyping True when this bubble represents a typing indicator.
     */
    data class ChatMsgVm(
        val id: String,
        val sender: ChatSender,
        val text: String? = null,
        val json: String? = null,
        val isTyping: Boolean = false
    )

    private val _chats = MutableStateFlow<Map<String, List<ChatMsgVm>>>(emptyMap())

    /** All chats keyed by nodeId. */
    val chats: StateFlow<Map<String, List<ChatMsgVm>>> = _chats.asStateFlow()

    /**
     * Observe chat list for a specific [nodeId] as a [StateFlow].
     *
     * This keeps UI code minimal:
     * ```kotlin
     * val bubbles by vmAI.chatFlow(node.id).collectAsState()
     * ```
     */
    fun chatFlow(nodeId: String): StateFlow<List<ChatMsgVm>> =
        _chats
            .map { it[nodeId] ?: emptyList() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
                initialValue = emptyList()
            )

    /**
     * Ensure the first AI question bubble is inserted only once for a node.
     */
    fun chatEnsureSeedQuestion(nodeId: String, question: String) {
        val cur = _chats.value[nodeId]
        if (cur.isNullOrEmpty()) {
            chatAppend(
                nodeId,
                ChatMsgVm(id = "q-$nodeId", sender = ChatSender.AI, text = question)
            )
            if (DEBUG_LOGS) Log.d(TAG, "chatEnsureSeedQuestion: seeded for $nodeId")
        }
    }

    /**
     * Append a new chat message for [nodeId].
     */
    fun chatAppend(nodeId: String, msg: ChatMsgVm) {
        updateNode(nodeId) { it + msg }
        if (DEBUG_LOGS) Log.v(TAG, "chatAppend[$nodeId]: ${msg.id}")
    }

    /**
     * Replace existing typing bubble or append if not present.
     */
    fun chatUpsertTyping(nodeId: String, typing: ChatMsgVm) {
        updateNode(nodeId) { list ->
            val i = list.indexOfFirst { it.isTyping }
            if (i >= 0) list.toMutableList().apply { set(i, typing) } else list + typing
        }
    }

    /**
     * Remove any typing bubbles for [nodeId].
     */
    fun chatRemoveTyping(nodeId: String) {
        updateNode(nodeId) { list -> list.filterNot { it.isTyping } }
    }

    /**
     * Replace a typing bubble with [finalMsg], or append if none exists.
     */
    fun chatReplaceTypingWith(nodeId: String, finalMsg: ChatMsgVm) {
        updateNode(nodeId) { list ->
            val i = list.indexOfFirst { it.isTyping }
            if (i >= 0) list.toMutableList().apply { set(i, finalMsg) } else list + finalMsg
        }
    }

    /**
     * Clear chat history for a single [nodeId].
     */
    fun chatClear(nodeId: String) {
        _chats.update { it - nodeId }
        if (DEBUG_LOGS) Log.w(TAG, "chatClear: cleared chat for $nodeId")
    }

    /**
     * Clear chat history for all nodes.
     *
     * Use this when starting a completely fresh survey session so that
     * no previous AI conversation leaks into the new run.
     */
    fun resetChats() {
        _chats.value = emptyMap()
        if (DEBUG_LOGS) Log.w(TAG, "resetChats: cleared all chats")
    }

    private inline fun updateNode(
        nodeId: String,
        xform: (List<ChatMsgVm>) -> List<ChatMsgVm>
    ) {
        _chats.update { map ->
            val cur = map[nodeId] ?: emptyList()
            map + (nodeId to xform(cur))
        }
    }

    // ─────────────────────── Execution control ───────────────────────

    private var evalJob: Job? = null
    private val running = AtomicBoolean(false)

    /**
     * True when an evaluation coroutine is currently running.
     */
    val isRunning: Boolean
        get() = running.get()

    /**
     * Evaluate the given [prompt] and return the parsed score (0..100) or null.
     *
     * This is the legacy single-step evaluation API.
     */
    suspend fun evaluate(prompt: String, timeoutMs: Long = defaultTimeoutMs): Int? {
        if (prompt.isBlank()) {
            Log.i(TAG, "evaluate: blank prompt -> reset states and return null")
            resetStates(keepError = false)
            return null
        }

        val fullPrompt = runCatching { repo.buildPrompt(prompt) }
            .onFailure { t -> Log.e(TAG, "evaluate: buildPrompt failed", t) }
            .getOrElse { prompt }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluate: already running -> returning current score=${_score.value}")
            return _score.value
        }

        evalJob?.cancel()
        evalJob = null

        prepareUiForNewRun()

        val elapsed = measureTimeMillis {
            val job = startSingleStageInternal(
                stageName = "single",
                originalPrompt = prompt,
                fullPrompt = fullPrompt,
                timeoutMs = timeoutMs,
                publishRaw = true,
                parseScore = true,
                parseFollowups = true,
                clearRawAtStart = true,
                emitFinalEvent = true
            )
            evalJob = job
            job.join()
        }

        finalizeRunFlags()

        Log.d(TAG, "evaluate: finished in ${elapsed}ms, score=${_score.value}, err=${_error.value}")
        return _score.value
    }

    /**
     * Fire-and-forget variant of [evaluate].
     *
     * Legacy single-step evaluation API.
     *
     * @return [Job] representing the launched evaluation.
     */
    fun evaluateAsync(prompt: String, timeoutMs: Long = defaultTimeoutMs): Job {
        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return viewModelScope.launch { }
        }

        Log.d(TAG, "prompt: $prompt")

        val fullPrompt = runCatching { repo.buildPrompt(prompt) }
            .onFailure { t -> Log.e(TAG, "evaluateAsync: buildPrompt failed", t) }
            .getOrElse { prompt }

        Log.d(TAG, "fullPrompt: $fullPrompt")

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluateAsync: already running -> returning existing job")
            return evalJob ?: viewModelScope.launch { }
        }

        evalJob?.cancel()
        evalJob = null

        prepareUiForNewRun()

        val job = startSingleStageInternal(
            stageName = "single",
            originalPrompt = prompt,
            fullPrompt = fullPrompt,
            timeoutMs = timeoutMs,
            publishRaw = true,
            parseScore = true,
            parseFollowups = true,
            clearRawAtStart = true,
            emitFinalEvent = true
        )
        evalJob = job

        job.invokeOnCompletion {
            finalizeRunFlags()
        }

        return job
    }

    /**
     * Two-step prompting API (fire-and-forget).
     *
     * Stage 1 (EVAL):
     * - Must return strict JSON containing:
     *   - "score": int 0..100
     *   - "needs_followup": boolean (or tolerant variants)
     * - Publishes [raw] so UI can render JSON bubble.
     *
     * Stage 2 (FOLLOWUP):
     * - Runs only if gating decides it is needed.
     * - Uses [followupPromptTemplate] which should contain {{EVAL_JSON}} placeholder.
     * - Does NOT publish [raw] (to avoid duplicate JSON bubble replacement in UI).
     * - Publishes [followupQuestion].
     *
     * IMPORTANT UI COMPAT NOTE:
     * - We intentionally drop loading=false between stages so existing AiScreen can
     *   finalize the JSON bubble after EVAL.
     * - We clear raw before FOLLOWUP so stage2 completion does not re-trigger
     *   "replace typing with JSON" logic in UI.
     */
    fun evaluateTwoStepAsync(
        evalPrompt: String,
        followupPromptTemplate: String,
        gating: TwoStepGating = TwoStepGating(),
        timeoutMsEval: Long = defaultTimeoutMs,
        timeoutMsFollowup: Long = defaultTimeoutMs
    ): Job {
        if (evalPrompt.isBlank()) {
            resetStates(keepError = false)
            return viewModelScope.launch { }
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluateTwoStepAsync: already running -> returning existing job")
            return evalJob ?: viewModelScope.launch { }
        }

        evalJob?.cancel()
        evalJob = null

        // Prepare run state (EVAL stage).
        prepareUiForNewRun()

        val job = viewModelScope.launch(ioDispatcher) {
            try {
                // Build full prompt for EVAL.
                val fullEval = runCatching { repo.buildPrompt(evalPrompt) }
                    .onFailure { t -> Log.e(TAG, "twoStep: buildPrompt(EVAL) failed", t) }
                    .getOrElse { evalPrompt }

                // ---- Stage 1: EVAL ----
                val evalOutcome = runStageStreaming(
                    stageName = "eval",
                    originalPrompt = evalPrompt,
                    fullPrompt = fullEval,
                    timeoutMs = timeoutMsEval
                )

                val evalRaw = evalOutcome.rawText

                // Publish EVAL output for JSON bubble.
                if (evalRaw.isNotBlank()) {
                    _raw.value = evalRaw
                    _score.value = clampScore(extractScoreFromJson(evalRaw) ?: FollowupExtractor.extractScore(evalRaw))
                    _followups.value = emptyList()
                    _followupQuestion.value = null

                    // Emit Final ONCE for the EVAL stage (preserves legacy semantics).
                    _events.tryEmit(AiEvent.Final(evalRaw, _score.value, emptyList()))
                } else {
                    _raw.value = ""
                    _score.value = null
                    _followups.value = emptyList()
                    _followupQuestion.value = null
                    _events.tryEmit(AiEvent.Final("", null, emptyList()))
                }

                if (evalOutcome.timedOut) {
                    _error.value = "timeout"
                    _events.tryEmit(AiEvent.Timeout)
                    return@launch
                }

                // Drop loading=false so UI can finalize JSON bubble after stage1.
                _loading.value = false
                _stream.value = ""

                // Decide whether to run follow-up.
                val runFollowup = decideFollowupFromEval(evalRaw, gating)

                if (!runFollowup) {
                    return@launch
                }

                // ---- Stage 2: FOLLOWUP ----
                // Clear raw so stage2 completion does not re-trigger JSON bubble replacement in UI.
                _raw.value = null
                _followupQuestion.value = null
                _followups.value = emptyList()

                _loading.value = true

                val injected = injectEvalJson(
                    template = followupPromptTemplate,
                    evalJson = evalRaw
                )

                val fullFollow = runCatching { repo.buildPrompt(injected) }
                    .onFailure { t -> Log.e(TAG, "twoStep: buildPrompt(FOLLOWUP) failed", t) }
                    .getOrElse { injected }

                val followOutcome = runStageStreaming(
                    stageName = "followup",
                    originalPrompt = injected,
                    fullPrompt = fullFollow,
                    timeoutMs = timeoutMsFollowup
                )

                val followRaw = followOutcome.rawText

                val q = extractFollowupQuestion(followRaw)
                if (!q.isNullOrBlank()) {
                    _followupQuestion.value = q
                    _followups.value = listOf(q)
                }

                if (followOutcome.timedOut) {
                    _error.value = "timeout"
                    _events.tryEmit(AiEvent.Timeout)
                }
            } catch (ce: CancellationException) {
                _error.value = "cancelled"
                _events.tryEmit(AiEvent.Cancelled)
                throw ce
            } catch (t: Throwable) {
                val msg = t.message ?: "error"
                _error.value = msg
                _events.tryEmit(AiEvent.Error(msg))
                Log.e(TAG, "evaluateTwoStepAsync: error", t)
            } finally {
                // End of the full 2-step pipeline.
                finalizeRunFlags()
            }
        }

        evalJob = job
        return job
    }

    /**
     * Cancel the ongoing evaluation if any.
     *
     * This is a user-driven cancellation path.
     * - Sets [error] to "cancelled".
     * - Emits [AiEvent.Cancelled].
     * - Clears [loading] and [running] flags.
     */
    fun cancel() {
        Log.i(TAG, "cancel: invoked (isRunning=${running.get()}, loading=${_loading.value})")

        runCatching { evalJob?.cancel() }
            .onFailure { t -> Log.w(TAG, "cancel: exception during cancel (ignored)", t) }

        _error.value = "cancelled"
        _loading.value = false
        running.set(false)
        evalJob = null

        _events.tryEmit(AiEvent.Cancelled)
    }

    /**
     * Reset transient AI-related states while keeping chat history intact.
     *
     * @param keepError True to preserve the last error message.
     */
    fun resetStates(keepError: Boolean = false) {
        cancel()
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()
        if (!keepError) _error.value = null
    }

    /**
     * Reset all AI-related state including chats.
     *
     * Use this when starting a completely new survey run.
     */
    fun resetAll(keepError: Boolean = false) {
        resetStates(keepError = keepError)
        resetChats()
    }

    override fun onCleared() {
        Log.i(TAG, "onCleared: ViewModel is being cleared -> cancel()")
        super.onCleared()
        cancel()
    }

    // ───────────────────────── Internal evaluation core ─────────────────────────

    /**
     * Single-step evaluation runner that matches legacy behavior.
     */
    private fun startSingleStageInternal(
        stageName: String,
        originalPrompt: String,
        fullPrompt: String,
        timeoutMs: Long,
        publishRaw: Boolean,
        parseScore: Boolean,
        parseFollowups: Boolean,
        clearRawAtStart: Boolean,
        emitFinalEvent: Boolean
    ): Job = viewModelScope.launch(ioDispatcher) {
        try {
            val outcome = runStageStreaming(
                stageName = stageName,
                originalPrompt = originalPrompt,
                fullPrompt = fullPrompt,
                timeoutMs = timeoutMs,
                clearRawAtStart = clearRawAtStart,
                clearFollowupsAtStart = true,
                clearScoreAtStart = true
            )

            val rawText = outcome.rawText.ifBlank { _stream.value }

            if (publishRaw) {
                _raw.value = rawText
            }

            if (parseScore) {
                val parsedScore = clampScore(FollowupExtractor.extractScore(rawText))
                _score.value = parsedScore
            }

            if (parseFollowups) {
                val top3 = FollowupExtractor.fromRaw(rawText, max = 3)
                _followups.value = top3
                _followupQuestion.value = top3.firstOrNull()
            }

            if (emitFinalEvent) {
                _events.tryEmit(AiEvent.Final(rawText, _score.value, _followups.value))
            }

            if (outcome.timedOut) {
                _error.value = "timeout"
                _events.tryEmit(AiEvent.Timeout)
            }
        } catch (ce: CancellationException) {
            _error.value = "cancelled"
            _events.tryEmit(AiEvent.Cancelled)
            Log.w(TAG, "startSingleStageInternal: cancelled", ce)
            throw ce
        } catch (t: Throwable) {
            val msg = t.message ?: "error"
            _error.value = msg
            _events.tryEmit(AiEvent.Error(msg))
            Log.e(TAG, "startSingleStageInternal: error", t)
        }
    }

    /**
     * Streaming stage runner (shared by single-step and two-step).
     *
     * - Streams chunks via [repo.request].
     * - Updates [_stream] and emits [AiEvent.Stream].
     * - Applies timeout but still returns best-effort buffer.
     */
    private suspend fun runStageStreaming(
        stageName: String,
        originalPrompt: String,
        fullPrompt: String,
        timeoutMs: Long,
        clearRawAtStart: Boolean = false,
        clearFollowupsAtStart: Boolean = false,
        clearScoreAtStart: Boolean = false
    ): StageOutcome {
        val buf = StringBuilder()
        var chunkCount = 0
        var totalChars = 0
        var timedOut = false

        if (clearRawAtStart) _raw.value = null
        if (clearFollowupsAtStart) {
            _followupQuestion.value = null
            _followups.value = emptyList()
        }
        if (clearScoreAtStart) _score.value = null

        // Ensure loading is true while running this stage (caller may toggle between stages).
        _loading.value = true
        _stream.value = ""

        val elapsed = measureTimeMillis {
            try {
                try {
                    withTimeout(timeoutMs) {
                        repo.request(fullPrompt).collect { part ->
                            if (part.isNotEmpty()) {
                                chunkCount++
                                buf.append(part)
                                totalChars += part.length

                                _stream.update { it + part }
                                _events.tryEmit(AiEvent.Stream(part))
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    timedOut = true
                    Log.w(TAG, "runStage[$stageName]: timeout after ${timeoutMs}ms", e)
                } catch (e: CancellationException) {
                    if (looksLikeTimeout(e)) {
                        timedOut = true
                        Log.w(TAG, "runStage[$stageName]: timeout-like cancellation (${e.javaClass.name})")
                    } else {
                        throw e
                    }
                }
            } catch (ce: CancellationException) {
                Log.w(TAG, "runStage[$stageName]: cancelled", ce)
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "runStage[$stageName]: error", t)
                throw t
            }
        }

        val rawText = buf.toString().ifBlank { _stream.value }

        if (DEBUG_LOGS) {
            Log.d(
                TAG,
                "Stage[$stageName][stats]: elapsed=${elapsed}ms, prompt.len=${originalPrompt.length}, full.len=${fullPrompt.length}, chunks=$chunkCount, chars=$totalChars"
            )
            Log.d(
                TAG,
                "Stage[$stageName][sha]: prompt=${sha256Hex(originalPrompt)}, full=${sha256Hex(fullPrompt)}, raw=${sha256Hex(rawText)}"
            )
        }

        // Caller decides whether to keep loading true or false after this stage.
        // We do not finalize flags here to support multi-stage pipelines.
        _stream.value = ""

        return StageOutcome(
            rawText = rawText,
            timedOut = timedOut
        )
    }

    /**
     * Prepare all UI-visible states for a new evaluation run.
     *
     * This intentionally does not touch chat history.
     */
    private fun prepareUiForNewRun() {
        _loading.value = true
        _score.value = null
        _stream.value = ""
        _followupQuestion.value = null
        _followups.value = emptyList()
        _raw.value = null

        // Preserve recent timeout/cancel badges unless overwritten by a new error.
        if (_error.value != "timeout" && _error.value != "cancelled") {
            _error.value = null
        }
    }

    /**
     * Finalize flags after an evaluation completes.
     *
     * This is idempotent and safe to call multiple times.
     */
    private fun finalizeRunFlags() {
        _loading.value = false
        running.set(false)
        evalJob = null
    }

    // ───────────────────────── two-step helpers ─────────────────────────

    /**
     * Decide whether FOLLOWUP should run based on the EVAL JSON + gating.
     *
     * Supported keys (tolerant):
     * - score / "score"
     * - needs_followup / "needs_followup" / "needs followup" / "needs-followup"
     */
    private fun decideFollowupFromEval(evalRaw: String, gating: TwoStepGating): Boolean {
        val s = clampScore(extractScoreFromJson(evalRaw) ?: FollowupExtractor.extractScore(evalRaw)) ?: 0
        val needs = extractBoolFromJson(evalRaw, "needs_followup")
            ?: extractBoolFromJson(evalRaw, "needs followup")
            ?: extractBoolFromJson(evalRaw, "needs-followup")
            ?: false

        val ok = s >= gating.evalOkScoreThreshold

        if (ok && gating.skipFollowupWhenOk && !needs) return false
        if (!ok && gating.forceFollowupWhenScoreBelowThreshold) return true
        return needs
    }

    /**
     * Inject EVAL JSON into the FOLLOWUP prompt template.
     */
    private fun injectEvalJson(template: String, evalJson: String): String {
        val t = template
        return if (t.contains(EVAL_JSON_PLACEHOLDER)) {
            t.replace(EVAL_JSON_PLACEHOLDER, evalJson.trim())
        } else {
            // Fallback: append at the end so it still works even if placeholder was forgotten.
            buildString {
                append(t.trim())
                append("\n\nEVAL_JSON:\n")
                append(evalJson.trim())
            }
        }
    }

    /**
     * Extract follow-up question from strict JSON, with fallbacks.
     *
     * Supported keys (tolerant):
     * - follow_up_question
     * - follow-up question
     * - followupQuestion
     */
    private fun extractFollowupQuestion(text: String): String? {
        val raw = stripCodeFence(text).trim()
        if (raw.isBlank()) return null

        val fromJson = extractStringFromJson(raw, "follow_up_question")
            ?: extractStringFromJson(raw, "follow-up question")
            ?: extractStringFromJson(raw, "followupQuestion")

        if (!fromJson.isNullOrBlank()) return fromJson.trim()

        // Fallback: treat as plain text.
        return raw.ifBlank { null }
    }

    // ───────────────────────── JSON extraction (lenient regex) ─────────────────────────

    /**
     * Extract integer value for a JSON key.
     *
     * Example matches:
     * - "score": 88
     * - "score": "88"
     */
    private fun extractIntFromJson(text: String, key: String): Int? {
        val t = stripCodeFence(text)
        val regex = Regex("""(?i)"${Regex.escape(key)}"\s*:\s*"?(\d{1,3})"?""")
        val m = regex.find(t) ?: return null
        val v = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        return v.coerceIn(0, 100)
    }

    /**
     * Extract boolean value for a JSON key.
     *
     * Example matches:
     * - "needs_followup": true
     * - "needs_followup": "false"
     */
    private fun extractBoolFromJson(text: String, key: String): Boolean? {
        val t = stripCodeFence(text)
        val regex = Regex("""(?i)"${Regex.escape(key)}"\s*:\s*"?\s*(true|false)\s*"?""")
        val m = regex.find(t) ?: return null
        return when (m.groupValues.getOrNull(1)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /**
     * Extract string value for a JSON key (best-effort, non-escaping).
     *
     * Example match:
     * - "follow_up_question": "Je, ...?"
     */
    private fun extractStringFromJson(text: String, key: String): String? {
        val t = stripCodeFence(text)
        // Non-greedy string capture that tolerates basic escapes.
        val regex = Regex("""(?is)"${Regex.escape(key)}"\s*:\s*"(.*?)"""")
        val m = regex.find(t) ?: return null
        val v = m.groupValues.getOrNull(1) ?: return null
        return v
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .trim()
            .ifBlank { null }
    }

    /**
     * Prefer strict score from EVAL JSON. Falls back to extractor if missing.
     */
    private fun extractScoreFromJson(text: String): Int? =
        extractIntFromJson(text, "score")

    private fun stripCodeFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        val closing = t.indexOf("```", startIndex = 3)
        if (closing == -1) return t
        val newline = t.indexOf('\n', startIndex = 3)
        val contentStart = if (newline in 4 until closing) newline + 1 else 3
        return t.substring(contentStart, closing).trim()
    }

    // ───────────────────────── helpers ─────────────────────────

    private data class StageOutcome(
        val rawText: String,
        val timedOut: Boolean
    )

    /**
     * Clamp score into the expected UI range.
     */
    private fun clampScore(s: Int?): Int? = s?.coerceIn(0, 100)

    /**
     * Heuristic timeout detection for cancellation types that do not surface
     * [TimeoutCancellationException] directly.
     */
    private fun looksLikeTimeout(e: CancellationException): Boolean {
        val n = e.javaClass.name
        val m = e.message ?: ""
        return n.endsWith("TimeoutCancellationException") ||
                n.contains("Timeout", ignoreCase = true) ||
                m.contains("timeout", ignoreCase = true)
    }

    /**
     * Compute SHA-256 hex digest for lightweight debug comparison.
     *
     * This is used only for logging. Do not rely on this for security.
     */
    private fun sha256Hex(input: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }.getOrElse { "sha256_error" }
}

/* ───────────────────────── Events ───────────────────────── */

/**
 * UI-facing events for reactive handling.
 *
 * These events are intentionally compact:
 * - They are suitable for transient UI effects.
 * - They avoid carrying heavyweight objects.
 */
sealed interface AiEvent {

    /**
     * Emitted for each streamed chunk.
     */
    data class Stream(val chunk: String) : AiEvent

    /**
     * Emitted at the end with the best-available final buffer.
     *
     * @param raw Raw text payload accumulated from the model.
     * @param score Parsed score (0..100) or null.
     * @param followups Extracted follow-up questions (up to top-3).
     */
    data class Final(
        val raw: String,
        val score: Int?,
        val followups: List<String>
    ) : AiEvent

    /**
     * Emitted if evaluation was cancelled explicitly.
     */
    data object Cancelled : AiEvent

    /**
     * Emitted if evaluation hit the timeout.
     */
    data object Timeout : AiEvent

    /**
     * Emitted for unexpected errors.
     */
    data class Error(val message: String) : AiEvent
}
