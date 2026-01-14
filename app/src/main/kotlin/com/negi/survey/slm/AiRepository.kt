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

@file:Suppress("MemberVisibilityCanBePrivate")

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * - Implementations must ensure no unsafe concurrent JNI calls happen.
     * - Canceling the collection should stop the underlying engine best-effort.
     */
    suspend fun request(prompt: String): Flow<String>

    /**
     * Best-effort cancellation of the currently running inference (if any).
     *
     * IMPORTANT:
     * - Safe to call multiple times.
     * - Must never crash even if already idle.
     */
    fun cancelActive()

    /**
     * Build the full model-ready prompt string from a user-level [userPrompt].
     */
    fun buildPrompt(userPrompt: String): String
}

/* ====================================================================== */
/*  Process-wide inference serialization                                   */
/* ====================================================================== */

/**
 * Global mutex to serialize ALL native/engine interactions process-wide.
 *
 * This is intentionally heavy-handed to avoid JNI races across different backends.
 */
private val AI_PROCESS_LOCK = Mutex()

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
        sb.append(chunk.take(remaining))
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

/**
 * YAML-driven prompt builder used by all repositories.
 */
private object PromptBuilder {

    private const val DEF_USER_TURN_PREFIX = "<start_of_turn>user"
    private const val DEF_MODEL_TURN_PREFIX = "<start_of_turn>model"
    private const val DEF_TURN_END = "<end_of_turn>"
    private const val DEF_EMPTY_JSON_INSTRUCTION = "Respond with an empty JSON object: {}"
    private const val DEF_PREAMBLE = "You are a well-known farmer survey expert. Read the Question and the Answer."
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

    fun build(config: SurveyConfig, userPrompt: String): String {
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

        return compactJoin(
            userTurn,
            userBlock,
            turnEnd,
            modelTurn,
        )
    }
}

/* ====================================================================== */
/*  Shared streaming skeleton (single terminate path)                      */
/* ====================================================================== */

private object StreamRunner {

    private const val TAG = "AiStreamRunner"

    data class Params(
        val backendTag: String,
        val modelName: String,
        val requestId: Long,
        val prompt: String,
        val gateWaitMs: Long,
        val hardWatchdogMs: Long,
        val progressStallMs: Long,
        val progressPollMs: Long,
        val postCloseQuiesceMs: Long,
    )

    /**
     * Start an async stream.
     *
     * Implementations must:
     * - Invoke [onChunk] any number of times.
     * - Invoke [onTerminal] at most once (runner dedupes anyway).
     * - Invoke [onError] at most once (runner dedupes anyway).
     */
    interface Starter {
        fun startStream(
            onChunk: (String) -> Unit,
            onTerminal: () -> Unit,
            onError: (Throwable) -> Unit,
        )
    }

    /**
     * Returns a cold Flow. Collection runs the stream.
     *
     * - All JNI/engine calls must be serialized by [AI_PROCESS_LOCK].
     * - Termination is unified through terminateOnce().
     */
    fun run(
        params: Params,
        starter: Starter,
        cancelActive: () -> Unit,
        postCloseCleanup: suspend (reason: String) -> Unit,
    ): Flow<String> = callbackFlow {
        val out = this

        val closed = AtomicBoolean(false)
        val finalized = AtomicBoolean(false)

        val startAt = AtomicLong(SystemClock.elapsedRealtime())
        val lastProgressAt = AtomicLong(startAt.get())
        val firstChunkAt = AtomicLong(-1L)

        val chunks = AtomicLong(0L)
        val capturedAll = AtomicBoolean(true)
        val fullOut = StringBuilder(8 * 1024)

        val normalizedPrompt = params.prompt.normalizePrompt()
        val promptHash = AiTrace.sha256Short(normalizedPrompt)

        fun markProgress() {
            lastProgressAt.set(SystemClock.elapsedRealtime())
        }

        fun appendOutput(chunk: String) {
            if (chunk.isEmpty()) return
            if (firstChunkAt.get() < 0L) firstChunkAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
            chunks.incrementAndGet()
            val ok = AiTrace.capAppend(fullOut, chunk)
            if (!ok) capturedAll.set(false)
        }

        fun finalizeOnce(reason: String, cause: Throwable? = null) {
            if (!finalized.compareAndSet(false, true)) return

            val now = SystemClock.elapsedRealtime()
            val elapsedMs = now - startAt.get()
            val firstMs = firstChunkAt.get().let { if (it < 0L) -1L else (it - startAt.get()) }
            val outText = fullOut.toString()

            val stats = buildString {
                appendLine("=== AI STREAM TRACE ===")
                appendLine("backend=${params.backendTag} rid=${params.requestId} model='${params.modelName}' reason=$reason")
                appendLine("prompt.sha256_8=$promptHash prompt.len=${normalizedPrompt.length}")
                appendLine("gateWaitMs=${params.gateWaitMs} elapsedMs=$elapsedMs firstChunkMs=$firstMs")
                appendLine("chunks=${chunks.get()} capturedAll=${capturedAll.get()} out.len=${outText.length}")
                if (cause != null) {
                    appendLine("--- exception ---")
                    appendLine(Log.getStackTraceString(cause))
                }
                appendLine("=== PROMPT (FULL) ===")
                appendLine(normalizedPrompt)
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
                header = "[${params.backendTag}][${params.requestId}] FINALIZE: $reason",
                body = stats,
            )

            AiTrace.dumpToFile(params.backendTag, params.requestId, params.modelName, stats)
        }

        // The ONLY termination gateway.
        fun terminateOnce(reason: String, cause: Throwable? = null) {
            if (!closed.compareAndSet(false, true)) return

            markProgress()
            finalizeOnce(reason, cause)

            out.close(cause)
        }

        // Watchdogs live inside the flow scope (cancelled automatically on close).
        val watchdogJob = launch(Dispatchers.Default) {
            while (isActive && !closed.get()) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - startAt.get()
                val stalled = now - lastProgressAt.get()

                if (elapsed >= params.hardWatchdogMs) {
                    Log.w(TAG, "[${params.backendTag}][${params.requestId}] hard timeout (${elapsed}ms) → cancel/terminate")
                    cancelActive()
                    terminateOnce("hard-watchdog-timeout")
                    break
                }

                if (stalled >= params.progressStallMs) {
                    Log.w(TAG, "[${params.backendTag}][${params.requestId}] stall timeout (${stalled}ms) → cancel/terminate")
                    cancelActive()
                    terminateOnce("progress-stall-timeout")
                    break
                }

                delay(params.progressPollMs)
            }
        }

