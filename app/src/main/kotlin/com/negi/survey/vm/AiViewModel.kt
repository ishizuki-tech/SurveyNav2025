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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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

/**
 * Optional repository hook for engines that require explicit lifecycle handling
 * to avoid native re-entry crashes (e.g., LiteRT-LM session reuse issues).
 */
interface RepositoryRunHooks {

    /** Called right before the actual request starts (after cooldown). */
    fun onBeforeAiRun(mode: AiRequestMode) {}

    /** Called after the request finishes/cancels/errors (best-effort). */
    fun onAfterAiRun(mode: AiRequestMode) {}
}

/**
 * Optional repository hook for engines that can cooperatively cancel an in-flight run.
 *
 * IMPORTANT:
 * This is the correct place to stop native sessions / GPU kernels / streaming callbacks.
 * ViewModel job cancellation alone might not stop native work if the flow is not cooperative.
 */
interface RepositoryCancelable {
    fun cancelInFlight() {}
}

/**
 * ViewModel dedicated to AI-related operations and chat persistence.
 *
 * Key safety guarantees:
 * - Single-flight: at most one run is considered active at a time.
 * - Run correlation: each run has a monotonically increasing runId.
 * - Staleness guard: late emissions are ignored when runId is stale.
 * - Re-entry safety: timeout/cancel/error paths best-effort stop native work.
 * - Start/Cancel atomicity: eliminates the "running=true but evalJobRef unset" race window.
 */
