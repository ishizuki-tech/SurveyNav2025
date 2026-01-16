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

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.negi.survey.BuildConfig
import com.negi.survey.config.SurveyConfig
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
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
     */
    fun buildPrompt(userPrompt: String): String
}

/* ====================================================================== */
/*  Shared process-wide inference gate                                    */
/* ====================================================================== */

/**
 * Single process-wide gate used by both MediaPipe SLM and LiteRtLM backends.
 *
 * Semantics:
 * - At most one active inference flow (SLM or LiteRtLM) may run at once.
 * - The gate is held for the entire lifetime of the streaming Flow collection.
 */
private val AI_INFERENCE_GATE = Semaphore(1)

/* ====================================================================== */
/*  Logging / Trace utilities                                              */
/* ====================================================================== */

private object AiTrace {

    private const val TAG = "AiTrace"

    /** Enables verbose prompt/output tracing. */
    private const val ENABLED_DEFAULT: Boolean = BuildConfig.DEBUG

    /** Max chars kept in-memory for full output capture (safety cap). */
    private const val MAX_CAPTURE_CHARS: Int = 250_000

    /** Max chars we will attempt to print to logcat via chunked logging. */
    private const val MAX_LOGCAT_CHARS: Int = 120_000

    /** Chunk size per log line (keep below Logcat line limit). */
    private const val LOG_CHUNK: Int = 3_200

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var enabled: Boolean = ENABLED_DEFAULT

    /**
     * Install an application context for optional file dumps.
     *
     * Call once early, e.g. MainActivity.onCreate():
     *   AiTrace.install(applicationContext)
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "Installed (enabled=$enabled)")
    }

    fun capAppend(sb: StringBuilder, chunk: String): Boolean {
        if (sb.length >= MAX_CAPTURE_CHARS) return false
        val remaining = MAX_CAPTURE_CHARS - sb.length
        if (chunk.length <= remaining) {
            sb.append(chunk)
            return true
        }
        sb.append(chunk.substring(0, remaining))
        return false
    }

    fun sha256Short(text: String): String {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            bytes.take(8).joinToString("") { b -> "%02x".format(b) }
        }.getOrElse { "sha256_err" }
    }

    fun logLong(tag: String, level: Int, header: String, body: String) {
        if (!enabled) return

        val full = if (body.length > MAX_LOGCAT_CHARS) {
            body.take(MAX_LOGCAT_CHARS) + "\n... (logcat truncated; consider file dump)"
        } else {
            body
        }

        val lines = buildString {
            if (header.isNotBlank()) appendLine(header)
            append(full)
        }

        var i = 0
        var part = 0
        while (i < lines.length) {
            val end = minOf(lines.length, i + LOG_CHUNK)
            val slice = lines.substring(i, end)
            val prefix = "[part=${part.toString().padStart(3, '0')}] "
            when (level) {
                Log.ERROR -> Log.e(tag, prefix + slice)
                Log.WARN -> Log.w(tag, prefix + slice)
                else -> Log.d(tag, prefix + slice)
            }
            i = end
            part++
        }
    }

    /**
     * Best-effort dump into app-private storage:
     *   files/diagnostics/llm_trace/
     */
    fun dumpToFile(kind: String, requestId: Long, modelName: String, text: String): File? {
        if (!enabled) return null
        val ctx = appContext ?: return null

        return runCatching {
            val dir = File(ctx.filesDir, "diagnostics/llm_trace").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val safeModel = modelName.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
            val f = File(dir, "${kind}_${stamp}_rid${requestId}_${safeModel}.txt")
            f.writeText(text, Charsets.UTF_8)
            f
        }.onFailure { e ->
            Log.w(TAG, "dumpToFile failed: ${e.message}", e)
        }.getOrNull()
    }
}

/* ====================================================================== */
/*  Shared prompt utilities                                               */
/* ====================================================================== */

private fun String.normalizePrompt(): String =
    replace("\r\n", "\n")
        .replace("\r", "\n")
        .trimEnd('\n')

