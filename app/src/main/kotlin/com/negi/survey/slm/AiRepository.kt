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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

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
/*  Shared process-wide inference gate                                     */
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

    private val installedOnce = AtomicBoolean(false)

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
        if (installedOnce.compareAndSet(false, true)) {
            Log.d(TAG, "Installed (enabled=$enabled)")
        }
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
            bytes.take(8).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
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
                // Wait one step: keep AUTO until we can compare reliably.
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
                    // Fallback: mismatch => treat as full reset; emit whole incoming.
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
/*  Shared prompt defaults                                                 */
/* ====================================================================== */

private object PromptDefaults {
    // YAML fallback defaults
    const val USER_TURN_PREFIX: String = "<start_of_turn>user"
    const val MODEL_TURN_PREFIX: String = "<start_of_turn>model"
    const val TURN_END: String = "<end_of_turn>"
    const val EMPTY_JSON_INSTRUCTION: String = "Respond with an empty JSON object: {}"

    const val PREAMBLE: String =
        "You are a well-known farmer survey expert. Read the Question and the Answer."

    const val KEY_CONTRACT: String =
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

    const val LENGTH_BUDGET: String =
        "LENGTH LIMITS:\n" +
                "- analysis<=80 chars\n" +
                "- expected answer<=60 chars\n" +
                "- follow-up question<=90 chars"

    const val SCORING_RULE: String =
        "SCORING RULE:\n" +
                "- Judge ONLY content relevance/completeness/accuracy.\n" +
                "- Do NOT penalize style or formatting."

    const val STRICT_OUTPUT: String =
        "STRICT OUTPUT (NO MARKDOWN):\n" +
                "- RAW JSON only.\n" +
                "- No extra text.\n" +
                "- Prefer compact JSON.\n" +
                "- Entire output should be short and machine-parseable."
}

/* ====================================================================== */
/*  Shared prompt builder                                                  */
/* ====================================================================== */

private fun buildPromptCommon(config: SurveyConfig, userPrompt: String): String {
    val slm = config.slm

    val userTurn = slm.user_turn_prefix ?: PromptDefaults.USER_TURN_PREFIX
    val modelTurn = slm.model_turn_prefix ?: PromptDefaults.MODEL_TURN_PREFIX
    val turnEnd = slm.turn_end ?: PromptDefaults.TURN_END
    val emptyJson = slm.empty_json_instruction ?: PromptDefaults.EMPTY_JSON_INSTRUCTION

    val preamble = slm.preamble ?: PromptDefaults.PREAMBLE
    val keyContract = slm.key_contract ?: PromptDefaults.KEY_CONTRACT
    val lengthBudget = slm.length_budget ?: PromptDefaults.LENGTH_BUDGET
    val scoringRule = slm.scoring_rule ?: PromptDefaults.SCORING_RULE
    val strictOutput = slm.strict_output ?: PromptDefaults.STRICT_OUTPUT

    val effective = if (userPrompt.isBlank()) emptyJson else userPrompt.trimIndent().normalizePrompt()

    val userBlock = compactJoin(
        preamble,
        keyContract,
        lengthBudget,
        scoringRule,
        strictOutput,
        effective,
    )

    return compactJoin(
        userTurn,
        userBlock,
        turnEnd,
        modelTurn,
    )
}

/* ====================================================================== */
/*  SLM (MediaPipe) backend                                               */
/* ====================================================================== */

