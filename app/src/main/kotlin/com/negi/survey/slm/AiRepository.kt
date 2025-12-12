/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiRepository.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.slm

import android.util.Log
import com.negi.survey.config.SurveyConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Repository that streams inference results from an on-device LLM backend.
 *
 * Implementations:
 *  - [SlmDirectRepository]: MediaPipe SLM (LlmInference / LlmInferenceSession).
 *  - [LiteRtRepository]: LiteRtLM (Gemma *.litertlm via LiteRtLM object).
 */
interface Repository {

    /**
     * Execute a single streaming inference for the given [prompt].
     *
     * Contract:
     * - Returns a cold [Flow]. Collection actually runs the inference.
     * - Implementations may enforce process-wide serialization (e.g., via a semaphore).
     * - Callers are expected to collect in a coroutine scope they control and cancel
     *   collection to abort/cleanup the underlying engine call (best-effort).
     */
    suspend fun request(prompt: String): Flow<String>

    /**
     * Build the full model-ready prompt string from a user-level [userPrompt].
     *
     * Implementations are responsible for:
     * - Applying YAML-based SLM metadata (preamble, key contract, etc.).
     * - Inserting user/model turn markers and a turn-end token.
     * - Handling the "empty prompt" case by emitting a valid JSON instruction.
     */
    fun buildPrompt(userPrompt: String): String
}

/* ====================================================================== */
/*  Shared process-wide inference gate                                    */
/* ====================================================================== */

/**
 * Single process-wide gate used by both MediaPipe SLM and LiteRtLM backends.
 *
 * Rationale:
 * - Avoid running two heavy LLM engines (e.g., SLM .task + LiteRT .litertlm)
 *   at the same time in the same process.
 * - Greatly reduces peak memory and GPU usage on constrained devices.
 *
 * Semantics:
 * - At most one active inference flow (SLM or LiteRtLM) may run at once.
 * - The gate is held for the entire lifetime of the streaming [Flow] collected
 *   from [Repository.request].
 */
private val AI_INFERENCE_GATE = Semaphore(1)

/* ====================================================================== */
/*  SLM (MediaPipe) backend                                               */
/* ====================================================================== */

/**
 * Concrete [Repository] implementation that directly calls an on-device SLM.
 *
 * Backend:
 *  - Uses [SLM] singleton (MediaPipe LlmInference / LlmInferenceSession).
 *
 * Characteristics:
 *  - Uses a single [model] instance guarded by a process-wide [AI_INFERENCE_GATE]
 *    to avoid concurrent inferences across all LLM backends.
 *  - Streams partial results via [callbackFlow] and a listener-based SLM API.
 *  - Coordinates engine shutdown with finished/onClean + watchdogs.
 */
