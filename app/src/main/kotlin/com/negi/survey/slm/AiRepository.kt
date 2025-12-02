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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Repository that streams inference results from an on-device SLM.
 *
 * Responsibilities:
 * - Build a final prompt string based on [SurveyConfig.slm] overrides.
 * - Expose streaming results as [Flow] of token chunks.
 * - Provide robust lifecycle management with finished/onClean flags,
 *   idle-grace and watchdogs.
 * - Perform defensive cleanup (cancel/reset) on abnormal transitions.
 */
interface Repository {

    /**
     * Execute a single streaming inference for [prompt].
     *
     * The returned [Flow] is cold: collection will perform the actual
     * inference. Only one inference is allowed at a time across the
     * process because of the process-wide semaphore gate.
     *
     * Cancellation:
     *  - Cancelling the collector coroutine closes the flow and triggers
     *    best-effort engine cleanup (cancel/resetSession).
     */
    suspend fun request(prompt: String): Flow<String>

    /**
     * Build the full SLM prompt string from a user-level [userPrompt].
     *
     * The prompt is composed from:
     *  - YAML [SurveyConfig.slm] fields (with defaults if missing).
     *  - Conversation markers (user/model turn prefixes and end token).
     *  - The given [userPrompt], or an instruction for empty JSON when blank.
     */
    fun buildPrompt(userPrompt: String): String
}

/**
 * Concrete [Repository] implementation that directly calls an on-device SLM.
 *
 * Characteristics:
 * - Uses a single [model] instance guarded by [globalGate] to avoid
 *   concurrent inferences against the same engine.
 * - Streams partial results via [callbackFlow] and a listener-based SLM API.
 * - Coordinates engine shutdown with:
 *   - A finished flag from the listener.
 *   - An onClean callback when the engine is fully safe to reuse.
 *   - Idle-grace and watchdog timers as a fallback when onClean is late.
 */