class SlmDirectRepository(
    private val appContext: Context,
    private val model: Model,
    private val config: SurveyConfig,
) : Repository {

    init {
        // Ensure file dumps can work without requiring explicit install elsewhere.
        AiTrace.install(appContext)
    }

    companion object {
        private const val TAG = "SlmDirectRepository"

        /** Request correlation id sequence (process-wide). */
        private val REQ_SEQ = AtomicLong(0L)

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
        val p = buildPromptCommon(config, userPrompt)
        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${p.length}")
        return p
    }

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
                        if (chunk.isNotEmpty() && !out.isClosedForSend) out.trySend(chunk)
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
                        body = stats,
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
                        "[$requestId] SLM request start: model='${model.name}', prompt.len=${normalizedPrompt.length}, gateWaitMs=$gateWaitMs, busy=${isBusyNow()}",
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
                        // BUGFIX: null means success (do NOT treat as timeout).
                        err ?: ""
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
                        },
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

        private const val HARD_WATCHDOG_MS = 20_000L
        private const val PROGRESS_STALL_MS = 6_000L
        private const val PROGRESS_POLL_MS = 250L
    }

    override fun buildPrompt(userPrompt: String): String {
        val p = buildPromptCommon(config, userPrompt)
        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${p.length}")
        return p
    }

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
                        },
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

/* ====================================================================== */
/*  Unified Single/Double step support                                    */
/* ====================================================================== */

/**
 * Output key style for the final JSON.
 *
 * - LEGACY: "expected answer", "follow-up question"
 * - SNAKE_CASE: "expected_answer", "follow_up_question"
 */
enum class OutputKeyStyle {
    LEGACY,
    SNAKE_CASE,
}

/**
 * Two-step stages.
 */
enum class TwoStepStage {
    EVAL,
    FOLLOW_UP,
}

/**
 * Request mode for AI.
 */
sealed class AiRequestMode {

    /**
     * Single-step mode: runs exactly one inference and streams raw deltas.
     */
    data class SingleStep(
        val passThroughStreaming: Boolean = true,
    ) : AiRequestMode()

    /**
     * Double-step mode: runs EVAL then optional FOLLOW_UP based on gating.
     */
    data class DoubleStep(
        val options: TwoStepOptions = TwoStepOptions(),
    ) : AiRequestMode()
}

/**
 * Configuration knobs for two-step validation.
 */
data class TwoStepOptions(
    /** Score threshold at/above which we consider the answer "OK". */
    val evalOkScoreThreshold: Int = 85,

    /** If true and eval says OK, skip the follow-up step. */
    val skipFollowupWhenOk: Boolean = true,

    /**
     * If true, on EVAL JSON parse failure we conservatively run FOLLOW_UP.
     * If false, we return the raw EVAL output as-is.
     */
    val followupOnEvalParseError: Boolean = true,

    /** Output key style for the final JSON. */
    val outputKeyStyle: OutputKeyStyle = OutputKeyStyle.LEGACY,

    /**
     * Emit streamed deltas from EVAL step to the returned Flow.
     * Usually false (keeps UI clean).
     */
    val emitEvalChunks: Boolean = false,

    /**
     * Emit streamed deltas from FOLLOW_UP step to the returned Flow.
     * Usually false (follow-up output should be short anyway).
     */
    val emitFollowupChunks: Boolean = false,

    /**
     * Emit the final merged JSON as a single emission at the end.
     * If you also emit chunks, this can cause duplicated-looking UI,
     * so by default it only emits the final JSON.
     */
    val emitFinalMergedJson: Boolean = true,

    /**
     * If true, allow FOLLOW_UP step to override analysis/expected fields too.
     * Default keeps EVAL's analysis/expected and only replaces follow-up question.
     */
    val followupOverridesAllFields: Boolean = false,

    /**
     * If true, include gating fields ("needs_followup", "missing") in final JSON.
     * Default false to preserve legacy schema.
     */
    val includeGatingFieldsInFinal: Boolean = false,

    /**
     * If true, include minimal meta fields in final JSON for debugging.
     * Default false to keep output compact and stable.
     */
    val includeMetaInFinal: Boolean = false,
)

/**
 * Run inference in either Single-step or Double-step mode.
 *
 * Contract:
 * - SingleStep: streams raw deltas from the underlying repository (legacy behavior).
 * - DoubleStep: captures EVAL (optionally emits chunks), gates, and optionally runs FOLLOW_UP.
 *   It then emits one final merged JSON (by default).
 */