private fun compactJoin(vararg parts: String): String {
    val list = buildList {
        parts.forEach { p ->
            val t = p.normalizePrompt()
            if (t.isNotBlank()) add(t)
        }
    }
    return list.joinToString("\n")
}

/* ====================================================================== */
/*  Stream chunk normalization                                            */
/* ====================================================================== */

/**
 * Some streaming APIs return either:
 * - DELTA chunks (new tokens)
 * - ACCUMULATED text (full text so far)
 *
 * This helper normalizes to DELTA output for Flow emission and capture.
 */
private class StreamDeltaNormalizer(
    private val modeHint: PartialMode = PartialMode.AUTO
) {
    enum class PartialMode { AUTO, DELTA, ACCUMULATED }

    private var decided: PartialMode = modeHint
    private var lastFull: String = ""

    /**
     * Normalize [incoming] into a delta string to append/emit.
     */
    fun toDelta(incoming: String): String {
        if (incoming.isEmpty()) return ""

        if (decided == PartialMode.AUTO) {
            // Heuristic: if incoming often starts with previous full, treat as accumulated.
            decided = if (lastFull.isNotEmpty() && incoming.startsWith(lastFull)) {
                PartialMode.ACCUMULATED
            } else {
                // Wait one step: store and keep AUTO until we can compare reliably.
                // But if lastFull is empty, we can't decide yet.
                PartialMode.AUTO
            }
        }

        return when (decided) {
            PartialMode.DELTA -> {
                lastFull += incoming
                incoming
            }
            PartialMode.ACCUMULATED -> {
                val delta = if (incoming.startsWith(lastFull)) {
                    incoming.substring(lastFull.length)
                } else {
                    // Fallback: if mismatch, treat as a full reset; emit whole incoming.
                    incoming
                }
                lastFull = incoming
                delta
            }
            PartialMode.AUTO -> {
                // Not enough info yet; assume DELTA for first chunk.
                lastFull += incoming
                incoming
            }
        }
    }
}

/* ====================================================================== */
/*  SLM (MediaPipe) backend                                               */
/* ====================================================================== */