class SlmDirectRepository(
    private val model: Model,
    private val config: SurveyConfig,
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"

        // ---------------------------------------------------------------------
        // YAML fallback defaults
        // ---------------------------------------------------------------------

        private const val DEF_USER_TURN_PREFIX = "<start_of_turn>user"
        private const val DEF_MODEL_TURN_PREFIX = "<start_of_turn>model"
        private const val DEF_TURN_END = "<end_of_turn>"
        private const val DEF_EMPTY_JSON_INSTRUCTION = "Respond with an empty JSON object: {}"

        private const val DEF_PREAMBLE =
            "You are a well-known farmer survey expert. Read the Question and the Answer."

        /**
         * Default key contract aligned with the current UI pipeline:
         * - AiScreen preview/score extraction expects "follow-up question".
         */
        private const val DEF_KEY_CONTRACT =
            "OUTPUT FORMAT:\n" +
                    "- In English.\n" +
                    "- Keys:\n" +
                    "  • \"analysis\": short string\n" +
                    "  • \"expected answer\": short string\n" +
                    "  • \"follow-up question\": a single short confirm/validate question\n" +
                    "  • \"score\": integer 1–100\n" +
                    "FOLLOW-UP INTENT:\n" +
                    "- The follow-up must confirm or clarify the respondent's original answer to the SAME question.\n" +
                    "- Target the biggest uncertainty (unit/scale, missing number, time window, baseline, method).\n" +
                    "- Keep it single-scope and answerable immediately."

        private const val DEF_LENGTH_BUDGET =
            "LENGTH LIMITS:\n" +
                    "- analysis<=80 chars\n" +
                    "- expected answer<=60 chars\n" +
                    "- follow-up question<=90 chars"

        private const val DEF_SCORING_RULE =
            "SCORING RULE:\n" +
                    "- Judge ONLY content relevance/completeness/accuracy.\n" +
                    "- Do NOT penalize style or formatting."

        private const val DEF_STRICT_OUTPUT =
            "STRICT OUTPUT (NO MARKDOWN):\n" +
                    "- RAW JSON only.\n" +
                    "- No extra text.\n" +
                    "- Prefer compact JSON.\n" +
                    "- Entire output should be short and machine-parseable."

        // ---------------------------------------------------------------------
        // Concurrency / lifecycle
        // ---------------------------------------------------------------------

        private const val CLEAN_WAIT_MS = 5_000L
        private const val CLEAN_STEP_MS = 500L

        private const val FINISH_WATCHDOG_DEFAULT_MS = 3_000L
        private const val FINISH_WATCHDOG_STEP_MS = 100L
        private const val FINISH_IDLE_GRACE_DEFAULT_MS = 250L

        private const val FINISH_WATCHDOG_MS = FINISH_WATCHDOG_DEFAULT_MS
        private const val FINISH_IDLE_GRACE_MS = FINISH_IDLE_GRACE_DEFAULT_MS

        // ---------------------------------------------------------------------
        // Absolute safety watchdogs
        // ---------------------------------------------------------------------

        /** Hard limit for a single inference to avoid permanent gate locks. */
        private const val HARD_WATCHDOG_MS = 20_000L

        /** Close the flow if no progress callback is observed within this window. */
        private const val PROGRESS_STALL_MS = 6_000L

        private const val PROGRESS_POLL_MS = 250L

        // ---------------------------------------------------------------------
        // Logging
        // ---------------------------------------------------------------------

        /** Prevent logcat blow-ups and accidental data leakage. */
        private const val PROMPT_LOG_MAX_CHARS = 1_800
    }

    // -------------------------------------------------------------------------
    // Prompt builder (shared semantics with LiteRtRepository)
    // -------------------------------------------------------------------------

    override fun buildPrompt(userPrompt: String): String {
        val slm = config.slm

        val userTurn = slm.user_turn_prefix ?: DEF_USER_TURN_PREFIX
        val modelTurn = slm.model_turn_prefix ?: DEF_MODEL_TURN_PREFIX
        val turnEnd = slm.turn_end ?: DEF_TURN_END
        val emptyJson = slm.empty_json_instruction ?: DEF_EMPTY_JSON_INSTRUCTION

        val preamble = slm.preamble ?: DEF_PREAMBLE
        val keyContract = slm.key_contract ?: DEF_KEY_CONTRACT
        val lengthBudget = slm.length_budget ?: DEF_LENGTH_BUDGET
        val scoringRule = slm.scoring_rule ?: DEF_SCORING_RULE
        val strictOutput = slm.strict_output ?: DEF_STRICT_OUTPUT

        val effective = if (userPrompt.isBlank()) {
            emptyJson
        } else {
            userPrompt.trimIndent().normalize()
        }

        val userBlock = compactJoin(
            preamble,
            keyContract,
            lengthBudget,
            scoringRule,
            strictOutput,
            effective,
        )

        val finalPrompt = compactJoin(
            userTurn,
            userBlock,
            turnEnd,
            modelTurn,
        )

        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${finalPrompt.length}")
        return finalPrompt
    }

    // -------------------------------------------------------------------------
    // Inference streaming (SLM backend)
    // -------------------------------------------------------------------------

    override suspend fun request(prompt: String): Flow<String> =
        callbackFlow {
            val out = this

            AI_INFERENCE_GATE.withPermit {
                Log.d(TAG, "SLM request start: model='${model.name}', prompt.len=${prompt.length}")

                val anchorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

                val closed = AtomicBoolean(false)
                val seenFinished = AtomicBoolean(false)
                val seenOnClean = AtomicBoolean(false)
                val finishWatchdogStarted = AtomicBoolean(false)

                val startAt = AtomicLong(android.os.SystemClock.elapsedRealtime())
                val lastProgressAt = AtomicLong(startAt.get())

                fun markProgress() {
                    lastProgressAt.set(android.os.SystemClock.elapsedRealtime())
                }

                fun isBusyNow(): Boolean {
                    return runCatching { SLM.isBusy(model) }
                        .onFailure { Log.w(TAG, "SLM.isBusy threw: ${it.message}", it) }
                        /**
                         * Assume busy on failure to avoid unsafe overlap, but watchdogs will release.
                         */
                        .getOrElse { true }
                }

                fun safeClose(reason: String? = null, cause: Throwable? = null) {
                    if (closed.compareAndSet(false, true)) {
                        if (!reason.isNullOrBlank()) {
                            if (cause != null) {
                                Log.w(TAG, "safeClose: $reason", cause)
                            } else {
                                Log.d(TAG, "safeClose: $reason")
                            }
                        }
                        out.close(cause)
                    }
                }

                anchorScope.launch {
                    while (isActive && !closed.get()) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val elapsed = now - startAt.get()
                        val stalled = now - lastProgressAt.get()

                        if (elapsed >= HARD_WATCHDOG_MS) {
                            Log.w(TAG, "hard watchdog timeout (${elapsed}ms) → cancel/reset/close")
                            runCatching { SLM.cancel(model) }
                            runCatching { SLM.resetSession(model) }
                            safeClose("hard-watchdog-timeout")
                            break
                        }

                        if (!seenFinished.get() && stalled >= PROGRESS_STALL_MS) {
                            Log.w(TAG, "progress stall (${stalled}ms) → cancel/reset/close")
                            runCatching { SLM.cancel(model) }
                            runCatching { SLM.resetSession(model) }
                            safeClose("progress-stall-timeout")
                            break
                        }

                        delay(PROGRESS_POLL_MS)
                    }
                }

                try {
                    if (isBusyNow()) {
                        Log.w(TAG, "pre-run: engine reported BUSY → cancel/resetSession")
                        runCatching { SLM.cancel(model) }
                        runCatching { SLM.resetSession(model) }
                    }

                    val normalizedPrompt = prompt.normalize()
                    Log.d(
                        TAG,
                        "SLM prompt preview =\n${normalizedPrompt.take(PROMPT_LOG_MAX_CHARS)}" +
                                if (normalizedPrompt.length > PROMPT_LOG_MAX_CHARS) "\n... (truncated)" else "",
                    )

                    SLM.runInference(
                        model = model,
                        input = normalizedPrompt,
                        listener = { partial, finished ->
                            markProgress()

                            if (partial.isNotEmpty() && !out.isClosedForSend) {
                                val result = out.trySend(partial)
                                if (result.isFailure) {
                                    Log.w(
                                        TAG,
                                        "trySend(partial.len=${partial.length}) failed: ${result.exceptionOrNull()?.message}",
                                        result.exceptionOrNull(),
                                    )
                                }
                            }

                            if (finished) {
                                seenFinished.set(true)
                                if (!finishWatchdogStarted.compareAndSet(false, true)) return@runInference

                                Log.d(TAG, "SLM inference finished (model='${model.name}')")

                                anchorScope.launch {
                                    val ok = withTimeoutOrNull(FINISH_WATCHDOG_MS) {
                                        var idleSince = -1L

                                        while (isActive && !closed.get() && !seenOnClean.get()) {
                                            val busy = isBusyNow()
                                            val now = android.os.SystemClock.elapsedRealtime()

                                            if (!busy) {
                                                if (idleSince < 0) idleSince = now
                                                val idleDur = now - idleSince
                                                if (idleDur >= FINISH_IDLE_GRACE_MS) {
                                                    Log.d(TAG, "finish idle-grace (${idleDur}ms) → safeClose()")
                                                    break
                                                }
                                            } else {
                                                idleSince = -1L
                                            }

                                            delay(FINISH_WATCHDOG_STEP_MS)
                                        }
                                        true
                                    } != null

                                    if (!closed.get() && !seenOnClean.get()) {
                                        if (ok) {
                                            safeClose("finished-idle-grace")
                                        } else {
                                            Log.w(
                                                TAG,
                                                "finish watchdog: onClean not observed within ${FINISH_WATCHDOG_MS}ms → safeClose()",
                                            )
                                            safeClose("finish-watchdog-timeout")
                                        }
                                    }
                                }
                            }
                        },
                        onClean = {
                            markProgress()
                            seenOnClean.set(true)
                            Log.d(TAG, "SLM onClean (model='${model.name}')")
                            safeClose("onClean")
                        },
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "SLM.runInference threw: ${t.message}", t)
                    safeClose("exception", t)
                    runCatching { SLM.cancel(model) }
                    runCatching { SLM.resetSession(model) }
                }

                awaitClose {
                    anchorScope.cancel(CancellationException("callbackFlow closed"))

                    val finished = seenFinished.get()
                    val cleaned = seenOnClean.get()

                    fun waitCleanOrIdle(tag: String) {
                        val deadline = android.os.SystemClock.elapsedRealtime() + CLEAN_WAIT_MS
                        var loops = 0

                        /**
                         * Blocking waits are acceptable here because:
                         * - The entire flow is already serialized by AI_INFERENCE_GATE.
                         * - The upstream runs on Dispatchers.IO via flowOn.
                         */
                        android.os.SystemClock.sleep(CLEAN_STEP_MS)

                        while (android.os.SystemClock.elapsedRealtime() < deadline) {
                            if (seenOnClean.get()) break
                            if (!isBusyNow()) break
                            android.os.SystemClock.sleep(CLEAN_STEP_MS)
                            loops++
                        }

                        Log.d(
                            TAG,
                            "awaitClose: waitCleanOrIdle[$tag] done (loops=$loops, cleaned=${seenOnClean.get()}, busy=${isBusyNow()})",
                        )
                    }

                    when {
                        cleaned -> {
                            Log.d(TAG, "awaitClose: onClean observed → wait for idle then return")
                            waitCleanOrIdle("cleaned")
                        }

                        isBusyNow() -> {
                            runCatching {
                                Log.d(TAG, "awaitClose: engine BUSY → cancel()")
                                SLM.cancel(model)
                            }.onFailure {
                                Log.w(TAG, "cancel() failed: ${it.message}", it)
                            }

                            waitCleanOrIdle("after-cancel")

                            if (finished && !isBusyNow() && !seenOnClean.get()) {
                                runCatching {
                                    Log.d(TAG, "awaitClose: finished & idle (no onClean) → resetSession()")
                                    SLM.resetSession(model)
                                }.onFailure {
                                    Log.w(TAG, "resetSession() failed: ${it.message}", it)
                                }
                            }
                        }

                        finished -> {
                            runCatching {
                                Log.d(TAG, "awaitClose: finished(no onClean) & idle → resetSession()")
                                SLM.resetSession(model)
                            }.onFailure {
                                Log.w(TAG, "resetSession() failed: ${it.message}", it)
                            }
                        }

                        else -> {
                            if (isBusyNow()) {
                                runCatching {
                                    Log.d(TAG, "awaitClose: early cancel path → cancel()")
                                    SLM.cancel(model)
                                }.onFailure {
                                    Log.w(TAG, "cancel() failed: ${it.message}", it)
                                }
                                waitCleanOrIdle("early-cancel")
                            }
                        }
                    }
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Utilities shared with LiteRtRepository
    // -------------------------------------------------------------------------

    private fun String.normalize(): String =
        replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')

    private fun compactJoin(vararg parts: String): String {
        val list = buildList {
            parts.forEach { p ->
                val t = p.normalize()
                if (t.isNotBlank()) add(t)
            }
        }
        return list.joinToString("\n")
    }
}

/* ====================================================================== */
/*  LiteRtLM backend                                                      */
/* ====================================================================== */

/**
 * Concrete [Repository] implementation backed by LiteRtLM (Gemma *.litertlm).
 *
 * Backend:
 *  - Uses [LiteRtLM] singleton (LiteRT LM Engine / Conversation).
 *
 * Characteristics:
 *  - Uses the same process-wide [AI_INFERENCE_GATE] as [SlmDirectRepository].
 *  - Streams partial results via [callbackFlow] and LiteRtLM callback API.
 *  - Uses watchdogs for "hang" and "no progress" failure modes.
 *
 * Cancellation behavior:
 *  - LiteRtLM does not provide a true "cancel" API; on collector cancellation,
 *    this repo optionally triggers best-effort [LiteRtLM.cleanUp] if the inference
 *    did not finish yet, to avoid background compute/memory pressure.
 */
class LiteRtRepository(
    private val model: Model,
    private val config: SurveyConfig,
) : Repository {

    companion object {
        private const val TAG = "LiteRtRepository"

        // YAML fallback defaults (duplicated for clarity / decoupling).
        private const val DEF_USER_TURN_PREFIX = "<start_of_turn>user"
        private const val DEF_MODEL_TURN_PREFIX = "<start_of_turn>model"
        private const val DEF_TURN_END = "<end_of_turn>"
        private const val DEF_EMPTY_JSON_INSTRUCTION = "Respond with an empty JSON object: {}"

        private const val DEF_PREAMBLE =
            "You are a well-known farmer survey expert. Read the Question and the Answer."

        private const val DEF_KEY_CONTRACT =
            "OUTPUT FORMAT:\n" +
                    "- In English.\n" +
                    "- Keys:\n" +
                    "  • \"analysis\": short string\n" +
                    "  • \"expected answer\": short string\n" +
                    "  • \"follow-up question\": a single short confirm/validate question\n" +
                    "  • \"score\": integer 1–100\n" +
                    "FOLLOW-UP INTENT:\n" +
                    "- The follow-up must confirm or clarify the respondent's original answer to the SAME question.\n" +
                    "- Target the biggest uncertainty (unit/scale, missing number, time window, baseline, method).\n" +
                    "- Keep it single-scope and answerable immediately."

        private const val DEF_LENGTH_BUDGET =
            "LENGTH LIMITS:\n" +
                    "- analysis<=80 chars\n" +
                    "- expected answer<=60 chars\n" +
                    "- follow-up question<=90 chars"

        private const val DEF_SCORING_RULE =
            "SCORING RULE:\n" +
                    "- Judge ONLY content relevance/completeness/accuracy.\n" +
                    "- Do NOT penalize style or formatting."

        private const val DEF_STRICT_OUTPUT =
            "STRICT OUTPUT (NO MARKDOWN):\n" +
                    "- RAW JSON only.\n" +
                    "- No extra text.\n" +
                    "- Prefer compact JSON.\n" +
                    "- Entire output should be short and machine-parseable."

        /** Hard upper bound for a single LiteRtLM inference. */
        private const val HARD_WATCHDOG_MS = 20_000L

        /** Stall watchdog when no progress is observed. */
        private const val PROGRESS_STALL_MS = 6_000L

        private const val PROGRESS_POLL_MS = 250L

        // Logging
        private const val PROMPT_LOG_MAX_CHARS = 1_800
    }

    // -------------------------------------------------------------------------
    // Prompt builder (same semantics as SLM)
    // -------------------------------------------------------------------------

    override fun buildPrompt(userPrompt: String): String {
        val slm = config.slm

        val userTurn = slm.user_turn_prefix ?: DEF_USER_TURN_PREFIX
        val modelTurn = slm.model_turn_prefix ?: DEF_MODEL_TURN_PREFIX
        val turnEnd = slm.turn_end ?: DEF_TURN_END
        val emptyJson = slm.empty_json_instruction ?: DEF_EMPTY_JSON_INSTRUCTION

        val preamble = slm.preamble ?: DEF_PREAMBLE
        val keyContract = slm.key_contract ?: DEF_KEY_CONTRACT
        val lengthBudget = slm.length_budget ?: DEF_LENGTH_BUDGET
        val scoringRule = slm.scoring_rule ?: DEF_SCORING_RULE
        val strictOutput = slm.strict_output ?: DEF_STRICT_OUTPUT

        val effective = if (userPrompt.isBlank()) {
            emptyJson
        } else {
            userPrompt.trimIndent().normalize()
        }

        val userBlock = compactJoin(
            preamble,
            keyContract,
            lengthBudget,
            scoringRule,
            strictOutput,
            effective,
        )

        val finalPrompt = compactJoin(
            userTurn,
            userBlock,
            turnEnd,
            modelTurn,
        )

        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${finalPrompt.length}")
        return finalPrompt
    }

    // -------------------------------------------------------------------------
    // Inference streaming (LiteRtLM backend)
    // -------------------------------------------------------------------------

    override suspend fun request(prompt: String): Flow<String> =
        callbackFlow {
            val out = this

            AI_INFERENCE_GATE.withPermit {
                Log.d(TAG, "LiteRtLM request start: model='${model.name}', prompt.len=${prompt.length}")

                val anchorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

                val closed = AtomicBoolean(false)
                val seenFinished = AtomicBoolean(false)
                val cleanupTriggered = AtomicBoolean(false)

                val startAt = AtomicLong(android.os.SystemClock.elapsedRealtime())
                val lastProgressAt = AtomicLong(startAt.get())

                fun markProgress() {
                    lastProgressAt.set(android.os.SystemClock.elapsedRealtime())
                }

                fun safeClose(reason: String? = null, cause: Throwable? = null) {
                    if (closed.compareAndSet(false, true)) {
                        if (!reason.isNullOrBlank()) {
                            if (cause != null) {
                                Log.w(TAG, "safeClose: $reason", cause)
                            } else {
                                Log.d(TAG, "safeClose: $reason")
                            }
                        }
                        out.close(cause)
                    }
                }

                fun bestEffortCleanUp(tag: String) {
                    if (!cleanupTriggered.compareAndSet(false, true)) return
                    runCatching {
                        LiteRtLM.cleanUp(model) {
                            Log.d(TAG, "LiteRtLM cleaned up ($tag)")
                        }
                    }.onFailure {
                        Log.w(TAG, "LiteRtLM.cleanUp failed ($tag): ${it.message}", it)
                    }
                }

                anchorScope.launch {
                    while (isActive && !closed.get()) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val elapsed = now - startAt.get()
                        val stalled = now - lastProgressAt.get()

                        if (elapsed >= HARD_WATCHDOG_MS) {
                            Log.w(TAG, "LiteRtLM hard watchdog timeout (${elapsed}ms) → cleanUp/close")
                            bestEffortCleanUp("hard-watchdog")
                            safeClose("hard-watchdog-timeout")
                            break
                        }

                        if (!seenFinished.get() && stalled >= PROGRESS_STALL_MS) {
                            Log.w(TAG, "LiteRtLM progress stall (${stalled}ms) → cleanUp/close")
                            bestEffortCleanUp("progress-stall")
                            safeClose("progress-stall-timeout")
                            break
                        }

                        delay(PROGRESS_POLL_MS)
                    }
                }

                try {
                    val normalizedPrompt = prompt.normalize()
                    Log.d(
                        TAG,
                        "LiteRtLM prompt preview =\n${normalizedPrompt.take(PROMPT_LOG_MAX_CHARS)}" +
                                if (normalizedPrompt.length > PROMPT_LOG_MAX_CHARS) "\n... (truncated)" else "",
                    )

                    LiteRtLM.runInference(
                        model = model,
                        input = normalizedPrompt,
                        resultListener = { partial, finished ->
                            markProgress()

                            if (partial.isNotEmpty() && !out.isClosedForSend) {
                                val result = out.trySend(partial)
                                if (result.isFailure) {
                                    Log.w(
                                        TAG,
                                        "trySend(partial.len=${partial.length}) failed: ${result.exceptionOrNull()?.message}",
                                        result.exceptionOrNull(),
                                    )
                                }
                            }

                            if (finished) {
                                seenFinished.set(true)
                                Log.d(TAG, "LiteRtLM inference finished (model='${model.name}')")
                                safeClose("finished")
                            }
                        },
                        cleanUpListener = {
                            markProgress()
                            Log.d(TAG, "LiteRtLM cleanUpListener (model='${model.name}')")
                            safeClose("cleanup-listener")
                        },
                        onError = { message ->
                            markProgress()
                            Log.e(TAG, "LiteRtLM error: $message")
                            safeClose("LiteRtLM.runInference error: $message", RuntimeException(message))
                        },
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "LiteRtLM.runInference threw: ${t.message}", t)
                    safeClose("exception", t)
                    bestEffortCleanUp("exception")
                }

                awaitClose {
                    anchorScope.cancel(CancellationException("callbackFlow closed"))
                    val finished = seenFinished.get()

                    Log.d(
                        TAG,
                        "awaitClose: flow closed (finished=$finished) model='${model.name}'",
                    )

                    /**
                     * If the collector cancelled early, stop background work aggressively.
                     * This may require re-initialization on next request, but avoids
                     * silent background compute/memory pressure.
                     */
                    if (!finished) {
                        bestEffortCleanUp("collector-cancel")
                    }
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Utilities (duplicated for independence)
    // -------------------------------------------------------------------------

    private fun String.normalize(): String =
        replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')

    private fun compactJoin(vararg parts: String): String {
        val list = buildList {
            parts.forEach { p ->
                val t = p.normalize()
                if (t.isNotBlank()) add(t)
            }
        }
        return list.joinToString("\n")
    }
}