fun Repository.requestWithMode(
    userPrompt: String,
    mode: AiRequestMode = AiRequestMode.SingleStep(),
): Flow<String> = flow {
    when (mode) {
        is AiRequestMode.SingleStep -> {
            val prompt = buildPrompt(userPrompt)
            request(prompt).collect { delta ->
                if (mode.passThroughStreaming) emit(delta)
            }
        }

        is AiRequestMode.DoubleStep -> {
            val opts = mode.options
            val metaRid = SystemClock.elapsedRealtime()

            // -------------------------
            // 1) EVAL
            // -------------------------
            val evalUserPrompt = buildEvalUserPrompt(
                baseUserPrompt = userPrompt,
                threshold = opts.evalOkScoreThreshold,
                keyStyle = opts.outputKeyStyle,
            )

            val evalPrompt = buildPrompt(evalUserPrompt)
            val evalRaw = collectFlowToString(
                upstream = request(evalPrompt),
                emitChunks = opts.emitEvalChunks,
                emit = { chunk -> emit(chunk) },
            )

            val evalJsonStr = extractFirstJsonObject(evalRaw) ?: ""
            val evalParsed = parseEvalJson(
                jsonStr = evalJsonStr,
                keyStyle = opts.outputKeyStyle,
            )

            if (evalParsed == null) {
                // EVAL parse failed.
                if (!opts.followupOnEvalParseError) {
                    if (opts.emitFinalMergedJson) {
                        emit(wrapAsFallbackJson(evalRaw, opts.outputKeyStyle, error = "eval_json_parse_failed"))
                    }
                    return@flow
                }
                // Otherwise: proceed to FOLLOW_UP with no "missing" hint.
            } else {
                val needsFollowup = decideNeedsFollowup(evalParsed, opts.evalOkScoreThreshold)
                val ok = !needsFollowup

                if (ok && opts.skipFollowupWhenOk) {
                    val finalJson = buildFinalJsonFromEval(
                        eval = evalParsed,
                        keyStyle = opts.outputKeyStyle,
                        includeGating = opts.includeGatingFieldsInFinal,
                        includeMeta = opts.includeMetaInFinal,
                        metaRid = metaRid,
                        metaMode = "single_eval_ok",
                    )
                    if (opts.emitFinalMergedJson) emit(finalJson)
                    return@flow
                }
            }

            // -------------------------
            // 2) FOLLOW_UP
            // -------------------------
            val followUserPrompt = buildFollowupUserPrompt(
                baseUserPrompt = userPrompt,
                evalMissingHint = evalParsed?.missing,
                keyStyle = opts.outputKeyStyle,
            )

            val followPrompt = buildPrompt(followUserPrompt)
            val followRaw = collectFlowToString(
                upstream = request(followPrompt),
                emitChunks = opts.emitFollowupChunks,
                emit = { chunk -> emit(chunk) },
            )

            val followJsonStr = extractFirstJsonObject(followRaw) ?: ""
            val followParsed = parseFollowupJson(
                jsonStr = followJsonStr,
                keyStyle = opts.outputKeyStyle,
            )

            val finalMerged = mergeEvalAndFollowup(
                eval = evalParsed,
                follow = followParsed,
                rawEval = evalRaw,
                rawFollow = followRaw,
                opts = opts,
                metaRid = metaRid,
            )

            if (opts.emitFinalMergedJson) emit(finalMerged)
        }
    }
}

/* ====================================================================== */
/*  Two-step helpers                                                      */
/* ====================================================================== */

private data class EvalResult(
    val analysis: String?,
    val expected: String?,
    val followupQuestion: String?,
    val score: Int?,
    val needsFollowup: Boolean?,
    val missing: String?,
)

private data class FollowupResult(
    val analysis: String?,
    val expected: String?,
    val followupQuestion: String?,
    val score: Int?,
)