class SlmDirectRepository(
    private val appContext: Context,
    private val model: Model,
    private val config: SurveyConfig,
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"

        /** Request correlation id sequence (process-wide). */
        private val REQ_SEQ = AtomicLong(0L)

        // YAML fallback defaults
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

        // Watchdogs
        private const val HARD_WATCHDOG_MS = 20_000L
        private const val PROGRESS_STALL_MS = 6_000L
        private const val PROGRESS_POLL_MS = 250L

        // Finish behavior
        private const val FINISH_WATCHDOG_MS = 3_000L
        private const val FINISH_WATCHDOG_STEP_MS = 100L
        private const val FINISH_IDLE_GRACE_MS = 250L

        // Logging
        private const val PROMPT_HEADER_MAX = 8
        private const val PROMPT_TAIL_MAX = 8
    }

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

        val effective = if (userPrompt.isBlank()) emptyJson else userPrompt.trimIndent().normalizePrompt()

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

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun request(prompt: String): Flow<String> =
        callbackFlow {
            val out = this
            val requestId = REQ_SEQ.incrementAndGet()
            val gateReqAt = SystemClock.elapsedRealtime()

            AI_INFERENCE_GATE.withPermit {
                val gateWaitMs = SystemClock.elapsedRealtime() - gateReqAt

                val anchorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val closed = AtomicBoolean(false)
                val finalized = AtomicBoolean(false)

                val seenFinished = AtomicBoolean(false)
                val seenOnClean = AtomicBoolean(false)
                val finishWatchdogStarted = AtomicBoolean(false)

                val startAt = AtomicLong(SystemClock.elapsedRealtime())
                val lastProgressAt = AtomicLong(startAt.get())
                val firstChunkAt = AtomicLong(-1L)

                val chunks = AtomicLong(0L)
                val capturedAll = AtomicBoolean(true)
                val fullOut = StringBuilder(8 * 1024)

                val normalizer = StreamDeltaNormalizer(StreamDeltaNormalizer.PartialMode.AUTO)

                fun markProgress() {
                    lastProgressAt.set(SystemClock.elapsedRealtime())
                }

                fun isBusyNow(): Boolean {
                    return runCatching { SLM.isBusy(model) }
                        .onFailure { Log.w(TAG, "[$requestId] SLM.isBusy threw: ${it.message}", it) }
                        .getOrElse { true }
                }

                /**
                 * Decouple callback thread from Flow emission.
                 */
                val emitCh = Channel<String>(capacity = Channel.BUFFERED)

                val emitterJob = anchorScope.launch {
                    for (chunk in emitCh) {
                        if (chunk.isNotEmpty() && !out.isClosedForSend) {
                            out.trySend(chunk)
                        }
                    }
                }

                fun appendOutput(delta: String) {
                    if (delta.isEmpty()) return
                    if (firstChunkAt.get() < 0L) firstChunkAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    chunks.incrementAndGet()
                    val ok = AiTrace.capAppend(fullOut, delta)
                    if (!ok) capturedAll.set(false)
                }

                fun snapshotOutput(): String = fullOut.toString()

                fun buildPromptSummary(normalized: String): String {
                    val lines = normalized.split('\n')
                    val head = lines.take(PROMPT_HEADER_MAX).joinToString("\n")
                    val tail = lines.takeLast(PROMPT_TAIL_MAX).joinToString("\n")
                    val fp = AiTrace.sha256Short(normalized)
                    return buildString {
                        appendLine("rid=$requestId model='${model.name}' gateWaitMs=$gateWaitMs busy=${isBusyNow()}")
                        appendLine("prompt.len=${normalized.length} lines=${lines.size} sha256_8=$fp")
                        appendLine("--- prompt.head ---")
                        appendLine(head)
                        appendLine("--- prompt.tail ---")
                        appendLine(tail)
                    }
                }

                fun finalizeOnce(reason: String, cause: Throwable? = null) {
                    if (!finalized.compareAndSet(false, true)) return

                    val now = SystemClock.elapsedRealtime()
                    val elapsedMs = now - startAt.get()
                    val firstMs = firstChunkAt.get().let { if (it < 0L) -1L else (it - startAt.get()) }
                    val outText = snapshotOutput()

                    val stats = buildString {
                        appendLine("=== SLM TRACE STATS ===")
                        appendLine("rid=$requestId model='${model.name}' reason=$reason")
                        appendLine("gateWaitMs=$gateWaitMs elapsedMs=$elapsedMs firstChunkMs=$firstMs")
                        appendLine("chunks=${chunks.get()} capturedAll=${capturedAll.get()} out.len=${outText.length}")
                        appendLine("busy_now=${isBusyNow()} finished=${seenFinished.get()} onClean=${seenOnClean.get()}")
                        if (cause != null) {
                            appendLine("--- exception ---")
                            appendLine(Log.getStackTraceString(cause))
                        }
                        appendLine("=== OUTPUT (FULL) ===")
                        append(outText)
                        if (!capturedAll.get()) {
                            appendLine()
                            appendLine("... (output capture truncated by MAX_CAPTURE_CHARS)")
                        }
                    }

                    AiTrace.logLong(
                        tag = TAG,
                        level = if (cause != null) Log.WARN else Log.DEBUG,
                        header = "[$requestId] FINALIZE: $reason",
                        body = stats
                    )

                    val dump = buildString {
                        appendLine("=== PROMPT+OUTPUT DUMP ===")
                        appendLine("rid=$requestId model='${model.name}' reason=$reason")
                        appendLine()
                        appendLine("=== PROMPT ===")
                        appendLine(prompt.normalizePrompt())
                        appendLine()
                        appendLine("=== OUTPUT ===")
                        appendLine(outText)
                        if (!capturedAll.get()) appendLine("... (output capture truncated by MAX_CAPTURE_CHARS)")
                    }

                    val f = AiTrace.dumpToFile("slm", requestId, model.name, dump)
                    if (f != null) Log.d(TAG, "[$requestId] Dumped full prompt/output to: ${f.absolutePath}")
                }

                /**
                 * Single close path for all reasons.
                 *
                 * Ordering:
                 * 1) finalize
                 * 2) close emit channel
                 * 3) cancel emitter/scope
                 * 4) close flow
                 */
                fun closeOnce(reason: String, cause: Throwable? = null) {
                    if (!closed.compareAndSet(false, true)) return

                    finalizeOnce(reason, cause)

                    runCatching { emitCh.close() }
                    runCatching { emitterJob.cancel() }
                    runCatching { anchorScope.cancel(CancellationException("closeOnce: $reason")) }

                    if (cause != null) out.close(cause) else out.close()
                }

                /**
                 * Watchdog: hard timeout + progress stall.
                 */
                anchorScope.launch {
                    while (isActive && !closed.get()) {
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - startAt.get()
                        val stalled = now - lastProgressAt.get()

                        if (elapsed >= HARD_WATCHDOG_MS) {
                            Log.w(TAG, "[$requestId] hard watchdog timeout (${elapsed}ms) → cancel/reset/close")
                            runCatching { SLM.cancel(model) }
                            runCatching { SLM.resetSession(model) }
                            closeOnce("hard-watchdog-timeout")
                            break
                        }

                        if (!seenFinished.get() && stalled >= PROGRESS_STALL_MS) {
                            Log.w(TAG, "[$requestId] progress stall (${stalled}ms) → cancel/reset/close")
                            runCatching { SLM.cancel(model) }
                            runCatching { SLM.resetSession(model) }
                            closeOnce("progress-stall-timeout")
                            break
                        }

                        delay(PROGRESS_POLL_MS)
                    }
                }

                try {
                    val normalizedPrompt = prompt.normalizePrompt()

                    Log.d(
                        TAG,
                        "[$requestId] SLM request start: model='${model.name}', prompt.len=${normalizedPrompt.length}, gateWaitMs=$gateWaitMs, busy=${isBusyNow()}"
                    )

                    AiTrace.logLong(TAG, Log.DEBUG, "[$requestId] PROMPT (FULL)", normalizedPrompt)
                    Log.d(TAG, "[$requestId] " + buildPromptSummary(normalizedPrompt))

                    // Ensure initialized (engine/session ready).
                    val initErr = withTimeoutOrNull(8_000L) {
                        var err: String? = null
                        val done = AtomicBoolean(false)
                        SLM.ensureInitialized(appContext, model) { e ->
                            err = e
                            done.set(true)
                        }
                        while (!done.get()) delay(25)
                        err
                    } ?: "SLM.ensureInitialized timed out."

                    if (initErr.isNotBlank()) {
                        Log.e(TAG, "[$requestId] ensureInitialized failed: $initErr")
                        closeOnce("ensureInitialized-error", RuntimeException(initErr))
                        return@withPermit
                    }

                    if (isBusyNow()) {
                        Log.w(TAG, "[$requestId] pre-run: engine BUSY → cancel/resetSession")
                        runCatching { SLM.cancel(model) }
                        runCatching { SLM.resetSession(model) }
                    }

                    SLM.runInference(
                        model = model,
                        input = normalizedPrompt,
                        listener = { partial, finished ->
                            markProgress()

                            // Normalize partial to delta (prevents accumulated duplication).
                            val delta = normalizer.toDelta(partial)
                            if (delta.isNotEmpty()) {
                                appendOutput(delta)
                                emitCh.trySend(delta)
                            }

                            if (finished) {
                                seenFinished.set(true)
                                if (!finishWatchdogStarted.compareAndSet(false, true)) return@runInference

                                Log.d(TAG, "[$requestId] SLM inference finished (model='${model.name}')")

                                anchorScope.launch {
                                    val ok = withTimeoutOrNull(FINISH_WATCHDOG_MS) {
                                        var idleSince = -1L
                                        while (isActive && !closed.get() && !seenOnClean.get()) {
                                            val busy = isBusyNow()
                                            val now = SystemClock.elapsedRealtime()

                                            if (!busy) {
                                                if (idleSince < 0) idleSince = now
                                                if ((now - idleSince) >= FINISH_IDLE_GRACE_MS) break
                                            } else {
                                                idleSince = -1L
                                            }
                                            delay(FINISH_WATCHDOG_STEP_MS)
                                        }
                                        true
                                    } != null

                                    if (!closed.get() && !seenOnClean.get()) {
                                        if (ok) closeOnce("finished-idle-grace")
                                        else closeOnce("finish-watchdog-timeout")
                                    }
                                }
                            }
                        },
                        onClean = {
                            markProgress()
                            seenOnClean.set(true)
                            Log.d(TAG, "[$requestId] SLM onClean (model='${model.name}')")
                            closeOnce("onClean")
                        }
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "[$requestId] SLM.runInference threw: ${t.message}", t)
                    runCatching { SLM.cancel(model) }
                    runCatching { SLM.resetSession(model) }
                    closeOnce("exception", t)
                }

                awaitClose {
                    // If collector cancels without triggering our close path, clean aggressively.
                    if (!closed.get()) {
                        Log.d(TAG, "[$requestId] awaitClose: collector-cancel → cancel/reset + close")
                        runCatching { SLM.cancel(model) }
                        runCatching { SLM.resetSession(model) }
                        closeOnce("collector-cancel")
                    }
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.IO)
}

/* ====================================================================== */
/*  LiteRtLM backend                                                      */
/* ====================================================================== */

class LiteRtRepository(
    private val model: Model,
    private val config: SurveyConfig,
) : Repository {

    companion object {
        private const val TAG = "LiteRtRepository"

        private val REQ_SEQ = AtomicLong(0L)

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

        private const val HARD_WATCHDOG_MS = 20_000L
        private const val PROGRESS_STALL_MS = 6_000L
        private const val PROGRESS_POLL_MS = 250L
    }

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

        val effective = if (userPrompt.isBlank()) emptyJson else userPrompt.trimIndent().normalizePrompt()

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

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun request(prompt: String): Flow<String> =
        callbackFlow {
            val out = this
            val requestId = REQ_SEQ.incrementAndGet()
            val gateReqAt = SystemClock.elapsedRealtime()

            AI_INFERENCE_GATE.withPermit {
                val gateWaitMs = SystemClock.elapsedRealtime() - gateReqAt

                val anchorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val closed = AtomicBoolean(false)
                val finalized = AtomicBoolean(false)

                val seenFinished = AtomicBoolean(false)
                val cleanupTriggered = AtomicBoolean(false)

                val startAt = AtomicLong(SystemClock.elapsedRealtime())
                val lastProgressAt = AtomicLong(startAt.get())
                val firstChunkAt = AtomicLong(-1L)

                val chunks = AtomicLong(0L)
                val capturedAll = AtomicBoolean(true)
                val fullOut = StringBuilder(8 * 1024)

                val normalizer = StreamDeltaNormalizer(StreamDeltaNormalizer.PartialMode.AUTO)

                val emitCh = Channel<String>(capacity = Channel.BUFFERED)
                val emitterJob = anchorScope.launch {
                    for (chunk in emitCh) {
                        if (chunk.isNotEmpty() && !out.isClosedForSend) out.trySend(chunk)
                    }
                }

                fun markProgress() {
                    lastProgressAt.set(SystemClock.elapsedRealtime())
                }

                fun appendOutput(delta: String) {
                    if (delta.isEmpty()) return
                    if (firstChunkAt.get() < 0L) firstChunkAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    chunks.incrementAndGet()
                    val ok = AiTrace.capAppend(fullOut, delta)
                    if (!ok) capturedAll.set(false)
                }

                fun snapshotOutput(): String = fullOut.toString()

                fun finalizeOnce(reason: String, cause: Throwable? = null) {
                    if (!finalized.compareAndSet(false, true)) return

                    val now = SystemClock.elapsedRealtime()
                    val elapsedMs = now - startAt.get()
                    val firstMs = firstChunkAt.get().let { if (it < 0L) -1L else (it - startAt.get()) }

                    val normalizedPrompt = prompt.normalizePrompt()
                    val outText = snapshotOutput()

                    val stats = buildString {
                        appendLine("=== LITERT TRACE STATS ===")
                        appendLine("rid=$requestId model='${model.name}' reason=$reason")
                        appendLine("gateWaitMs=$gateWaitMs elapsedMs=$elapsedMs firstChunkMs=$firstMs")
                        appendLine("chunks=${chunks.get()} capturedAll=${capturedAll.get()} out.len=${outText.length}")
                        if (cause != null) {
                            appendLine("--- exception ---")
                            appendLine(Log.getStackTraceString(cause))
                        }
                        appendLine("=== PROMPT (FULL) ===")
                        appendLine(normalizedPrompt)
                        appendLine("=== OUTPUT (FULL) ===")
                        append(outText)
                        if (!capturedAll.get()) appendLine("\n... (output capture truncated by MAX_CAPTURE_CHARS)")
                    }

                    AiTrace.logLong(TAG, if (cause != null) Log.WARN else Log.DEBUG, "[$requestId] FINALIZE: $reason", stats)
                    AiTrace.dumpToFile("litert", requestId, model.name, stats)
                }

                fun closeOnce(reason: String, cause: Throwable? = null) {
                    if (!closed.compareAndSet(false, true)) return

                    finalizeOnce(reason, cause)

                    runCatching { emitCh.close() }
                    runCatching { emitterJob.cancel() }
                    runCatching { anchorScope.cancel(CancellationException("closeOnce: $reason")) }

                    if (cause != null) out.close(cause) else out.close()
                }

                fun bestEffortCleanUp(tag: String) {
                    if (!cleanupTriggered.compareAndSet(false, true)) return
                    runCatching {
                        LiteRtLM.cleanUp(model) { Log.d(TAG, "[$requestId] LiteRtLM cleaned up ($tag)") }
                    }.onFailure {
                        Log.w(TAG, "[$requestId] LiteRtLM.cleanUp failed ($tag): ${it.message}", it)
                    }
                }

                anchorScope.launch {
                    while (isActive && !closed.get()) {
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - startAt.get()
                        val stalled = now - lastProgressAt.get()

                        if (elapsed >= HARD_WATCHDOG_MS) {
                            Log.w(TAG, "[$requestId] LiteRtLM hard watchdog timeout (${elapsed}ms) → cleanUp/close")
                            bestEffortCleanUp("hard-watchdog")
                            closeOnce("hard-watchdog-timeout")
                            break
                        }

                        if (!seenFinished.get() && stalled >= PROGRESS_STALL_MS) {
                            Log.w(TAG, "[$requestId] LiteRtLM progress stall (${stalled}ms) → cleanUp/close")
                            bestEffortCleanUp("progress-stall")
                            closeOnce("progress-stall-timeout")
                            break
                        }

                        delay(PROGRESS_POLL_MS)
                    }
                }

                try {
                    val normalizedPrompt = prompt.normalizePrompt()

                    Log.d(TAG, "[$requestId] LiteRtLM request start: model='${model.name}', prompt.len=${normalizedPrompt.length}, gateWaitMs=$gateWaitMs")
                    AiTrace.logLong(TAG, Log.DEBUG, "[$requestId] PROMPT (FULL)", normalizedPrompt)

                    LiteRtLM.runInference(
                        model = model,
                        input = normalizedPrompt,
                        resultListener = { partial, finished ->
                            markProgress()
                            val delta = normalizer.toDelta(partial)
                            if (delta.isNotEmpty()) {
                                appendOutput(delta)
                                emitCh.trySend(delta)
                            }
                            if (finished) {
                                seenFinished.set(true)
                                closeOnce("finished")
                            }
                        },
                        cleanUpListener = {
                            markProgress()
                            closeOnce("cleanup-listener")
                        },
                        onError = { message ->
                            markProgress()
                            bestEffortCleanUp("onError")
                            closeOnce("litert-error", RuntimeException(message))
                        }
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "[$requestId] LiteRtLM.runInference threw: ${t.message}", t)
                    bestEffortCleanUp("exception")
                    closeOnce("exception", t)
                }

                awaitClose {
                    if (!closed.get()) {
                        bestEffortCleanUp("collector-cancel")
                        closeOnce("collector-cancel")
                    }
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.IO)
}
