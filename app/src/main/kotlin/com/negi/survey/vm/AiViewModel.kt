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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
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
    }

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
     * Design:
     * - This is a suspend wrapper that:
     *   1) prepares UI state
     *   2) starts a single evaluation job
     *   3) awaits its completion
     *
     * Timeout semantics:
     * - A timeout still attempts to parse whatever was streamed so far.
     * - [AiEvent.Final] is emitted at most once per call.
     * - [AiEvent.Timeout] is emitted in addition when applicable.
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
            val job = startEvaluationInternal(
                originalPrompt = prompt,
                fullPrompt = fullPrompt,
                timeoutMs = timeoutMs
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
     * @return [Job] representing the launched evaluation.
     */
    fun evaluateAsync(prompt: String, timeoutMs: Long = defaultTimeoutMs): Job {
        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return viewModelScope.launch { }
        }

        Log.d("AiViewModel", "prompt: $prompt")

        val fullPrompt = runCatching { repo.buildPrompt(prompt) }
            .onFailure { t -> Log.e(TAG, "evaluateAsync: buildPrompt failed", t) }
            .getOrElse { prompt }

        Log.d("AiViewModel", "fullPrompt: $fullPrompt")

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluateAsync: already running -> returning existing job")
            return evalJob ?: viewModelScope.launch { }
        }

        evalJob?.cancel()
        evalJob = null

        prepareUiForNewRun()

        val job = startEvaluationInternal(
            originalPrompt = prompt,
            fullPrompt = fullPrompt,
            timeoutMs = timeoutMs
        )
        evalJob = job

        job.invokeOnCompletion {
            finalizeRunFlags()
        }

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
     * Starts the evaluation coroutine that:
     * - streams text
     * - finalizes raw output
     * - parses score & follow-ups
     * - emits terminal events
     *
     * This function must be called only after [prepareUiForNewRun]
     * and after [running] has been set to true.
     */
    private fun startEvaluationInternal(
        originalPrompt: String,
        fullPrompt: String,
        timeoutMs: Long
    ): Job = viewModelScope.launch(ioDispatcher) {

        val buf = StringBuilder()
        var chunkCount = 0
        var totalChars = 0
        var timedOut = false

        var finalEmitted = false

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
                Log.w(TAG, "evaluate: timeout after ${timeoutMs}ms", e)
            } catch (e: CancellationException) {
                // Treat only timeout-like cancellations as soft timeouts.
                // For user-driven cancellation, rethrow and let the outer catch handle events.
                if (looksLikeTimeout(e)) {
                    timedOut = true
                    Log.w(TAG, "evaluate: timeout-like cancellation (${e.javaClass.name})")
                } else {
                    throw e
                }
            }

            val rawText = buf.toString().ifBlank { _stream.value }

            if (DEBUG_LOGS) {
                Log.d(
                    TAG,
                    "Evaluate[stats]: prompt.len=${originalPrompt.length}, full.len=${fullPrompt.length}, chunks=$chunkCount, chars=$totalChars"
                )
                Log.d(
                    TAG,
                    "Evaluate[sha]: prompt=${sha256Hex(originalPrompt)}, full=${sha256Hex(fullPrompt)}, raw=${sha256Hex(rawText)}"
                )
            }

            if (rawText.isNotBlank()) {
                val parsedScore = clampScore(FollowupExtractor.extractScore(rawText))
                val top3 = FollowupExtractor.fromRaw(rawText, max = 3)
                val q0 = top3.firstOrNull()

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
                Log.w(TAG, "evaluate: no output produced (stream & buffer empty)")
                _events.tryEmit(AiEvent.Final("", null, emptyList()))
                finalEmitted = true
            }

            if (timedOut) {
                _error.value = "timeout"
                _events.tryEmit(AiEvent.Timeout)
            }
        } catch (e: CancellationException) {
            _error.value = "cancelled"

            if (!finalEmitted) {
                // Best-effort terminal snapshot for UI that expects a final signal.
                _events.tryEmit(AiEvent.Final(_stream.value, _score.value, _followups.value))
            }

            _events.tryEmit(AiEvent.Cancelled)
            Log.w(TAG, "evaluate: cancelled", e)
            throw e
        } catch (t: Throwable) {
            val msg = t.message ?: "error"
            _error.value = msg
            _events.tryEmit(AiEvent.Error(msg))
            Log.e(TAG, "evaluate: error", t)

            if (!finalEmitted) {
                _events.tryEmit(AiEvent.Final(_stream.value, _score.value, _followups.value))
            }
        }
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

    // ───────────────────────── helpers ─────────────────────────

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