/**
 * Collect a streaming [Flow] into a single String while optionally emitting chunks downstream.
 */
private suspend fun collectFlowToString(
    upstream: Flow<String>,
    emitChunks: Boolean,
    emit: suspend (String) -> Unit,
): String {
    val sb = StringBuilder(8 * 1024)
    upstream.collect { delta ->
        if (delta.isEmpty()) return@collect
        sb.append(delta)
        if (emitChunks) emit(delta)
    }
    return sb.toString()
}

/**
 * Build a stage-specific user prompt for EVAL.
 *
 * This is appended AFTER the repository's base key contract, so we make it explicit that this step overrides it.
 */
private fun buildEvalUserPrompt(
    baseUserPrompt: String,
    threshold: Int,
    keyStyle: OutputKeyStyle,
): String {
    val keys = keysForStyle(keyStyle)
    val override = buildString {
        appendLine("OVERRIDE: EVAL STEP (validation).")
        appendLine("Return RAW JSON only (no markdown, no extra text).")
        appendLine("Required keys:")
        appendLine(" - \"${keys.analysis}\" (short)")
        appendLine(" - \"${keys.expected}\" (short)")
        appendLine(" - \"${keys.followup}\" (single question or empty string)")
        appendLine(" - \"${keys.score}\" (integer 1-100)")
        appendLine("Additional gating keys (required):")
        appendLine(" - \"needs_followup\" (true/false)")
        appendLine(" - \"missing\" (short string or empty)")
        appendLine("Rules:")
        appendLine(" - If score >= $threshold: set needs_followup=false and set \"${keys.followup}\" to \"\".")
        appendLine(" - If score < $threshold: set needs_followup=true and ask ONE concrete follow-up question.")
    }
    return compactJoin(override, baseUserPrompt)
}

/**
 * Build a stage-specific user prompt for FOLLOW_UP.
 */
private fun buildFollowupUserPrompt(
    baseUserPrompt: String,
    evalMissingHint: String?,
    keyStyle: OutputKeyStyle,
): String {
    val keys = keysForStyle(keyStyle)
    val hintLine = evalMissingHint?.takeIf { it.isNotBlank() }?.let { "Hint (missing): $it" } ?: ""
    val override = buildString {
        appendLine("OVERRIDE: FOLLOW_UP STEP (generate a single clarifying question).")
        appendLine("Return RAW JSON only (no markdown, no extra text).")
        appendLine("Keys:")
        appendLine(" - \"${keys.analysis}\" (short)")
        appendLine(" - \"${keys.expected}\" (short)")
        appendLine(" - \"${keys.followup}\" (ONE single-scope question)")
        appendLine(" - \"${keys.score}\" (integer 1-100)")
        if (hintLine.isNotBlank()) appendLine(hintLine)
        appendLine("Rules:")
        appendLine(" - Focus ONLY on the best next follow-up question to clarify the respondent's original answer.")
        appendLine(" - Keep it concrete, answerable immediately, and not multi-part.")
    }
    return compactJoin(override, baseUserPrompt)
}

/**
 * Decide if a follow-up is needed based on [EvalResult].
 *
 * Order of precedence:
 * 1) explicit needs_followup if present
 * 2) score threshold comparison
 * 3) otherwise: conservative true
 */
private fun decideNeedsFollowup(eval: EvalResult, threshold: Int): Boolean {
    eval.needsFollowup?.let { return it }
    val score = eval.score
    if (score != null) return score < threshold
    return true
}

/**
 * Parse EVAL JSON (best-effort).
 */
