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

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

    /** Build the full model-ready prompt string from a user-level [userPrompt]. */
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
 * - IMPORTANT: We must not release the gate until backend cleanup is finished
 *   (best-effort wait), otherwise back-to-back calls can crash native backends.
 */
private val AI_INFERENCE_GATE = Semaphore(1)

/**
 * Dedicated IO scope for trace dumps to avoid blocking callback threads (often Main).
 */
private val TRACE_IO_SCOPE: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/* ====================================================================== */
/*  Logging / Trace utilities                                              */
/* ====================================================================== */

private object AiTrace {

    private const val TAG = "AiTrace"

    /** Enables verbose prompt/output tracing. */
    private val ENABLED_DEFAULT: Boolean = BuildConfig.DEBUG

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

    /** Normalize [incoming] into a delta string to append/emit. */
    fun toDelta(incoming: String): String {
        if (incoming.isEmpty()) return ""

        if (decided == PartialMode.AUTO) {
            decided = if (lastFull.isNotEmpty() && incoming.startsWith(lastFull)) {
                PartialMode.ACCUMULATED
            } else {
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
                    incoming
                }
                lastFull = incoming
                delta
            }
            PartialMode.AUTO -> {
                lastFull += incoming
                incoming
            }
        }
    }
}

/* ====================================================================== */
/*  Shared prompt defaults                                                */
/* ====================================================================== */

private object PromptDefaults {
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
/*  Shared prompt builder                                                 */
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
/*  Close action + request context                                        */
/* ====================================================================== */

private enum class CloseAction {
    NONE,
    SLM_RESET,
    SLM_CANCEL_RESET,
    LITERT_CLEANUP,
}

/** Close info shared across callback threads safely. */
private data class CloseInfo(
    val reason: String,
    val action: CloseAction,
    val cause: Throwable?,
)

/**
 * Request context shared between callbacks and awaitClose.
 *
 * IMPORTANT:
 * - Close must be idempotent (callbacks may race with cancellation).
 * - awaitClose must be called exactly once.
 */
private class RequestCtx(
    val tag: String,
    val requestId: Long,
    val modelName: String,
) {
    val closeRequested = AtomicBoolean(false)
    val finalized = AtomicBoolean(false)
    val closeInfoRef = AtomicReference(CloseInfo("unknown", CloseAction.NONE, null))

    fun prefix(): String = "[$tag rid=$requestId model='$modelName']"

    fun requestClose(reason: String, action: CloseAction, cause: Throwable? = null): Boolean {
        if (!closeRequested.compareAndSet(false, true)) return false
        closeInfoRef.set(CloseInfo(reason, action, cause))
        return true
    }

    fun closeInfo(): CloseInfo = closeInfoRef.get()
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
        AiTrace.install(appContext)
    }

