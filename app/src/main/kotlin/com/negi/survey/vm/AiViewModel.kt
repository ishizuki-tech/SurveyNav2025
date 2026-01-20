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

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.survey.BuildConfig
import com.negi.survey.slm.AiRequestMode
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.slm.OutputKeyStyle
import com.negi.survey.slm.Repository
import com.negi.survey.slm.TwoStepOptions
import com.negi.survey.slm.requestWithMode
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * ViewModel dedicated to AI-related operations and chat persistence.
 *
 * Responsibilities:
 * - Evaluate via [Repository] with SingleStep / DoubleStep (2-step).
 * - Stream partial outputs to UI (throttled).
 * - Extract and keep score / follow-up questions (top-3).
 * - Persist chat history per nodeId.
 * - Provide robust timeout/cancel handling.
 *
 * Concurrency model:
 * - At most one evaluation is allowed at a time.
 * - The single-flight guarantee is enforced by [running].
 * - The active evaluation coroutine is tracked by [evalJob] so UI can cancel it.
 *
 * Staleness model:
 * - Each run gets a monotonically increasing runId.
 * - UI state updates are ignored if they come from a stale runId.
 */
class AiViewModel(
    private val repo: Repository,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "AiViewModel"

        // Debug toggles (avoid log spam in release).
        private const val DEBUG_LOGS = BuildConfig.DEBUG
        private const val DEBUG_WHITESPACE = BuildConfig.DEBUG

        // Log only the first N chunks verbosely to avoid log spam.
        private const val DEBUG_CHUNK_LOG_LIMIT = 12

        // Max chars to show in debug previews (avoid huge log lines).
        private const val DEBUG_PREVIEW_CHARS = 240

        private const val DEFAULT_TIMEOUT_MS = 120_000L

        // Stream publishing throttle to reduce UI churn / allocations.
        // Publish stream snapshot at most every N ms OR after N new chars.
        private const val STREAM_PUBLISH_MIN_INTERVAL_MS = 40L
        private const val STREAM_PUBLISH_MIN_NEW_CHARS = 96

        // Max chat messages per node (soft cap to prevent unbounded growth).
        private const val CHAT_MAX_PER_NODE = 200
    }

    // ───────────────────────── UI state ─────────────────────────

    private val _loading = MutableStateFlow(false)

    /** True while an evaluation is in progress. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _score = MutableStateFlow<Int?>(null)

    /** Parsed evaluation score (0..100) or null when unavailable. */
    val score: StateFlow<Int?> = _score.asStateFlow()

    private val _stream = MutableStateFlow("")

    /** Live concatenation of streamed tokens from the model (throttled). */
    val stream: StateFlow<String> = _stream.asStateFlow()

    private val _raw = MutableStateFlow<String?>(null)

    /** Final raw output used for parsing follow-ups and score. */
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

    /** Append a new chat message for [nodeId]. */
    fun chatAppend(nodeId: String, msg: ChatMsgVm) {
        updateNode(nodeId) { list ->
            val next = list + msg
            if (next.size <= CHAT_MAX_PER_NODE) next else next.takeLast(CHAT_MAX_PER_NODE)
        }
        if (DEBUG_LOGS) Log.v(TAG, "chatAppend[$nodeId]: ${msg.id}")
    }

    /** Replace existing typing bubble or append if not present. */
    fun chatUpsertTyping(nodeId: String, typing: ChatMsgVm) {
        updateNode(nodeId) { list ->
            val i = list.indexOfFirst { it.isTyping }
            val next = if (i >= 0) list.toMutableList().apply { set(i, typing) } else list + typing
            if (next.size <= CHAT_MAX_PER_NODE) next else next.takeLast(CHAT_MAX_PER_NODE)
        }
    }

    /** Remove any typing bubbles for [nodeId]. */
    fun chatRemoveTyping(nodeId: String) {
        updateNode(nodeId) { list -> list.filterNot { it.isTyping } }
    }

    /** Replace a typing bubble with [finalMsg], or append if none exists. */
    fun chatReplaceTypingWith(nodeId: String, finalMsg: ChatMsgVm) {
        updateNode(nodeId) { list ->
            val i = list.indexOfFirst { it.isTyping }
            val next = if (i >= 0) list.toMutableList().apply { set(i, finalMsg) } else list + finalMsg
            if (next.size <= CHAT_MAX_PER_NODE) next else next.takeLast(CHAT_MAX_PER_NODE)
        }
    }

    /** Clear chat history for a single [nodeId]. */
    fun chatClear(nodeId: String) {
        _chats.update { it - nodeId }
        if (DEBUG_LOGS) Log.w(TAG, "chatClear: cleared chat for $nodeId")
    }

    /** Clear chat history for all nodes. */
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

    // Run id / staleness guard
    private val runSeq = AtomicLong(0L)
    private val activeRunId = AtomicLong(0L)

    /** True when an evaluation coroutine is currently running. */
    val isRunning: Boolean
        get() = running.get()

    // ─────────────────────── Mode helpers ───────────────────────

    fun defaultTwoStepOptions(): TwoStepOptions {
        return TwoStepOptions(
            evalOkScoreThreshold = 85,
            skipFollowupWhenOk = true,
            followupOnEvalParseError = true,
            outputKeyStyle = OutputKeyStyle.LEGACY,
            emitEvalChunks = false,
            emitFollowupChunks = false,
            emitFinalMergedJson = true,
            followupOverridesAllFields = false,
            includeGatingFieldsInFinal = false,
            includeMetaInFinal = false
        )
    }

    // ─────────────────────── Public API ───────────────────────

    suspend fun evaluate(prompt: String, timeoutMs: Long = defaultTimeoutMs): Int? {
        return evaluateWithMode(
            prompt = prompt,
            mode = AiRequestMode.SingleStep(passThroughStreaming = true),
            timeoutMs = timeoutMs
        )
    }

    suspend fun evaluateTwoStep(
        prompt: String,
        twoStepOptions: TwoStepOptions = defaultTwoStepOptions(),
        timeoutMs: Long = defaultTimeoutMs
    ): Int? {
        return evaluateWithMode(
            prompt = prompt,
            mode = AiRequestMode.DoubleStep(options = twoStepOptions),
            timeoutMs = timeoutMs
        )
    }

    suspend fun evaluateWithMode(
        prompt: String,
        mode: AiRequestMode,
        timeoutMs: Long = defaultTimeoutMs
    ): Int? {
        if (prompt.isBlank()) {
            Log.i(TAG, "evaluateWithMode: blank prompt -> reset states and return null")
            resetStates(keepError = false)
            return null
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluateWithMode: already running -> returning current score=${_score.value}")
            return _score.value
        }

        val runId = runSeq.incrementAndGet()
        activeRunId.set(runId)

        // Cancel any previous job (defensive).
        evalJob?.cancel()
        evalJob = null

        prepareUiForNewRun()

        try {
            val elapsed = measureTimeMillis {
                val job = startEvaluationInternal(
                    runId = runId,
                    originalPrompt = prompt,
                    mode = mode,
                    timeoutMs = timeoutMs
                )
                evalJob = job
                job.join()
            }

            Log.d(TAG, "evaluateWithMode: finished in ${elapsed}ms, score=${_score.value}, err=${_error.value}")
            return _score.value
        } catch (e: CancellationException) {
            // If the caller is cancelled, cancel the underlying run silently.
            if (DEBUG_LOGS) Log.w(TAG, "evaluateWithMode: caller cancelled -> silent cancel of runId=$runId", e)
            cancelInternal(silent = true)
            throw e
        }
    }

    fun evaluateAsync(prompt: String, timeoutMs: Long = defaultTimeoutMs): Job {
        return evaluateAsyncWithMode(
            prompt = prompt,
            mode = AiRequestMode.SingleStep(passThroughStreaming = true),
            timeoutMs = timeoutMs
        )
    }

    fun evaluateTwoStepAsync(
        prompt: String,
        twoStepOptions: TwoStepOptions = defaultTwoStepOptions(),
        timeoutMs: Long = defaultTimeoutMs
    ): Job {
        return evaluateAsyncWithMode(
            prompt = prompt,
            mode = AiRequestMode.DoubleStep(options = twoStepOptions),
            timeoutMs = timeoutMs
        )
    }

    fun evaluateAsyncWithMode(
        prompt: String,
        mode: AiRequestMode,
        timeoutMs: Long = defaultTimeoutMs
    ): Job {
        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return viewModelScope.launch { }
        }

        if (DEBUG_LOGS) {
            Log.d(TAG, "evaluateAsyncWithMode: prompt.len=${prompt.length}, mode=${mode.javaClass.simpleName}")
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluateAsyncWithMode: already running -> returning existing job")
            return evalJob ?: viewModelScope.launch { }
        }

        val runId = runSeq.incrementAndGet()
        activeRunId.set(runId)

        evalJob?.cancel()
        evalJob = null

        prepareUiForNewRun()

        val job = startEvaluationInternal(
            runId = runId,
            originalPrompt = prompt,
            mode = mode,
            timeoutMs = timeoutMs
        )
        evalJob = job
        return job
    }

    /**
     * Cancel the ongoing evaluation if any.
     *
     * This is a user-driven cancellation path:
     * - Immediately updates UI (loading=false, error="cancelled")
     * - Invalidates run id to ignore late emissions
     * - Emits [AiEvent.Cancelled] exactly once from here
     */
    fun cancel() {
        cancelInternal(silent = false)
    }

    /**
     * Reset transient AI-related states while keeping chat history intact.
     *
     * IMPORTANT:
     * This reset must not surface user-visible "cancelled" errors.
     * Use silent cancel for internal resets (screen change, node switch, etc.).
     */
    fun resetStates(keepError: Boolean = false) {
        cancelInternal(silent = true)
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()
        if (!keepError) _error.value = null
    }

    /** Reset all AI-related state including chats. */
    fun resetAll(keepError: Boolean = false) {
        resetStates(keepError = keepError)
        resetChats()
    }

    override fun onCleared() {
        if (DEBUG_LOGS) Log.i(TAG, "onCleared: ViewModel is being cleared -> silent cancel")
        super.onCleared()
        cancelInternal(silent = true)
    }

    // ───────────────────────── Internal evaluation core ─────────────────────────

    private fun startEvaluationInternal(
        runId: Long,
        originalPrompt: String,
        mode: AiRequestMode,
        timeoutMs: Long
    ): Job = viewModelScope.launch(ioDispatcher) {

        fun isActiveRun(): Boolean = activeRunId.get() == runId

        fun requireActiveRun() {
            if (!isActiveRun()) throw CancellationException("stale-run")
        }

        val buf = StringBuilder()
        val eventBuf = StringBuilder()

        var chunkCount = 0
        var totalChars = 0
        var timedOut = false
        var finalEmitted = false

        // Throttle stream publishing
        var lastPublishAt = 0L
        var lastPublishedLen = 0

        fun flushStreamAndEvents(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            val len = buf.length
            val delta = len - lastPublishedLen

            val dueByTime = (now - lastPublishAt) >= STREAM_PUBLISH_MIN_INTERVAL_MS
            val dueByChars = delta >= STREAM_PUBLISH_MIN_NEW_CHARS

            if (force || dueByTime || dueByChars) {
                if (isActiveRun()) {
                    _stream.value = buf.toString()
                }

                // Emit stream events as a batched chunk to reduce UI load.
                if (eventBuf.isNotEmpty()) {
                    _events.tryEmit(AiEvent.Stream(eventBuf.toString()))
                    eventBuf.setLength(0)
                }

                lastPublishAt = now
                lastPublishedLen = len
            }
        }

        val debugFullPrompt: String? =
            if (DEBUG_LOGS) runCatching { repo.buildPrompt(originalPrompt) }.getOrElse { originalPrompt } else null

        if (DEBUG_LOGS) {
            Log.d(
                TAG,
                "run.start: runId=$runId mode=${mode.javaClass.simpleName}, prompt.len=${originalPrompt.length}, " +
                        "dbgFull.len=${debugFullPrompt?.length ?: -1}, timeoutMs=$timeoutMs"
            )
            Log.d(TAG, "run.sha: prompt=${sha256Hex(originalPrompt)}, dbgFull=${debugFullPrompt?.let { sha256Hex(it) } ?: "<off>"}")
        }

        try {
            try {
                withTimeout(timeoutMs) {
                    repo.requestWithMode(
                        userPrompt = originalPrompt,
                        mode = mode
                    ).collect { part ->
                        requireActiveRun()
                        if (part.isEmpty()) return@collect

                        chunkCount++
                        buf.append(part)
                        eventBuf.append(part)
                        totalChars += part.length

                        flushStreamAndEvents(force = false)

                        if (DEBUG_LOGS && DEBUG_WHITESPACE && chunkCount <= DEBUG_CHUNK_LOG_LIMIT) {
                            val head = part.take(12)
                            val tail = part.takeLast(12)
                            Log.d(
                                TAG,
                                "chunk[$chunkCount]: len=${part.length}, " +
                                        "leadWS=${part.firstOrNull()?.isWhitespace() == true}, " +
                                        "tailWS=${part.lastOrNull()?.isWhitespace() == true}, " +
                                        "head='${debugVisible(head)}', tail='${debugVisible(tail)}'"
                            )
                            Log.d(TAG, "chunk[$chunkCount].preview='${debugVisible(preview(part))}'")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                if (DEBUG_LOGS) Log.w(TAG, "evaluate: timeout after ${timeoutMs}ms", e)
            } catch (e: CancellationException) {
                // Stale run should exit quietly with no UI updates.
                if (!isActiveRun()) {
                    if (DEBUG_LOGS) Log.d(TAG, "run.stale: runId=$runId cancelled/invalidated")
                    return@launch
                }

                // Some timeouts arrive as cancellation-like exceptions in certain stacks.
                if (looksLikeTimeout(e)) {
                    timedOut = true
                    if (DEBUG_LOGS) Log.w(TAG, "evaluate: timeout-like cancellation (${e.javaClass.name})")
                } else {
                    throw e
                }
            }

            requireActiveRun()

            // Ensure latest snapshot is visible to UI and parsing code.
            flushStreamAndEvents(force = true)

            val rawText = buf.toString().ifBlank { _stream.value }

            if (DEBUG_LOGS) {
                Log.d(
                    TAG,
                    "Evaluate[stats]: runId=$runId mode=${mode.javaClass.simpleName}, chunks=$chunkCount, " +
                            "chars=$totalChars, raw.len=${rawText.length}"
                )
                Log.d(TAG, "Evaluate[sha]: raw=${sha256Hex(rawText)}")
            }

            if (DEBUG_LOGS && DEBUG_WHITESPACE) {
                Log.d(TAG, "rawVisible='${debugVisible(preview(rawText))}'")
            }

            if (rawText.isNotBlank()) {
                val parsedScore = runCatching { clampScore(FollowupExtractor.extractScore(rawText)) }
                    .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "extractScore failed (non-fatal)", t) }
                    .getOrNull()

                val top3 = runCatching { FollowupExtractor.fromRaw(rawText, max = 3) }
                    .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "extractFollowups failed (non-fatal)", t) }
                    .getOrElse { emptyList() }

                val q0 = top3.firstOrNull()

                if (DEBUG_LOGS && DEBUG_WHITESPACE) {
                    Log.d(TAG, "q0Visible='${debugVisible(preview(q0 ?: ""))}'")
                    Log.d(TAG, "top3.count=${top3.size}, top3[0].len=${q0?.length ?: 0}")
                }

                _raw.value = rawText
                _score.value = parsedScore
                _followups.value = top3
                _followupQuestion.value = q0

                _events.tryEmit(AiEvent.Final(rawText, parsedScore, top3))
                finalEmitted = true

                if (DEBUG_LOGS) {
                    Log.i(TAG, "Score=$parsedScore, FU[0]=${q0 ?: "<none>"} FU[1..]=${top3.drop(1)}")
                }
            } else {
                if (DEBUG_LOGS) Log.w(TAG, "evaluate: no output produced (stream & buffer empty)")
                _raw.value = ""
                _score.value = null
                _followups.value = emptyList()
                _followupQuestion.value = null

                _events.tryEmit(AiEvent.Final("", null, emptyList()))
                finalEmitted = true
            }

            if (timedOut) {
                _error.value = "timeout"
                _events.tryEmit(AiEvent.Timeout)
            }
        } catch (e: CancellationException) {
            // If run was invalidated, stay silent.
            if (!isActiveRun()) {
                if (DEBUG_LOGS) Log.d(TAG, "run.stale: runId=$runId cancelled in outer catch")
                return@launch
            }

            _error.value = "cancelled"
            flushStreamAndEvents(force = true)

            if (!finalEmitted) {
                _events.tryEmit(AiEvent.Final(_stream.value, _score.value, _followups.value))
            }

            // IMPORTANT: Do not emit Cancelled here when cancel() path already emits it.
            // This path covers "unexpected" cancellation (e.g., upstream cancellation without invalidation).
            _events.tryEmit(AiEvent.Cancelled)

            if (DEBUG_LOGS) Log.w(TAG, "evaluate: cancelled", e)
            throw e
        } catch (t: Throwable) {
            if (!isActiveRun()) {
                if (DEBUG_LOGS) Log.d(TAG, "run.stale: runId=$runId error after invalidation: ${t.message}")
                return@launch
            }

            val msg = t.message ?: "error"
            _error.value = msg
            _events.tryEmit(AiEvent.Error(msg))
            Log.e(TAG, "evaluate: error", t)

            flushStreamAndEvents(force = true)
            if (!finalEmitted) {
                _events.tryEmit(AiEvent.Final(_stream.value, _score.value, _followups.value))
            }
        } finally {
            finalizeRunFlags(runId)
        }
    }

    /**
     * Cancel helper used by both user-driven cancel and internal resets.
     *
     * @param silent When true, do not surface "cancelled" error nor emit Cancelled event.
     */
    private fun cancelInternal(silent: Boolean) {
        val job = evalJob
        val wasActive = (job?.isActive == true)

        if (DEBUG_LOGS) {
            Log.i(
                TAG,
                "cancelInternal: silent=$silent (isRunning=${running.get()}, loading=${_loading.value}, jobActive=$wasActive)"
            )
        }

        // Invalidate current run id so late emissions are ignored.
        activeRunId.set(0L)

        runCatching { job?.cancel() }
            .onFailure { t -> Log.w(TAG, "cancelInternal: exception during cancel (ignored)", t) }

        // Make UI reflect cancellation immediately.
        if (!silent) {
            _error.value = "cancelled"
        }
        _loading.value = false
        running.set(false)
        evalJob = null

        // Emit Cancelled exactly once from here for user-driven cancels.
        if (!silent) {
            _events.tryEmit(AiEvent.Cancelled)
        }
    }

    /** Prepare all UI-visible states for a new evaluation run. */
    private fun prepareUiForNewRun() {
        _loading.value = true
        _score.value = null
        _stream.value = ""
        _followupQuestion.value = null
        _followups.value = emptyList()
        _raw.value = null

        // Always clear previous errors at the start of a new run to avoid "sticky" UI states.
        _error.value = null
    }

    /** Finalize flags after an evaluation completes (only if still the active run). */
    private fun finalizeRunFlags(runId: Long) {
        // Only clear if this runId is still current.
        if (activeRunId.get() != runId) return

        _loading.value = false
        running.set(false)
        evalJob = null
        activeRunId.set(0L)
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun clampScore(s: Int?): Int? = s?.coerceIn(0, 100)

    private fun looksLikeTimeout(e: CancellationException): Boolean {
        val n = e.javaClass.name
        val m = e.message ?: ""
        return n.endsWith("TimeoutCancellationException") ||
                n.contains("Timeout", ignoreCase = true) ||
                m.contains("timeout", ignoreCase = true)
    }

    private fun sha256Hex(input: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }.getOrElse { "sha256_error" }

    /** Convert whitespace/newlines/tabs to visible markers for logs. */
    private fun debugVisible(s: String): String {
        if (s.isEmpty()) return ""
        return buildString(s.length) {
            for (ch in s) {
                append(
                    when (ch) {
                        ' ' -> '␠'
                        '\n' -> '↩'
                        '\t' -> '⇥'
                        '\r' -> '␍'
                        else -> ch
                    }
                )
            }
        }
    }

    /** Safe preview for logs (avoid huge lines). */
    private fun preview(s: String): String {
        if (s.isEmpty()) return ""
        val n = min(DEBUG_PREVIEW_CHARS, s.length)
        return s.take(n)
    }
}

/* ───────────────────────── Events ───────────────────────── */

sealed interface AiEvent {

    data class Stream(val chunk: String) : AiEvent

    data class Final(
        val raw: String,
        val score: Int?,
        val followups: List<String>
    ) : AiEvent

    data object Cancelled : AiEvent
    data object Timeout : AiEvent
    data class Error(val message: String) : AiEvent
}