class SlmDirectRepository(
    private val model: Model,
    private val config: SurveyConfig
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"

        // ---------- YAML fallback defaults ----------

        private const val DEF_USER_TURN_PREFIX = "<start_of_turn>user"
        private const val DEF_MODEL_TURN_PREFIX = "<start_of_turn>model"
        private const val DEF_TURN_END = "<end_of_turn>"
        private const val DEF_EMPTY_JSON_INSTRUCTION =
            "Respond with an empty JSON object: {}"

        private const val DEF_PREAMBLE =
            "You are a well-known English survey expert. Read the Question and the Answer."
        private const val DEF_KEY_CONTRACT =
            "OUTPUT FORMAT:\n- In English.\n- Keys: \"analysis\", \"expected answer\", \"follow-up questions\" (Exactly 3 in an array), \"score\" (int 1–100)."
        private const val DEF_LENGTH_BUDGET =
            "LENGTH LIMITS:\n- analysis<=60 chars; each follow-up<=80; expected answer<=40."
        private const val DEF_SCORING_RULE =
            "Scoring rule: Judge ONLY content relevance/completeness/accuracy. Do NOT penalize style or formatting."
        private const val DEF_STRICT_OUTPUT =
            "STRICT OUTPUT (NO MARKDOWN):\n- RAW JSON only, ONE LINE.\n- Use COMPACT JSON (no spaces around ':' and ',').\n- No extra text.\n- Entire output<=512 chars."

        // ---------- Concurrency / lifecycle ----------

        /** Process-wide gate: serialize access to the single SLM instance. */
        private val globalGate = Semaphore(1)

        private const val CLEAN_WAIT_MS = 5_000L
        private const val CLEAN_STEP_MS = 500L
        private const val FINISH_WATCHDOG_DEFAULT_MS = 3_000L
        private const val FINISH_WATCHDOG_STEP_MS = 100L
        private const val FINISH_IDLE_GRACE_DEFAULT_MS = 250L

        private const val FINISH_WATCHDOG_MS = FINISH_WATCHDOG_DEFAULT_MS
        private const val FINISH_IDLE_GRACE_MS = FINISH_IDLE_GRACE_DEFAULT_MS
    }

    // -------------------------------------------------------------------------
    // Prompt builder
    // -------------------------------------------------------------------------

    /**
     * Build the final prompt string with YAML-backed overrides from
     * [SurveyConfig.slm].
     *
     * Rules:
     * - When [userPrompt] is blank, fall back to `empty_json_instruction`.
     * - Normalize line breaks and trim trailing newlines.
     * - Skip any blank blocks when joining to avoid duplicate empty lines.
     *
     * Final ordering:
     *   1. preamble
     *   2. key_contract
     *   3. length_budget
     *   4. scoring_rule
     *   5. strict_output
     *   6. user_turn_prefix
     *   7. userPrompt (or empty_json_instruction)
     *   8. turn_end
     *   9. model_turn_prefix
     */
    override fun buildPrompt(userPrompt: String): String {
        val slm = config.slm

        val userTurn  = slm.user_turn_prefix       ?: DEF_USER_TURN_PREFIX
        val modelTurn = slm.model_turn_prefix      ?: DEF_MODEL_TURN_PREFIX
        val turnEnd   = slm.turn_end               ?: DEF_TURN_END
        val emptyJson = slm.empty_json_instruction ?: DEF_EMPTY_JSON_INSTRUCTION

        val preamble     = slm.preamble      ?: DEF_PREAMBLE
        val keyContract  = slm.key_contract  ?: DEF_KEY_CONTRACT
        val lengthBudget = slm.length_budget ?: DEF_LENGTH_BUDGET
        val scoringRule  = slm.scoring_rule  ?: DEF_SCORING_RULE
        val strictOutput = slm.strict_output ?: DEF_STRICT_OUTPUT

        // User prompt body (or explicit fallback for "no content").
        val effective = if (userPrompt.isBlank()) {
            emptyJson
        } else {
            userPrompt.trimIndent().normalize()
        }

        val finalPrompt = compactJoin(
            preamble,
            keyContract,
            lengthBudget,
            scoringRule,
            strictOutput,
            userTurn,
            effective,
            turnEnd,
            modelTurn
        )

        Log.d(
            TAG,
            "buildPrompt: in.len=${userPrompt.length}, out.len=${finalPrompt.length}"
        )
        return finalPrompt
    }

    // -------------------------------------------------------------------------
    // Inference streaming
    // -------------------------------------------------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun request(prompt: String): Flow<String> =
        callbackFlow {
            val out = this

            // Serialize access to the underlying SLM engine.
            globalGate.withPermit {
                Log.d(
                    TAG,
                    "SLM request start: model='${model.name}', prompt.len=${prompt.length}"
                )

                // Anchor scope for watchdog tasks tied to this flow's lifecycle.
                val anchorScope = CoroutineScope(coroutineContext + SupervisorJob())

                // State flags shared between listener, watchdog, and awaitClose.
                val closed       = AtomicBoolean(false)
                val seenFinished = AtomicBoolean(false)
                val seenOnClean  = AtomicBoolean(false)

                fun isBusyNow(): Boolean =
                    runCatching { SLM.isBusy(model) }
                        .onFailure {
                            Log.w(TAG, "SLM.isBusy threw: ${it.message}")
                        }
                        .getOrElse { true } // fall back to "busy" on failures

                fun safeClose(reason: String? = null) {
                    if (closed.compareAndSet(false, true)) {
                        if (!reason.isNullOrBlank()) {
                            Log.d(TAG, "safeClose: $reason")
                        }
                        out.close()
                    }
                }

                try {

                    SLM.cancel(model)
                    SLM.resetSession(model)

                    SLM.runInference(
                        model = model,
                        input = prompt,
                        listener = { partial, finished ->
                            if (partial.isNotEmpty() && !out.isClosedForSend) {
                                val result = out.trySend(partial)
                                if (result.isFailure) {
                                    Log.w(
                                        TAG,
                                        "trySend(partial.len=${partial.length}) failed: " +
                                                result.exceptionOrNull()?.message
                                    )
                                }
                            }

                            if (finished) {
                                seenFinished.set(true)
                                Log.d(TAG, "SLM inference finished (model='${model.name}')")

                                anchorScope.launch {
                                    // Wait until:
                                    //  (a) onClean observed, OR
                                    //  (b) engine stays idle for FINISH_IDLE_GRACE_MS, OR
                                    //  (c) watchdog timeout is reached.
                                    val ok = withTimeoutOrNull(FINISH_WATCHDOG_MS) {
                                        var idleSince = -1L
                                        while (isActive &&
                                            !closed.get() &&
                                            !seenOnClean.get()
                                        ) {
                                            val busy = isBusyNow()
                                            val now =
                                                android.os.SystemClock.elapsedRealtime()

                                            if (!busy) {
                                                if (idleSince < 0) {
                                                    idleSince = now
                                                }
                                                val idleDur = now - idleSince
                                                if (idleDur >= FINISH_IDLE_GRACE_MS) {
                                                    Log.d(
                                                        TAG,
                                                        "finish idle-grace (${idleDur}ms) → safeClose()"
                                                    )
                                                    break
                                                }
                                            } else {
                                                // Reset idle window on renewed activity.
                                                idleSince = -1L
                                            }

                                            kotlinx.coroutines.delay(
                                                FINISH_WATCHDOG_STEP_MS
                                            )
                                        }
                                        true
                                    } != null

                                    if (!closed.get() && !seenOnClean.get()) {
                                        if (ok) {
                                            // Idle-grace path.
                                            safeClose("finished-idle-grace")
                                        } else {
                                            // Watchdog timeout without onClean.
                                            Log.w(
                                                TAG,
                                                "finish watchdog: onClean not observed " +
                                                        "within ${FINISH_WATCHDOG_MS}ms → safeClose()"
                                            )
                                            safeClose("finish-watchdog-timeout")
                                        }
                                    }
                                }
                            }
                        },
                        onClean = {
                            seenOnClean.set(true)
                            Log.d(TAG, "SLM onClean (model='${model.name}')")
                            safeClose("onClean")
                        }
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "SLM.runInference threw: ${t.message}", t)

                    // Close the producer side quickly so collectors can unblock.
                    safeClose("exception")

                    // Engine-level cleanup.
                    SLM.cancel(model)
                    SLM.resetSession(model)

                    // Propagate as cancellation upstream.
                    cancel(CancellationException("SLM.runInference threw", t))
                }

                awaitClose {
                    // Cancel any pending watchdog jobs.
                    anchorScope.cancel(CancellationException("callbackFlow closed"))

                    val finished = seenFinished.get()
                    val cleaned = seenOnClean.get()

                    fun waitCleanOrIdle(tag: String) {
                        val deadline =
                            android.os.SystemClock.elapsedRealtime() + CLEAN_WAIT_MS
                        var loops = 0

                        android.os.SystemClock.sleep(CLEAN_STEP_MS)

                        while (android.os.SystemClock.elapsedRealtime() < deadline) {
                            if (seenOnClean.get()) break
                            if (!isBusyNow()) break
                            android.os.SystemClock.sleep(CLEAN_STEP_MS)
                            loops++
                        }
                        Log.d(
                            TAG,
                            "awaitClose: waitCleanOrIdle[$tag] done (loops=$loops, " +
                                    "cleaned=${seenOnClean.get()}, busy=${isBusyNow()})"
                        )
                    }

                    when {
                        // Normal: onClean observed → wait a little for stable idle.
                        cleaned -> {
                            Log.d(
                                TAG,
                                "awaitClose: onClean observed → wait for idle then release"
                            )
                            waitCleanOrIdle("cleaned")
                        }

                        // Engine still busy: try cancel() first, then idle-grace.
                        isBusyNow() -> {
                            runCatching {
                                Log.d(TAG, "awaitClose: engine BUSY → cancel()")
                                SLM.cancel(model)
                            }.onFailure {
                                Log.w(TAG, "cancel() failed: ${it.message}")
                            }

                            waitCleanOrIdle("after-cancel")

                            if (finished &&
                                !isBusyNow() &&
                                !seenOnClean.get()
                            ) {
                                runCatching {
                                    Log.d(
                                        TAG,
                                        "awaitClose: finished & idle (no onClean) → resetSession()"
                                    )
                                    SLM.resetSession(model)
                                }.onFailure {
                                    Log.w(TAG, "resetSession() failed: ${it.message}")
                                }
                            }
                        }

                        // Finished without onClean, engine already idle: cautious reset.
                        finished -> {
                            runCatching {
                                Log.d(
                                    TAG,
                                    "awaitClose: finished(no onClean) & idle → resetSession()"
                                )
                                SLM.resetSession(model)
                            }.onFailure {
                                Log.w(TAG, "resetSession() failed: ${it.message}")
                            }
                        }

                        // Early-close path: no finish flag; if still busy, try cancel.
                        else -> {
                            if (isBusyNow()) {
                                runCatching {
                                    Log.d(TAG, "awaitClose: early cancel path → cancel()")
                                    SLM.cancel(model)
                                }.onFailure {
                                    Log.w(TAG, "cancel() failed: ${it.message}")
                                }
                                waitCleanOrIdle("early-cancel")
                            }
                        }
                    }
                }
            }
        }
            // BUFFERED is typically enough for token streams.
            .buffer(Channel.BUFFERED)
            // Run callbacks on IO dispatcher to avoid blocking callers.
            .flowOn(Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Normalize CRLF/CR to LF and trim trailing newlines. */
    private fun String.normalize(): String =
        replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')

    /**
     * Join non-blank parts with a single newline separating each block.
     *
     * This helper:
     *  - Applies [normalize] to each input.
     *  - Drops blocks that are entirely blank.
     *  - Avoids trailing newline at the end of the result.
     */
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