        // We must start the backend stream under the global process lock.
        // IMPORTANT:
        // - We do NOT "collect another flow inside callbackFlow".
        // - We start the stream and return immediately.
        // - The flow stays open until terminateOnce() is called.
        launch(Dispatchers.IO) {
            AI_PROCESS_LOCK.withLock {
                if (closed.get()) return@withLock

                runCatching {
                    Log.d(
                        TAG,
                        "[${params.backendTag}][${params.requestId}] start model='${params.modelName}' prompt.len=${normalizedPrompt.length} gateWaitMs=${params.gateWaitMs}",
                    )

                    starter.startStream(
                        onChunk = { chunk ->
                            if (closed.get()) return@startStream
                            if (chunk.isNotEmpty()) {
                                markProgress()
                                appendOutput(chunk)
                                if (!out.isClosedForSend) out.trySend(chunk)
                            }
                        },
                        onTerminal = {
                            if (closed.get()) return@startStream
                            terminateOnce("terminal")
                        },
                        onError = { t ->
                            if (closed.get()) return@startStream
                            cancelActive()
                            terminateOnce("error", t)
                        },
                    )
                }.onFailure { t ->
                    cancelActive()
                    terminateOnce("exception", t)
                }
            }
        }

        awaitClose {
            runCatching { watchdogJob.cancel() }

            // If collector cancels before terminal, best-effort cancel the backend.
            if (!closed.get()) {
                cancelActive()
                terminateOnce("collector-cancel")
            }

            // Post-close cleanup (best-effort) happens after we close.
            launch(Dispatchers.IO) {
                runCatching {
                    // Quiesce wait: reduces late-callback-vs-cleanup races for JNI backends.
                    withTimeoutOrNull(params.postCloseQuiesceMs) {
                        delay(params.postCloseQuiesceMs)
                        true
                    }
                    postCloseCleanup("post-close")
                }.onFailure { e ->
                    Log.w(TAG, "[${params.backendTag}][${params.requestId}] postCloseCleanup failed: ${e.message}", e)
                }
            }
        }
    }
        .buffer(Channel.BUFFERED)
        .flowOn(Dispatchers.IO)
}

/* ====================================================================== */
/*  SLM (MediaPipe) backend                                               */
/* ====================================================================== */