private fun parseEvalJson(jsonStr: String, keyStyle: OutputKeyStyle): EvalResult? {
    if (jsonStr.isBlank()) return null
    return try {
        val obj = JSONObject(jsonStr)
        val keys = keysForStyle(keyStyle)
        EvalResult(
            analysis = obj.optStringAny(keys.analysis, "analysis"),
            expected = obj.optStringAny(keys.expected, "expected_answer", "expected answer", "expectedAnswer"),
            followupQuestion = obj.optStringAny(keys.followup, "follow_up_question", "follow-up question", "followup_question"),
            score = obj.optIntAny(keys.score, "score"),
            needsFollowup = obj.optBooleanAny("needs_followup", "needsFollowup", "needs followup"),
            missing = obj.optStringAny("missing", "missing_field", "missing fields"),
        )
    } catch (_: JSONException) {
        null
    }
}

/**
 * Parse FOLLOW_UP JSON (best-effort).
 */
private fun parseFollowupJson(jsonStr: String, keyStyle: OutputKeyStyle): FollowupResult? {
    if (jsonStr.isBlank()) return null
    return try {
        val obj = JSONObject(jsonStr)
        val keys = keysForStyle(keyStyle)
        FollowupResult(
            analysis = obj.optStringAny(keys.analysis, "analysis"),
            expected = obj.optStringAny(keys.expected, "expected_answer", "expected answer", "expectedAnswer"),
            followupQuestion = obj.optStringAny(keys.followup, "follow_up_question", "follow-up question", "followup_question"),
            score = obj.optIntAny(keys.score, "score"),
        )
    } catch (_: JSONException) {
        null
    }
}

/**
 * Build final JSON from EVAL only.
 */
private fun buildFinalJsonFromEval(
    eval: EvalResult,
    keyStyle: OutputKeyStyle,
    includeGating: Boolean,
    includeMeta: Boolean,
    metaRid: Long,
    metaMode: String,
): String {
    val keys = keysForStyle(keyStyle)
    val out = JSONObject()
    out.put(keys.analysis, eval.analysis ?: "")
    out.put(keys.expected, eval.expected ?: "")
    out.put(keys.followup, eval.followupQuestion ?: "")
    out.put(keys.score, eval.score ?: 0)

    if (includeGating) {
        out.put("needs_followup", eval.needsFollowup ?: false)
        out.put("missing", eval.missing ?: "")
    }
    if (includeMeta) {
        out.put("_meta_rid", metaRid)
        out.put("_meta_mode", metaMode)
    }
    return out.toString()
}

/**
 * Merge EVAL and FOLLOW_UP outputs into one final JSON.
 *
 * Default behavior:
 * - Preserve EVAL's analysis/expected/score
 * - Replace follow-up question with FOLLOW_UP's follow-up question (if present)
 */
private fun mergeEvalAndFollowup(
    eval: EvalResult?,
    follow: FollowupResult?,
    rawEval: String,
    rawFollow: String,
    opts: TwoStepOptions,
    metaRid: Long,
): String {
    val keys = keysForStyle(opts.outputKeyStyle)

    if (eval == null && follow == null) {
        return wrapAsFallbackJson(
            rawOutput = compactJoin("EVAL_RAW:\n$rawEval", "FOLLOW_RAW:\n$rawFollow"),
            keyStyle = opts.outputKeyStyle,
            error = "both_steps_parse_failed",
        )
    }

    val baseAnalysis = eval?.analysis ?: follow?.analysis ?: ""
    val baseExpected = eval?.expected ?: follow?.expected ?: ""
    val baseScore = eval?.score ?: follow?.score ?: 0
    val baseFollowup = eval?.followupQuestion ?: follow?.followupQuestion ?: ""

    val followFollowup = follow?.followupQuestion?.takeIf { it.isNotBlank() }
    val followAnalysis = follow?.analysis?.takeIf { it.isNotBlank() }
    val followExpected = follow?.expected?.takeIf { it.isNotBlank() }
    val followScore = follow?.score

    val out = JSONObject()

    if (opts.followupOverridesAllFields) {
        out.put(keys.analysis, followAnalysis ?: baseAnalysis)
        out.put(keys.expected, followExpected ?: baseExpected)
        out.put(keys.followup, followFollowup ?: baseFollowup)
        out.put(keys.score, followScore ?: baseScore)
    } else {
        out.put(keys.analysis, baseAnalysis)
        out.put(keys.expected, baseExpected)
        out.put(keys.followup, followFollowup ?: baseFollowup)
        out.put(keys.score, baseScore)
    }

    if (opts.includeGatingFieldsInFinal) {
        out.put("needs_followup", true)
        out.put("missing", eval?.missing ?: "")
    }

    if (opts.includeMetaInFinal) {
        out.put("_meta_rid", metaRid)
        out.put("_meta_mode", "two_step_merged")
    }

    return out.toString()
}