    companion object {
        private const val TAG = "SlmDirectRepository"
        private val REQ_SEQ = AtomicLong(0L)

        private const val HARD_WATCHDOG_MS = 20_000L
        private const val PROGRESS_STALL_MS = 6_000L
        private const val PROGRESS_POLL_MS = 250L

        private const val CLEANUP_WAIT_MS = 4_000L
        private const val CLEANUP_POLL_MS = 50L

        private const val PROMPT_HEADER_MAX = 8
        private const val PROMPT_TAIL_MAX = 8

        private const val RESET_SESSION_AFTER_EACH_REQUEST = true
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

                val ctx = RequestCtx(tag = "SLM", requestId = requestId, modelName = model.name)
                val anchorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

                val startAt = AtomicLong(SystemClock.elapsedRealtime())
                val lastProgressAt = AtomicLong(startAt.get())
                val firstChunkAt = AtomicLong(-1L)

                val seenFinished = AtomicBoolean(false)
                val seenOnClean = AtomicBoolean(false)

                val chunks = AtomicLong(0L)
                val capturedAll = AtomicBoolean(true)

                val outLock = Any()
                val fullOut = StringBuilder(8 * 1024)

                val normalizer = StreamDeltaNormalizer(StreamDeltaNormalizer.PartialMode.AUTO)

                fun markProgress() {
                    lastProgressAt.set(SystemClock.elapsedRealtime())
                }

                fun isBusyNow(): Boolean {
                    return runCatching { SLM.isBusy(model) }
                        .onFailure { Log.w(TAG, "${ctx.prefix()} SLM.isBusy threw: ${it.message}", it) }
                        .getOrElse { true }
                }

                fun appendOutput(delta: String) {
                    if (delta.isEmpty()) return
                    if (firstChunkAt.get() < 0L) firstChunkAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    chunks.incrementAndGet()

                    synchronized(outLock) {
                        val ok = AiTrace.capAppend(fullOut, delta)
                        if (!ok) capturedAll.set(false)
                    }
                }

                fun snapshotOutput(): String = synchronized(outLock) { fullOut.toString() }

                fun buildPromptSummary(normalized: String): String {
                    val lines = normalized.split('\n')
                    val head = lines.take(PROMPT_HEADER_MAX).joinToString("\n")
                    val tail = lines.takeLast(PROMPT_TAIL_MAX).joinToString("\n")
                    val fp = AiTrace.sha256Short(normalized)
                    return buildString {
                        appendLine("${ctx.prefix()} gateWaitMs=$gateWaitMs busy=${isBusyNow()}")
                        appendLine("prompt.len=${normalized.length} lines=${lines.size} sha256_8=$fp")
                        appendLine("--- prompt.head ---")
                        appendLine(head)
                        appendLine("--- prompt.tail ---")
                        appendLine(tail)
                    }
                }

                fun finalizeOnce(reason: String, cause: Throwable? = null) {
                    if (!ctx.finalized.compareAndSet(false, true)) return

                    val now = SystemClock.elapsedRealtime()
                    val elapsedMs = now - startAt.get()
                    val firstMs = firstChunkAt.get().let { if (it < 0L) -1L else (it - startAt.get()) }
                    val outText = snapshotOutput()

                    val stats = buildString {
                        appendLine("=== SLM TRACE STATS ===")
                        appendLine("${ctx.prefix()} reason=$reason")
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
                            appendLine("... (output capture truncated by capture cap)")
                        }
                    }

                    AiTrace.logLong(
                        tag = TAG,
                        level = if (cause != null) Log.WARN else Log.DEBUG,
                        header = "${ctx.prefix()} FINALIZE: $reason",
                        body = stats,
                    )

                    val dump = buildString {
                        appendLine("=== PROMPT+OUTPUT DUMP ===")
                        appendLine("${ctx.prefix()} reason=$reason")
                        appendLine()
                        appendLine("=== PROMPT ===")
                        appendLine(prompt.normalizePrompt())
                        appendLine()
                        appendLine("=== OUTPUT ===")
                        appendLine(outText)
                        if (!capturedAll.get()) appendLine("... (output capture truncated by capture cap)")
                    }

                    // Offload file IO to avoid blocking callback threads.
                    TRACE_IO_SCOPE.launch {
                        val f = AiTrace.dumpToFile("slm", requestId, model.name, dump)
                        if (f != null) Log.d(TAG, "${ctx.prefix()} Dumped full prompt/output to: ${f.absolutePath}")
                    }
                }

                /**
                 * Close the channel at most once.
                 *
                 * NOTE:
                 * - This MUST NOT call awaitClose.
                 * - This MUST NOT attempt to register close handlers.
                 */
                fun closeChannelOnce(reason: String, action: CloseAction, cause: Throwable? = null) {
                    val first = ctx.requestClose(reason, action, cause)
                    if (!first) return

                    finalizeOnce(reason, cause)

                    // Stop watchdogs ASAP.
                    anchorScope.cancel(CancellationException("closeChannelOnce: $reason"))

                    // Close producer channel exactly once.
                    if (cause != null) out.close(cause) else out.close()
                }

                suspend fun cleanupSlm() {
                    val info = ctx.closeInfo()
                    withContext(NonCancellable) {
                        val doReset = RESET_SESSION_AFTER_EACH_REQUEST
                        when (info.action) {
                            CloseAction.SLM_CANCEL_RESET -> {
                                runCatching { SLM.cancel(model) }
                                if (doReset) runCatching { SLM.resetSession(model) }
                            }
                            CloseAction.SLM_RESET -> {
                                if (doReset) runCatching { SLM.resetSession(model) }
                            }
                            else -> {
                                if (doReset) runCatching { SLM.resetSession(model) }
                            }
                        }

                        val ok = withTimeoutOrNull(CLEANUP_WAIT_MS) {
                            while (true) {
                                if (!isBusyNow()) break
                                delay(CLEANUP_POLL_MS)
                            }
                            true
                        } != null

                        if (!ok) {
                            Log.w(TAG, "${ctx.prefix()} cleanup wait timeout (busy=${isBusyNow()}) reason=${info.reason}")
                        }
                    }
                }