class AiViewModel(
    private val repo: Repository,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cooldownAfterRunMs: Long = COOLDOWN_AFTER_RUN_MS
) : ViewModel() {

    companion object {
        private const val TAG = "AiViewModel"

        /** Debug toggles (avoid log spam in release). */
        private const val DEBUG_LOGS = BuildConfig.DEBUG
        private const val DEBUG_WHITESPACE = BuildConfig.DEBUG

        /** Log only the first N chunks verbosely to avoid log spam. */
        private const val DEBUG_CHUNK_LOG_LIMIT = 12

        /** Max chars to show in debug previews (avoid huge log lines). */
        private const val DEBUG_PREVIEW_CHARS = 240

        private const val DEFAULT_TIMEOUT_MS = 120_000L

        /**
         * Stream publishing throttle to reduce UI churn / allocations.
         * Publish stream snapshot at most every N ms OR after N new chars.
         */
        private const val STREAM_PUBLISH_MIN_INTERVAL_MS = 40L
        private const val STREAM_PUBLISH_MIN_NEW_CHARS = 96

        /** Max chat messages per node (soft cap to prevent unbounded growth). */
        private const val CHAT_MAX_PER_NODE = 200

        /**
         * Cooldown between runs (helps native engines that crash on immediate re-entry).
         */
        private const val COOLDOWN_AFTER_RUN_MS = 250L

        private const val TYPING_ID_PREFIX = "typing-"
    }

    /* ───────────────────────── UI state ───────────────────────── */

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

    /* ─────────────── Run correlation (Debug & UI gating) ─────────────── */

    private val _activeRunIdFlow = MutableStateFlow(0L)

    /** Current active runId while running, otherwise 0. */
    val activeRunIdFlow: StateFlow<Long> = _activeRunIdFlow.asStateFlow()

    private val _streamRunId = MutableStateFlow(0L)

    /** runId that most recently updated [stream]. */
    val streamRunId: StateFlow<Long> = _streamRunId.asStateFlow()

    private val _rawRunId = MutableStateFlow(0L)

    /** runId that most recently updated [raw]. */
    val rawRunId: StateFlow<Long> = _rawRunId.asStateFlow()

    /* ─────────────────────── Chat persistence ─────────────────────── */

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
     * Cache chat flows per nodeId to avoid creating multiple stateIn flows.
     *
     * IMPORTANT:
     * Use computeIfAbsent on ConcurrentHashMap to avoid rare double-initialization.
     */
    private val chatFlowCache = ConcurrentHashMap<String, StateFlow<List<ChatMsgVm>>>()

    /**
     * Observe chat list for a specific [nodeId] as a [StateFlow].
     */
    fun chatFlow(nodeId: String): StateFlow<List<ChatMsgVm>> =
        chatFlowCache.computeIfAbsent(nodeId) {
            _chats
                .map { it[nodeId] ?: emptyList() }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
                    initialValue = emptyList()
                )
        }

    /**
     * Ensure the first AI question bubble is inserted only once for a node.
     *
     * Behavior:
     * - If missing, insert at the beginning (even if the chat already has messages).
     * - If already present, do nothing.
     */
    fun chatEnsureSeedQuestion(nodeId: String, question: String) {
        val q = question.trim()
        if (q.isBlank()) return

        updateNode(nodeId) { list ->
            val already =
                list.any { it.id == "q-$nodeId" } ||
                        list.any { it.sender == ChatSender.AI && (it.text ?: "").trim() == q }

            if (already) list
            else listOf(ChatMsgVm(id = "q-$nodeId", sender = ChatSender.AI, text = q)) + list
        }

        if (DEBUG_LOGS) Log.d(TAG, "chatEnsureSeedQuestion: ensured for $nodeId")
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
            val i = list.indexOfFirst { it.isTyping || it.id.startsWith(TYPING_ID_PREFIX) }
            val next = if (i >= 0) list.toMutableList().apply { set(i, typing) } else list + typing
            if (next.size <= CHAT_MAX_PER_NODE) next else next.takeLast(CHAT_MAX_PER_NODE)
        }
    }

    /** Remove any typing bubbles for [nodeId]. */
    fun chatRemoveTyping(nodeId: String) {
        updateNode(nodeId) { list ->
            list.filterNot { it.isTyping || it.id.startsWith(TYPING_ID_PREFIX) }
        }
    }

    /** Replace a typing bubble with [finalMsg], or append if none exists. */
    fun chatReplaceTypingWith(nodeId: String, finalMsg: ChatMsgVm) {
        updateNode(nodeId) { list ->
            val i = list.indexOfFirst { it.isTyping || it.id.startsWith(TYPING_ID_PREFIX) }
            val next = if (i >= 0) list.toMutableList().apply { set(i, finalMsg) } else list + finalMsg
            if (next.size <= CHAT_MAX_PER_NODE) next else next.takeLast(CHAT_MAX_PER_NODE)
        }
    }

    /** Clear chat history for a single [nodeId]. */
    fun chatClear(nodeId: String) {
        _chats.update { it - nodeId }
        chatFlowCache.remove(nodeId)
        if (DEBUG_LOGS) Log.w(TAG, "chatClear: cleared chat for $nodeId")
    }

    /** Clear chat history for all nodes. */
    fun resetChats() {
        _chats.value = emptyMap()
        chatFlowCache.clear()
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

    /* ─────────────────────── Execution control ─────────────────────── */

    /**
     * A single lock to serialize start/cancel/reset operations.
     *
     * IMPORTANT:
     * This eliminates the "running=true but evalJobRef unset" race window.
     */
    private val stateLock = Any()

    private val evalJobRef = AtomicReference<Job?>(null)
    private val running = AtomicBoolean(false)

    /** Run id / staleness guard. */
    private val runSeq = AtomicLong(0L)
    private val activeRunId = AtomicLong(0L)

    /** Cooldown guard. */
    private val nextAllowedAt = AtomicLong(0L)

    /** True when an evaluation coroutine is currently running. */
    val isRunning: Boolean
        get() = running.get()

    /** Public handle for run correlation. */
    data class RunHandle(
        val runId: Long,
        val job: Job
    )

    /* ─────────────────────── Mode helpers ─────────────────────── */

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

    /* ─────────────────────── Public API ─────────────────────── */

    /**
     * Start a single-step async run and return a handle containing the runId.
     *
     * NOTE:
     * Use this from UI when you need to correlate raw/stream with a specific request.
     */
    fun startSingleStepAsync(
        prompt: String,
        timeoutMs: Long = defaultTimeoutMs
    ): RunHandle {
        return startAsyncWithMode(prompt, AiRequestMode.SingleStep(passThroughStreaming = true), timeoutMs)
    }

    /**
     * Start a run (single-step or double-step) and return a handle containing the runId.
     */
    fun startAsyncWithMode(
        prompt: String,
        mode: AiRequestMode,
        timeoutMs: Long = defaultTimeoutMs
    ): RunHandle {
        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return RunHandle(runId = 0L, job = viewModelScope.launch { })
        }

        if (DEBUG_LOGS) {
            Log.d(TAG, "startAsyncWithMode: prompt.len=${prompt.length}, mode=${mode.javaClass.simpleName}")
        }

        synchronized(stateLock) {
            if (!running.compareAndSet(false, true)) {
                _events.tryEmit(AiEvent.Busy)
                val existingRunId = activeRunId.get()
                val existingJob = evalJobRef.get()

                if (DEBUG_LOGS) Log.w(TAG, "startAsyncWithMode: busy -> returning existing runId=$existingRunId")

                val job = existingJob ?: viewModelScope.launch(ioDispatcher) {
                    // Best-effort wait if we ever hit an unexpected null window.
                    var spins = 0
                    while (evalJobRef.get() == null && running.get() && spins < 200) {
                        delay(5)
                        spins++
                    }
                    evalJobRef.get()?.join()
                }

                return RunHandle(runId = existingRunId, job = job)
            }

            // If we reached here, we successfully claimed the single-flight.
            val runId = runSeq.incrementAndGet()
            activeRunId.set(runId)
            _activeRunIdFlow.value = runId

            // Cancel any lingering job (should be rare).
            // IMPORTANT: stop native work first to avoid re-entry crashes.
            runCatching { (repo as? RepositoryCancelable)?.cancelInFlight() }
                .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "repo.cancelInFlight failed (ignored)", t) }

            runCatching { evalJobRef.getAndSet(null)?.cancel() }
                .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "previous job cancel failed (ignored)", t) }

            prepareUiForNewRun(runId)

            // CRITICAL FIX:
            // Create LAZY job, install it into evalJobRef, then start() it.
            // This removes the "job finishes before evalJobRef is set" race.
            val job = startEvaluationInternalLazy(runId = runId, originalPrompt = prompt, mode = mode, timeoutMs = timeoutMs)
            evalJobRef.set(job)
            job.start()

            return RunHandle(runId = runId, job = job)
        }
    }

    /**
     * Backward-compatible async entrypoint (single-step).
     */
    fun evaluateAsync(prompt: String, timeoutMs: Long = defaultTimeoutMs): Job {
        return startSingleStepAsync(prompt, timeoutMs).job
    }

    /**
     * Backward-compatible async entrypoint (with mode).
     */
    fun evaluateAsyncWithMode(
        prompt: String,
        mode: AiRequestMode,
        timeoutMs: Long = defaultTimeoutMs
    ): Job {
        return startAsyncWithMode(prompt, mode, timeoutMs).job
    }

    /**
     * Unified async entrypoint for SingleStep / DoubleStep evaluation.
     */
    fun evaluateAsyncAuto(
        prompt: String,
        useTwoStep: Boolean,
        twoStepOptions: TwoStepOptions = defaultTwoStepOptions(),
        timeoutMs: Long = defaultTimeoutMs
    ): Job {
        val mode: AiRequestMode =
            if (useTwoStep) AiRequestMode.DoubleStep(options = twoStepOptions)
            else AiRequestMode.SingleStep(passThroughStreaming = true)

        return startAsyncWithMode(prompt, mode, timeoutMs).job
    }

    /**
     * Unified suspend entrypoint for SingleStep / DoubleStep evaluation.
     *
     * NOTE:
     * If you need runId correlation, use [startAsyncWithMode] + join at the call site.
     */
    suspend fun evaluateWithMode(
        prompt: String,
        mode: AiRequestMode,
        timeoutMs: Long = defaultTimeoutMs
    ): Int? {
        if (prompt.isBlank()) {
            if (DEBUG_LOGS) Log.i(TAG, "evaluateWithMode: blank prompt -> reset states and return null")
            resetStates(keepError = false)
            return null
        }

        // If already running, keep the original semantics: do not queue.
        synchronized(stateLock) {
            if (running.get()) {
                if (DEBUG_LOGS) Log.w(TAG, "evaluateWithMode: already running -> returning current score=${_score.value}")
                _events.tryEmit(AiEvent.Busy)
                return _score.value
            }
        }

        val handle = startAsyncWithMode(prompt, mode, timeoutMs)
        try {
            val elapsed = measureTimeMillis { handle.job.join() }
            if (DEBUG_LOGS) {
                Log.d(TAG, "evaluateWithMode: finished in ${elapsed}ms, score=${_score.value}, err=${_error.value}")
            }
        } catch (e: CancellationException) {
            if (DEBUG_LOGS) Log.w(TAG, "evaluateWithMode: caller cancelled -> silent cancel", e)
            stopInFlightSilently()
            throw e
        }

        return _score.value
    }

    suspend fun evaluate(prompt: String, timeoutMs: Long = defaultTimeoutMs): Int? {
        return evaluateWithMode(prompt, AiRequestMode.SingleStep(passThroughStreaming = true), timeoutMs)
    }

    suspend fun evaluateTwoStep(
        prompt: String,
        twoStepOptions: TwoStepOptions = defaultTwoStepOptions(),
        timeoutMs: Long = defaultTimeoutMs
    ): Int? {
        return evaluateWithMode(prompt, AiRequestMode.DoubleStep(options = twoStepOptions), timeoutMs)
    }

    /**
     * Cancel the ongoing evaluation if any.
     *
     * User-driven cancel semantics:
     * - Immediately updates UI (loading=false, error="cancelled")
     * - Invalidates run id to ignore late emissions
     * - Emits [AiEvent.Cancelled] exactly once from here
     */
    fun cancel() {
        cancelInternal(silent = false)
    }

    /**
     * Stop any in-flight run silently (no "cancelled" surfaced to UI).
     *
     * Use this from UI on navigation / dispose to prevent native engines
     * from continuing work after leaving the screen.
     */
    fun stopInFlightSilently() {
        cancelInternal(silent = true)
    }

    /**
     * Reset transient AI-related states while keeping chat history intact.
     *
     * IMPORTANT:
     * This reset must not surface user-visible "cancelled" errors.
     */
    fun resetStates(keepError: Boolean = false) {
        cancelInternal(silent = true)
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _rawRunId.value = 0L
        _streamRunId.value = 0L
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

    /* ───────────────────────── Internal evaluation core ───────────────────────── */

    /**
     * Create a LAZY evaluation job.
     *
     * IMPORTANT:
     * The caller must set evalJobRef to this job and call start() while holding stateLock.
     * This prevents the rare race where a very fast coroutine completes before evalJobRef
     * is installed, leaving the ViewModel stuck in a running state.
     */
    private fun startEvaluationInternalLazy(
        runId: Long,
        originalPrompt: String,
        mode: AiRequestMode,
        timeoutMs: Long
    ): Job = viewModelScope.launch(
        context = ioDispatcher,
        start = CoroutineStart.LAZY
    ) {

        fun isActiveRun(): Boolean = activeRunId.get() == runId

        fun requireActiveRun() {
            if (!isActiveRun()) throw CancellationException("stale-run")
        }

        suspend fun awaitCooldownIfNeeded() {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val allowAt = nextAllowedAt.get()
                val waitMs = allowAt - now
                if (waitMs <= 0) return
                if (DEBUG_LOGS) Log.w(TAG, "cooldown.wait: ${waitMs}ms (runId=$runId)")
                delay(min(waitMs, 1_000L))
            }
        }

        fun stopNativeBestEffort(tag: String) {
            runCatching { (repo as? RepositoryCancelable)?.cancelInFlight() }
                .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "stopNativeBestEffort[$tag] failed (ignored)", t) }
        }

        val hooks = repo as? RepositoryRunHooks

        val buf = StringBuilder()
        val eventBuf = StringBuilder()

        var chunkCount = 0
        var totalChars = 0
        var timedOut = false
        var finalEmitted = false

        /** Throttle stream publishing. */
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
                    _streamRunId.value = runId

                    if (eventBuf.isNotEmpty()) {
                        _events.tryEmit(AiEvent.Stream(runId = runId, chunk = eventBuf.toString()))
                        eventBuf.setLength(0)
                    }
                } else {
                    // Drop stale stream events aggressively to avoid UI mixing.
                    eventBuf.setLength(0)
                }

                lastPublishAt = now
                lastPublishedLen = len
            }
        }

        if (DEBUG_LOGS) {
            Log.d(
                TAG,
                "run.start: runId=$runId mode=${mode.javaClass.simpleName} prompt.len=${originalPrompt.length} timeoutMs=$timeoutMs"
            )
            Log.d(TAG, "run.sha: prompt=${sha256Hex(originalPrompt)}")
        }

        try {
            awaitCooldownIfNeeded()
            requireActiveRun()

            runCatching { hooks?.onBeforeAiRun(mode) }
                .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "hooks.onBeforeAiRun failed (ignored)", t) }

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
                                "chunk[$chunkCount]: len=${part.length} leadWS=${part.firstOrNull()?.isWhitespace() == true} tailWS=${part.lastOrNull()?.isWhitespace() == true} " +
                                        "head='${debugVisible(head)}' tail='${debugVisible(tail)}'"
                            )
                            Log.d(TAG, "chunk[$chunkCount].preview='${debugVisible(preview(part))}'")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // P0 fix: ensure native is asked to stop on timeout.
                timedOut = true
                if (DEBUG_LOGS) Log.w(TAG, "evaluate: timeout after ${timeoutMs}ms", e)
                stopNativeBestEffort("timeout")
            } catch (e: CancellationException) {
                // If stale, bail early.
                if (!isActiveRun()) {
                    if (DEBUG_LOGS) Log.d(TAG, "run.stale: runId=$runId cancelled/invalidated")
                    return@launch
                }

                // Some engines surface timeout-like cancellations via CancellationException.
                if (looksLikeTimeout(e)) {
                    timedOut = true
                    if (DEBUG_LOGS) Log.w(TAG, "evaluate: timeout-like cancellation (${e.javaClass.name})")
                    stopNativeBestEffort("timeout-like")
                } else {
                    // User-driven cancel or upstream cancellation.
                    stopNativeBestEffort("cancel")
                    throw e
                }
            } finally {
                runCatching { hooks?.onAfterAiRun(mode) }
                    .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "hooks.onAfterAiRun failed (ignored)", t) }
            }

            requireActiveRun()

            flushStreamAndEvents(force = true)

            val rawText = buf.toString()

            if (DEBUG_LOGS) {
                Log.d(
                    TAG,
                    "Evaluate[stats]: runId=$runId mode=${mode.javaClass.simpleName} chunks=$chunkCount chars=$totalChars raw.len=${rawText.length}"
                )
                Log.d(TAG, "Evaluate[sha]: raw=${sha256Hex(rawText)}")
            }

            if (DEBUG_LOGS && DEBUG_WHITESPACE) {
                Log.d(TAG, "rawVisible='${debugVisible(preview(rawText))}'")
            }

            if (!isActiveRun()) return@launch

            if (rawText.isNotBlank()) {
                val parsedScore = runCatching { clampScore(FollowupExtractor.extractScore(rawText)) }
                    .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "extractScore failed (non-fatal)", t) }
                    .getOrNull()

                val top3 = runCatching { FollowupExtractor.fromRaw(rawText, max = 3) }
                    .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "extractFollowups failed (non-fatal)", t) }
                    .getOrElse { emptyList() }

                val q0 = top3.firstOrNull()

                _raw.value = rawText
                _rawRunId.value = runId

                _score.value = parsedScore
                _followups.value = top3
                _followupQuestion.value = q0

                _events.tryEmit(AiEvent.Final(runId = runId, raw = rawText, score = parsedScore, followups = top3))
                finalEmitted = true

                if (DEBUG_LOGS) {
                    Log.i(TAG, "Final[runId=$runId] score=$parsedScore FU[0]=${q0 ?: "<none>"} FU[1..]=${top3.drop(1)}")
                }
            } else {
                if (DEBUG_LOGS) Log.w(TAG, "evaluate: no output produced (buffer empty)")
                _raw.value = ""
                _rawRunId.value = runId

                _score.value = null
                _followups.value = emptyList()
                _followupQuestion.value = null

                _events.tryEmit(AiEvent.Final(runId = runId, raw = "", score = null, followups = emptyList()))
                finalEmitted = true
            }

            if (timedOut && isActiveRun()) {
                _error.value = "timeout"
                _events.tryEmit(AiEvent.Timeout(runId = runId))
            }
        } catch (e: CancellationException) {
            if (!isActiveRun()) {
                if (DEBUG_LOGS) Log.d(TAG, "run.stale: runId=$runId cancelled in outer catch")
                return@launch
            }

            // IMPORTANT:
            // Do NOT emit AiEvent.Cancelled here to avoid double emission.
            // User-driven cancel() is the only path that emits Cancelled.
            flushStreamAndEvents(force = true)

            if (!finalEmitted && isActiveRun()) {
                _events.tryEmit(
                    AiEvent.Final(
                        runId = runId,
                        raw = _stream.value,
                        score = _score.value,
                        followups = _followups.value
                    )
                )
            }

            if (DEBUG_LOGS) Log.w(TAG, "evaluate: cancelled (no Cancelled event emitted here)", e)
            throw e
        } catch (t: Throwable) {
            // P0 fix: best-effort stop native on unexpected errors.
            stopNativeBestEffort("error")

            if (!isActiveRun()) {
                if (DEBUG_LOGS) Log.d(TAG, "run.stale: runId=$runId error after invalidation: ${t.message}")
                return@launch
            }

            val msg = t.message ?: "error"
            _error.value = msg
            _events.tryEmit(AiEvent.Error(runId = runId, message = msg))
            Log.e(TAG, "evaluate: error", t)

            flushStreamAndEvents(force = true)
            if (!finalEmitted && isActiveRun()) {
                _events.tryEmit(
                    AiEvent.Final(
                        runId = runId,
                        raw = _stream.value,
                        score = _score.value,
                        followups = _followups.value
                    )
                )
            }
        } finally {
            // Always apply cooldown after a run attempt (even if cancelled/errored).
            nextAllowedAt.set(SystemClock.elapsedRealtime() + cooldownAfterRunMs)
            endRunIfOwned(runId = runId, job = this.coroutineContext[Job])
        }
    }

    /**
     * Cancel helper used by both user-driven cancel and internal resets.
     *
     * @param silent When true, do not surface "cancelled" error nor emit Cancelled event.
     */
    private fun cancelInternal(silent: Boolean) {
        val jobToCancel: Job?
        val hadActive: Boolean

        synchronized(stateLock) {
            hadActive = running.get()
            jobToCancel = evalJobRef.getAndSet(null)

            if (DEBUG_LOGS) {
                Log.i(
                    TAG,
                    "cancelInternal: silent=$silent hadActive=$hadActive jobActive=${jobToCancel?.isActive == true} activeRunId=${activeRunId.get()}"
                )
            }

            // P0 fix: stop native work BEFORE invalidating state to reduce re-entry races.
            runCatching { (repo as? RepositoryCancelable)?.cancelInFlight() }
                .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "repo.cancelInFlight failed (ignored)", t) }

            // Invalidate current run id so late emissions are ignored.
            activeRunId.set(0L)
            _activeRunIdFlow.value = 0L

            // Apply cooldown even after cancel/reset to reduce immediate re-entry risk.
            nextAllowedAt.set(SystemClock.elapsedRealtime() + cooldownAfterRunMs)

            // Make UI reflect cancellation immediately.
            if (!silent) _error.value = "cancelled"
            _loading.value = false
            running.set(false)

            // Clear run correlation to avoid UI mixing.
            _streamRunId.value = 0L
        }

        runCatching { jobToCancel?.cancel() }
            .onFailure { t -> if (DEBUG_LOGS) Log.w(TAG, "cancelInternal: exception during cancel (ignored)", t) }

        // Emit Cancelled exactly once from here for user-driven cancels.
        if (!silent && hadActive) _events.tryEmit(AiEvent.Cancelled)
    }

    /**
     * Prepare all UI-visible states for a new evaluation run.
     */
    private fun prepareUiForNewRun(runId: Long) {
        _loading.value = true
        _score.value = null
        _stream.value = ""
        _streamRunId.value = runId
        _raw.value = null
        _rawRunId.value = 0L
        _followupQuestion.value = null
        _followups.value = emptyList()

        // Clear previous errors at the start of a new run to avoid sticky UI states.
        _error.value = null
    }

    /**
     * Best-effort end-of-run cleanup.
     *
     * IMPORTANT:
     * Only clear flags if this run still owns the active slot.
     * This prevents older runs from clobbering newer run state.
     */
    private fun endRunIfOwned(runId: Long, job: Job?) {
        synchronized(stateLock) {
            val stillActive = activeRunId.get() == runId
            val sameJob = job != null && evalJobRef.get() === job

            if (!stillActive || !sameJob) return

            _loading.value = false
            running.set(false)
            evalJobRef.set(null)
            activeRunId.set(0L)
            _activeRunIdFlow.value = 0L
        }
    }

    /* ───────────────────────── helpers ───────────────────────── */

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

    data class Stream(
        val runId: Long,
        val chunk: String
    ) : AiEvent

    data class Final(
        val runId: Long,
        val raw: String,
        val score: Int?,
        val followups: List<String>
    ) : AiEvent

    /** Emitted when an evaluation request is made while another run is active. */
    data object Busy : AiEvent

    data object Cancelled : AiEvent

    data class Timeout(val runId: Long) : AiEvent

    data class Error(
        val runId: Long,
        val message: String
    ) : AiEvent
}