class SlmDirectRepository(
    private val model: Model,
    private val config: SurveyConfig,
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"
        private val REQ_SEQ = AtomicLong(0L)

        private const val HARD_WATCHDOG_MS = 20_000L
        private const val PROGRESS_STALL_MS = 6_000L
        private const val PROGRESS_POLL_MS = 250L

        private const val POST_CLOSE_QUIESCE_MS = 250L
    }

    override fun cancelActive() {
        runCatching { SLM.cancel(model) }
            .onFailure { Log.w(TAG, "cancelActive: SLM.cancel failed: ${it.message}", it) }
    }

    override fun buildPrompt(userPrompt: String): String {
        val finalPrompt = PromptBuilder.build(config, userPrompt)
        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${finalPrompt.length}")
        return finalPrompt
    }

    override suspend fun request(prompt: String): Flow<String> {
        val requestId = REQ_SEQ.incrementAndGet()
        val gateReqAt = SystemClock.elapsedRealtime()

        // gateWaitMs represents: how long we waited to ENTER the global process lock.
        // We compute it when the stream actually starts (inside the lock).
        // For trace, we compute a conservative approximation here too.
        val optimisticGateWait = 0L

        val params = StreamRunner.Params(
            backendTag = "slm",
            modelName = model.name,
            requestId = requestId,
            prompt = prompt,
            gateWaitMs = optimisticGateWait,
            hardWatchdogMs = HARD_WATCHDOG_MS,
            progressStallMs = PROGRESS_STALL_MS,
            progressPollMs = PROGRESS_POLL_MS,
            postCloseQuiesceMs = POST_CLOSE_QUIESCE_MS,
        )

        return StreamRunner.run(
            params = params.copy(gateWaitMs = SystemClock.elapsedRealtime() - gateReqAt),
            starter = object : StreamRunner.Starter {
                override fun startStream(
                    onChunk: (String) -> Unit,
                    onTerminal: () -> Unit,
                    onError: (Throwable) -> Unit,
                ) {
                    // IMPORTANT: Never close/reset here. Only signal terminal.
                    SLM.runInference(
                        model = model,
                        input = prompt.normalizePrompt(),
                        listener = { partial, finished ->
                            if (partial.isNotEmpty()) onChunk(partial)
                            if (finished) onTerminal()
                        },
                        onClean = {
                            // Treat onClean as terminal too, runner dedupes.
                            onTerminal()
                        },
                    )
                }
            },
            cancelActive = { cancelActive() },
            postCloseCleanup = { _ ->
                // Reset session ONLY after flow is closed and after a short quiesce.
                runCatching { SLM.resetSession(model) }
                    .onFailure { Log.w(TAG, "[$requestId] resetSession failed: ${it.message}", it) }
            },
        )
    }
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

        // Longer quiesce: LiteRT async callbacks can arrive slightly late.
        private const val POST_CLOSE_QUIESCE_MS = 750L

        private const val CLEANUP_WAIT_MS = 2_000L
    }

    override fun cancelActive() {
        runCatching { LiteRtLM.cancel(model) }
            .onFailure { Log.w(TAG, "cancelActive: LiteRtLM.cancel failed: ${it.message}", it) }
    }

    override fun buildPrompt(userPrompt: String): String {
        val finalPrompt = PromptBuilder.build(config, userPrompt)
        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${finalPrompt.length}")
        return finalPrompt
    }

    override suspend fun request(prompt: String): Flow<String> {
        val requestId = REQ_SEQ.incrementAndGet()
        val gateReqAt = SystemClock.elapsedRealtime()

        val params = StreamRunner.Params(
            backendTag = "litert",
            modelName = model.name,
            requestId = requestId,
            prompt = prompt,
            gateWaitMs = 0L,
            hardWatchdogMs = HARD_WATCHDOG_MS,
            progressStallMs = PROGRESS_STALL_MS,
            progressPollMs = PROGRESS_POLL_MS,
            postCloseQuiesceMs = POST_CLOSE_QUIESCE_MS,
        )

        return StreamRunner.run(
            params = params.copy(gateWaitMs = SystemClock.elapsedRealtime() - gateReqAt),
            starter = object : StreamRunner.Starter {
                override fun startStream(
                    onChunk: (String) -> Unit,
                    onTerminal: () -> Unit,
                    onError: (Throwable) -> Unit,
                ) {
                    // IMPORTANT:
                    // - LiteRtLM.runInference guarantees terminal via resultListener(done=true).
                    // - cleanUpListener is a hook only; do not treat it as terminal.
                    LiteRtLM.runInference(
                        model = model,
                        input = prompt.normalizePrompt(),
                        resultListener = { partial, done ->
                            if (partial.isNotEmpty()) onChunk(partial)
                            if (done) onTerminal()
                        },
                        cleanUpListener = {
                            // Hook only.
                        },
                        onError = { message ->
                            onError(RuntimeException(message))
                        },
                    )
                }
            },
            cancelActive = { cancelActive() },
            postCloseCleanup = { _ ->
                // Cleanup ONLY once, after flow is closed and after quiesce.
                // Best-effort wait for completion (non-blocking overall).
                val done = AtomicBoolean(false)
                LiteRtLM.cleanUp(model) { done.set(true) }

                withTimeoutOrNull(CLEANUP_WAIT_MS) {
                    while (isActive && !done.get()) delay(50L)
                    true
                }
            },
        )
    }
}