                // Watchdog (hard timeout + progress stall).
                anchorScope.launch {
                    while (isActive && !ctx.closeRequested.get()) {
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - startAt.get()
                        val stalled = now - lastProgressAt.get()

                        if (elapsed >= HARD_WATCHDOG_MS) {
                            Log.w(TAG, "${ctx.prefix()} hard watchdog timeout (${elapsed}ms) → cancel/reset + close")
                            runCatching { SLM.cancel(model) }
                            runCatching { SLM.resetSession(model) }
                            closeChannelOnce("hard-watchdog-timeout", CloseAction.SLM_CANCEL_RESET)
                            break
                        }

                        if (!seenFinished.get() && stalled >= PROGRESS_STALL_MS) {
                            Log.w(TAG, "${ctx.prefix()} progress stall (${stalled}ms) → cancel/reset + close")
                            runCatching { SLM.cancel(model) }
                            runCatching { SLM.resetSession(model) }
                            closeChannelOnce("progress-stall-timeout", CloseAction.SLM_CANCEL_RESET)
                            break
                        }

                        delay(PROGRESS_POLL_MS)
                    }
                }

                try {
                    val normalizedPrompt = prompt.normalizePrompt()

                    Log.d(
                        TAG,
                        "${ctx.prefix()} request start: prompt.len=${normalizedPrompt.length}, gateWaitMs=$gateWaitMs, busy=${isBusyNow()}",
                    )

                    AiTrace.logLong(TAG, Log.DEBUG, "${ctx.prefix()} PROMPT (FULL)", normalizedPrompt)
                    Log.d(TAG, buildPromptSummary(normalizedPrompt))

                    val initErr = withTimeoutOrNull(8_000L) {
                        var err: String? = null
                        val done = AtomicBoolean(false)
                        SLM.ensureInitialized(appContext, model) { e ->
                            err = e
                            done.set(true)
                        }
                        while (!done.get()) delay(25)
                        err ?: ""
                    } ?: "SLM.ensureInitialized timed out."

                    if (initErr.isNotBlank()) {
                        Log.e(TAG, "${ctx.prefix()} ensureInitialized failed: $initErr")
                        closeChannelOnce("ensureInitialized-error", CloseAction.SLM_CANCEL_RESET, RuntimeException(initErr))
                    } else {
                        if (isBusyNow()) {
                            Log.w(TAG, "${ctx.prefix()} pre-run: engine BUSY → cancel/resetSession")
                            runCatching { SLM.cancel(model) }
                            runCatching { SLM.resetSession(model) }
                        }

                        SLM.runInference(
                            model = model,
                            input = normalizedPrompt,
                            listener = { partial, finished ->
                                if (ctx.closeRequested.get()) return@runInference

                                markProgress()

                                val delta = normalizer.toDelta(partial)
                                if (delta.isNotEmpty()) {
                                    appendOutput(delta)
                                    out.trySend(delta)
                                }

                                if (finished && !ctx.closeRequested.get()) {
                                    seenFinished.set(true)
                                    Log.d(TAG, "${ctx.prefix()} inference finished")
                                    closeChannelOnce("finished", CloseAction.SLM_RESET)
                                }
                            },
                            onClean = {
                                if (ctx.closeRequested.get()) return@runInference
                                markProgress()
                                seenOnClean.set(true)
                                Log.d(TAG, "${ctx.prefix()} onClean")
                                closeChannelOnce("onClean", CloseAction.SLM_RESET)
                            },
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "${ctx.prefix()} SLM.runInference threw: ${t.message}", t)
                    runCatching { SLM.cancel(model) }
                    runCatching { SLM.resetSession(model) }

                    if (t is CancellationException) {
                        closeChannelOnce("cancelled", CloseAction.SLM_CANCEL_RESET, null)
                    } else {
                        closeChannelOnce("exception", CloseAction.SLM_CANCEL_RESET, t)
                    }
                } finally {
                    awaitClose {
                        if (!ctx.closeRequested.get()) {
                            Log.d(TAG, "${ctx.prefix()} awaitClose: collector-cancel → cancel/reset + close")
                            runCatching { SLM.cancel(model) }
                            runCatching { SLM.resetSession(model) }
                            closeChannelOnce("collector-cancel", CloseAction.SLM_CANCEL_RESET)
                        }
                    }

                    try {
                        cleanupSlm()
                    } catch (e: Throwable) {
                        Log.w(TAG, "${ctx.prefix()} cleanupSlm failed: ${e.message}", e)
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
    appContext: Context? = null,
) : Repository {

    init {
        if (appContext != null) {
            AiTrace.install(appContext)
        }
    }

    companion object {
        private const val TAG = "LiteRtRepository"
        private val REQ_SEQ = AtomicLong(0L)

        private const val HARD_WATCHDOG_MS = 20_000L
        private const val PROGRESS_STALL_MS = 6_000L
        private const val PROGRESS_POLL_MS = 250L

        /**
         * How long we wait for LiteRtLM.cleanUp() completion callback.
         * This is a best-effort wait to avoid releasing the global inference gate too early.
         */
        private const val CLEANUP_WAIT_MS = 8_000L
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

                val ctx = RequestCtx(tag = "LITERT", requestId = requestId, modelName = model.name)
                val anchorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

                // IMPORTANT:
                // - streamDone: "stream terminated" (done signal)
                // - cleanupDone: "native objects cleanup finished" (LiteRtLM.cleanUp onDone)
                val streamDone = CompletableDeferred<Unit>()
                val cleanupDone = CompletableDeferred<Unit>()
                val cleanupStarted = AtomicBoolean(false)

                val startAt = AtomicLong(SystemClock.elapsedRealtime())
                val lastProgressAt = AtomicLong(startAt.get())
                val firstChunkAt = AtomicLong(-1L)

                val seenFinished = AtomicBoolean(false)

                val chunks = AtomicLong(0L)
                val capturedAll = AtomicBoolean(true)

                val outLock = Any()
                val fullOut = StringBuilder(8 * 1024)

                val normalizer = StreamDeltaNormalizer(StreamDeltaNormalizer.PartialMode.AUTO)

                fun markProgress() {
                    lastProgressAt.set(SystemClock.elapsedRealtime())
                }

                fun appendOutput(delta: String) {
                    if (delta.isEmpty()) return
                    if (firstChunkAt.get() < 0L) firstChunkAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    chunks.incrementAndGet()
                    synchronized(outLock) {
                        val ok = AiTrace.capAppend(fullOut, delta)
                        if (!ok) capturedAll.set(false)
                    }
                }

                fun snapshotOutput(): String = synchronized(outLock) { fullOut.toString() }

                fun finalizeOnce(reason: String, cause: Throwable? = null) {
                    if (!ctx.finalized.compareAndSet(false, true)) return

                    val now = SystemClock.elapsedRealtime()
                    val elapsedMs = now - startAt.get()
                    val firstMs = firstChunkAt.get().let { if (it < 0L) -1L else (it - startAt.get()) }

                    val normalizedPrompt = prompt.normalizePrompt()
                    val outText = snapshotOutput()

                    val stats = buildString {
                        appendLine("=== LITERT TRACE STATS ===")
                        appendLine("${ctx.prefix()} reason=$reason")
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
                        if (!capturedAll.get()) appendLine("\n... (output capture truncated by capture cap)")
                    }

                    AiTrace.logLong(
                        tag = TAG,
                        level = if (cause != null) Log.WARN else Log.DEBUG,
                        header = "${ctx.prefix()} FINALIZE: $reason",
                        body = stats,
                    )

                    TRACE_IO_SCOPE.launch {
                        AiTrace.dumpToFile("litert", requestId, model.name, stats)
                    }
                }

                fun triggerCleanup(tag: String) {
                    if (cleanupDone.isCompleted) return
                    if (!cleanupStarted.compareAndSet(false, true)) return

                    runCatching {
                        LiteRtLM.cleanUp(model) {
                            Log.d(TAG, "${ctx.prefix()} LiteRtLM cleanUp finished ($tag)")
                            if (!cleanupDone.isCompleted) cleanupDone.complete(Unit)
                        }
                    }.onFailure {
                        Log.w(TAG, "${ctx.prefix()} LiteRtLM.cleanUp failed ($tag): ${it.message}", it)
                        if (!cleanupDone.isCompleted) cleanupDone.complete(Unit)
                    }
                }

                /**
                 * Close the channel at most once.
                 *
                 * NOTE:
                 * - Must not call awaitClose.
                 * - Must be safe under callback races.
                 */
                fun closeChannelOnce(reason: String, action: CloseAction, cause: Throwable? = null) {
                    val first = ctx.requestClose(reason, action, cause)
                    if (!first) return

                    finalizeOnce(reason, cause)

                    // Unblock cleanup waiters.
                    if (!streamDone.isCompleted) streamDone.complete(Unit)

                    // Stop watchdogs ASAP.
                    anchorScope.cancel(CancellationException("closeChannelOnce: $reason"))

                    if (cause != null) out.close(cause) else out.close()
                }

                suspend fun cleanupLiteRt() {
                    val info = ctx.closeInfo()
                    withContext(NonCancellable) {
                        // Ensure the stream is terminated before triggering cleanup, best-effort.
                        withTimeoutOrNull(1_000L) { streamDone.await() }

                        triggerCleanup("finally/${info.reason}")

                        val ok = withTimeoutOrNull(CLEANUP_WAIT_MS) {
                            cleanupDone.await()
                            true
                        } != null

                        if (!ok) {
                            Log.w(TAG, "${ctx.prefix()} cleanup wait timeout reason=${info.reason}")
                        }
                    }
                }

                // Watchdog (hard timeout + progress stall).
                anchorScope.launch {
                    while (isActive && !ctx.closeRequested.get()) {
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - startAt.get()
                        val stalled = now - lastProgressAt.get()

                        if (elapsed >= HARD_WATCHDOG_MS) {
                            Log.w(TAG, "${ctx.prefix()} hard watchdog timeout (${elapsed}ms) → cleanUp/close")
                            triggerCleanup("hard-watchdog")
                            closeChannelOnce("hard-watchdog-timeout", CloseAction.LITERT_CLEANUP)
                            break
                        }

                        if (!seenFinished.get() && stalled >= PROGRESS_STALL_MS) {
                            Log.w(TAG, "${ctx.prefix()} progress stall (${stalled}ms) → cleanUp/close")
                            triggerCleanup("progress-stall")
                            closeChannelOnce("progress-stall-timeout", CloseAction.LITERT_CLEANUP)
                            break
                        }

                        delay(PROGRESS_POLL_MS)
                    }
                }

                try {
                    val normalizedPrompt = prompt.normalizePrompt()

                    Log.d(TAG, "${ctx.prefix()} request start: prompt.len=${normalizedPrompt.length}, gateWaitMs=$gateWaitMs")
                    AiTrace.logLong(TAG, Log.DEBUG, "${ctx.prefix()} PROMPT (FULL)", normalizedPrompt)

                    LiteRtLM.runInference(
                        model = model,
                        input = normalizedPrompt,
                        resultListener = { partial, finished ->
                            if (ctx.closeRequested.get()) return@runInference

                            markProgress()

                            val delta = normalizer.toDelta(partial)
                            if (delta.isNotEmpty()) {
                                appendOutput(delta)
                                out.trySend(delta)
                            }

                            if (finished && !ctx.closeRequested.get()) {
                                seenFinished.set(true)
                                // Stream termination signal (NOT native cleanup completion).
                                if (!streamDone.isCompleted) streamDone.complete(Unit)
                                closeChannelOnce("finished", CloseAction.LITERT_CLEANUP)
                            }
                        },
                        cleanUpListener = {
                            // IMPORTANT: This is a "stream terminated" hook, not "native cleanup done".
                            markProgress()
                            if (!streamDone.isCompleted) streamDone.complete(Unit)

                            if (!ctx.closeRequested.get()) {
                                closeChannelOnce("stream-terminated", CloseAction.LITERT_CLEANUP)
                            }
                        },
                        onError = { message ->
                            markProgress()
                            if (!streamDone.isCompleted) streamDone.complete(Unit)
                            triggerCleanup("onError")
                            closeChannelOnce("litert-error", CloseAction.LITERT_CLEANUP, RuntimeException(message))
                        },
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "${ctx.prefix()} LiteRtLM.runInference threw: ${t.message}", t)
                    if (!streamDone.isCompleted) streamDone.complete(Unit)

                    triggerCleanup("exception")

                    if (t is CancellationException) {
                        closeChannelOnce("cancelled", CloseAction.LITERT_CLEANUP, null)
                    } else {
                        closeChannelOnce("exception", CloseAction.LITERT_CLEANUP, t)
                    }
                } finally {
                    awaitClose {
                        if (!ctx.closeRequested.get()) {
                            if (!streamDone.isCompleted) streamDone.complete(Unit)
                            triggerCleanup("collector-cancel")
                            closeChannelOnce("collector-cancel", CloseAction.LITERT_CLEANUP)
                        }
                    }

                    try {
                        cleanupLiteRt()
                    } catch (e: Throwable) {
                        Log.w(TAG, "${ctx.prefix()} cleanupLiteRt failed: ${e.message}", e)
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

enum class OutputKeyStyle {
    LEGACY,
    SNAKE_CASE,
}

enum class TwoStepStage {
    EVAL,
    FOLLOW_UP,
}

sealed class AiRequestMode {

    data class SingleStep(
        val passThroughStreaming: Boolean = true,
    ) : AiRequestMode()

    data class DoubleStep(
        val options: TwoStepOptions = TwoStepOptions(),
    ) : AiRequestMode()
}

data class TwoStepOptions(
    val evalOkScoreThreshold: Int = 85,
    val skipFollowupWhenOk: Boolean = true,
    val followupOnEvalParseError: Boolean = true,
    val outputKeyStyle: OutputKeyStyle = OutputKeyStyle.LEGACY,
    val emitEvalChunks: Boolean = false,
    val emitFollowupChunks: Boolean = false,
    val emitFinalMergedJson: Boolean = true,
    val followupOverridesAllFields: Boolean = false,
    val includeGatingFieldsInFinal: Boolean = false,
    val includeMetaInFinal: Boolean = false,
)

/**
 * Debug helper: annotate userPrompt with stage markers for log readability.
 *
 * NOTE:
 * - This does NOT affect the model prompt prefix/suffix contract.
 * - It only helps logcat/file trace show EVAL vs FOLLOW_UP.
 */
private fun tagUserPromptForStage(stage: TwoStepStage, userPrompt: String): String {
    val marker = when (stage) {
        TwoStepStage.EVAL -> "=== TWO_STEP_STAGE: EVAL ==="
        TwoStepStage.FOLLOW_UP -> "=== TWO_STEP_STAGE: FOLLOW_UP ==="
    }
    return compactJoin(marker, userPrompt)
}

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

            val evalUserPrompt = buildEvalUserPrompt(
                baseUserPrompt = userPrompt,
                threshold = opts.evalOkScoreThreshold,
                keyStyle = opts.outputKeyStyle,
            )

            val evalPrompt = buildPrompt(tagUserPromptForStage(TwoStepStage.EVAL, evalUserPrompt))

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
                if (!opts.followupOnEvalParseError) {
                    if (opts.emitFinalMergedJson) {
                        emit(wrapAsFallbackJson(evalRaw, opts.outputKeyStyle, error = "eval_json_parse_failed"))
                    }
                    return@flow
                }
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

            val followUserPrompt = buildFollowupUserPrompt(
                baseUserPrompt = userPrompt,
                evalMissingHint = evalParsed?.missing,
                keyStyle = opts.outputKeyStyle,
            )

            val followPrompt = buildPrompt(tagUserPromptForStage(TwoStepStage.FOLLOW_UP, followUserPrompt))

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

private fun decideNeedsFollowup(eval: EvalResult, threshold: Int): Boolean {
    eval.needsFollowup?.let { return it }
    val score = eval.score
    if (score != null) return score < threshold
    return true
}

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