/**
 * Wrap raw output into a conservative JSON when strict JSON couldn't be extracted/parsed.
 */
private fun wrapAsFallbackJson(rawOutput: String, keyStyle: OutputKeyStyle, error: String): String {
    val keys = keysForStyle(keyStyle)
    val out = JSONObject()
    out.put(keys.analysis, "")
    out.put(keys.expected, "")
    out.put(keys.followup, "")
    out.put(keys.score, 0)
    out.put("_error", error)
    out.put("_raw", rawOutput.take(20_000))
    return out.toString()
}

/**
 * Extract the first JSON object from [text] using a small state machine that handles strings/escapes.
 */
private fun extractFirstJsonObject(text: String): String? {
    val s = text.normalizePrompt()
    val firstBrace = s.indexOf('{')
    if (firstBrace < 0) return null

    var start = -1
    var depth = 0
    var inString = false
    var escaped = false

    for (i in firstBrace until s.length) {
        val c = s[i]

        if (inString) {
            if (escaped) {
                escaped = false
            } else {
                when (c) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            }
            continue
        }

        when (c) {
            '"' -> inString = true
            '{' -> {
                if (depth == 0) start = i
                depth++
            }
            '}' -> {
                if (depth > 0) depth--
                if (depth == 0 && start >= 0) return s.substring(start, i + 1)
            }
        }
    }
    return null
}

/**
 * Key set for a chosen [OutputKeyStyle].
 */
private data class KeySet(
    val analysis: String,
    val expected: String,
    val followup: String,
    val score: String,
)

private fun keysForStyle(style: OutputKeyStyle): KeySet {
    return when (style) {
        OutputKeyStyle.LEGACY -> KeySet(
            analysis = "analysis",
            expected = "expected answer",
            followup = "follow-up question",
            score = "score",
        )
        OutputKeyStyle.SNAKE_CASE -> KeySet(
            analysis = "analysis",
            expected = "expected_answer",
            followup = "follow_up_question",
            score = "score",
        )
    }
}

/* ====================================================================== */
/*  JSONObject small utilities                                             */
/* ====================================================================== */

private fun JSONObject.optStringAny(vararg keys: String): String? {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = opt(k)
        when (v) {
            is String -> return v
            is Number, is Boolean -> return v.toString()
            else -> {
                val s = optString(k, "")
                if (s.isNotBlank()) return s
            }
        }
    }
    return null
}

private fun JSONObject.optIntAny(vararg keys: String): Int? {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = opt(k)
        when (v) {
            is Int -> return v
            is Long -> return v.toInt()
            is Double -> return v.toInt()
            is Float -> return v.toInt()
            is String -> {
                val n = v.trim().toIntOrNull()
                if (n != null) return n
            }
        }
    }
    return null
}

private fun JSONObject.optBooleanAny(vararg keys: String): Boolean? {
    for (k in keys) {
        if (!has(k) || isNull(k)) continue
        val v = opt(k)
        when (v) {
            is Boolean -> return v
            is String -> {
                val t = v.trim().lowercase(Locale.US)
                if (t == "true") return true
                if (t == "false") return false
            }
            is Number -> return v.toInt() != 0
        }
    }
    return null
}
