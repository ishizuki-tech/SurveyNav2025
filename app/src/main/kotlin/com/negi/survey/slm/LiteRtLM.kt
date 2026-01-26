/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: LiteRtLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "LiteRtLM"

/** Upper bound for error strings rendered in UI/log aggregation. */
private const val ERROR_MAX_CHARS = 240

/**
 * Defaults:
 * - LiteRT-LM models in the official table commonly have context size 4096.
 * - Some models (e.g., FunctionGemma-270M) are 1024.
 *
 * We keep a conservative ABS clamp and derive per-model default when config is missing.
 */
private const val ABS_MAX_NUM_TOKENS = 4096
private const val DEFAULT_TOPK = 40
private const val DEFAULT_TOPP = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/**
 * Idle cleanup delay:
 * - Calling cleanUp() schedules a deferred cleanup instead of immediate teardown.
 * - Any new init/runInference cancels the scheduled cleanup.
 *
 * WHY:
 * - Prevents "rid=1 cleaned up -> rid=2 starts -> not initialized" races.
 * - Greatly reduces back-to-back native teardown / init churn.
 */
private const val IDLE_CLEANUP_MS = 12_000L

/**
 * Grace windows:
 * - After we "logically terminate" (cancel/watchdog), allow some time before closing native objects.
 * - This reduces late-callback-vs-close races.
 */
private const val CLOSE_GRACE_MS = 5_000L
private const val RETIRED_CLOSE_GRACE_MS = 1_500L

/**
 * Post-terminate cooldown:
 * - Even if onDone() arrived, native teardown may still be in progress.
 * - We delay the next start a little to reduce back-to-back SIGSEGV risk.
 */
private const val POST_TERMINATE_COOLDOWN_MS = 250L

/**
 * Streaming debug toggles.
 *
 * Keep these OFF in production builds; they are noisy and can be expensive.
 */
private const val DEBUG_STREAM = true
private const val DEBUG_STREAM_EVERY_N = 16
private const val DEBUG_PREFIX_CHARS = 20

/**
 * Simple holder for a LiteRT-LM [Engine] and its active [Conversation].
 *
 * IMPORTANT:
 * - Never close [engine]/[conversation] while a stream callback may still arrive.
 * - Cleanup must be deferred until the stream is fully terminated (or a guarded forced terminate).
 *
 * @property supportImage The engine was initialized with vision backend enabled if true.
 * @property supportAudio The engine was initialized with audio backend enabled if true.
 * @property engineConfigSnapshot Snapshot for debug/logging and mismatch warnings.
 */
data class LiteRtLmInstance(
    val engine: Engine,
    @Volatile var conversation: Conversation,
    val supportImage: Boolean,
    val supportAudio: Boolean,
    val engineConfigSnapshot: EngineConfig,
)

/**
 * High-level object for LiteRT-LM integration.
 *
 * Design goals (crash prevention):
 * - Serialize state transitions with [stateMutex].
 * - Ensure a single active stream per runtime key.
 * - Ignore late callbacks using a monotonically increasing runId.
 * - Defer cleanup/reset/replace until after stream termination.
 * - Provide a "logical cancel" (SDK may not truly cancel).
 *
 * Maintenance goals:
 * - Make cleanUp() safe even if called after every request.
 * - Centralize teardown policy (idle cleanup + cancellation).
 */
object LiteRtLM {

    /** Main thread handler for UI-safe callbacks. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /** Dedicated IO scope for init/cleanup work. */
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Global lock for instance map + lifecycle transitions. */
    private val stateMutex: Mutex = Mutex()

    /** Per-runtime LiteRT-LM runtime instances keyed by runtimeKey(model). */
    private val instances: MutableMap<String, LiteRtLmInstance> = ConcurrentHashMap()

    /** Pending actions to execute once the active stream terminates. */
    private val pendingAfterStream: MutableMap<String, MutableList<() -> Unit>> = ConcurrentHashMap()

    /** Prevent concurrent init for the same key. */
    private val initInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Mutex to serialize initializeIfNeeded() and generateText() calls.
     * Streaming runInference() is guarded per-key by RunState.active.
     */
    private val apiMutex: Mutex = Mutex()

    /** Busy flag used only by generateText() (suspend API). */
    private val busy: AtomicBoolean = AtomicBoolean(false)

    /** Scheduled idle cleanup jobs (per key). */
    private val cleanupJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /** Returns `true` when a generateText call is currently in progress. */
    fun isBusy(): Boolean = busy.get()

    /** Stable runtime key for instances and run states. */
    private fun runtimeKey(model: Model): String = model.name

    /** Post work onto the main thread. */
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Per-key run state (stream lifecycle + runId + cancellation). */
    private data class RunState(
        val active: AtomicBoolean = AtomicBoolean(false),
        val terminated: AtomicBoolean = AtomicBoolean(false),
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        val runId: AtomicLong = AtomicLong(0L),

        /** Monotonic timestamp (elapsedRealtime) when terminateOnce() ran. */
        val lastTerminateAtMs: AtomicLong = AtomicLong(0L),

        /** Monotonic timestamp (elapsedRealtime) of last "use" (init/run/reset). */
        val lastUseAtMs: AtomicLong = AtomicLong(0L),

        /** Do not allow a new start until this monotonic time (elapsedRealtime). */
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),

        /** Best-effort logical terminator installed by runInference(). */
        val terminator: AtomicReference<(() -> Unit)?> = AtomicReference(null),

        /**
         * Cleanup generation token.
         *
         * IMPORTANT:
         * - Idle cleanup captures a token value. It must match at close time.
         * - Any "use" (init/run/reset) increments this to invalidate stale cleanups.
         * - This prevents races where a cleanup job passes pre-checks and then closes
         *   right when a new run begins.
         */
        val cleanupToken: AtomicLong = AtomicLong(0L),
    )

    private val runStates: ConcurrentHashMap<String, RunState> = ConcurrentHashMap()

    /** Get or create per-key run state (thread-safe). */
    private fun getRunState(key: String): RunState {
        val existing = runStates[key]
        if (existing != null) return existing
        val created = RunState()
        val prev = runStates.putIfAbsent(key, created)
        return prev ?: created
    }

    /**
     * Touch last-use time and invalidate any scheduled cleanup.
     *
     * WHY:
     * - Even if a cleanup Job cannot be cancelled in time, token invalidation
     *   ensures it becomes a no-op at the final close check.
     */
    private fun markUsed(key: String) {
        val now = SystemClock.elapsedRealtime()
        val rs = getRunState(key)
        rs.lastUseAtMs.set(now)
        rs.cleanupToken.incrementAndGet()
    }

    /** Cancel any scheduled idle cleanup for this key. */
    private fun cancelScheduledCleanup(key: String, reason: String) {
        val job = cleanupJobs.remove(key)
        if (job != null) {
            job.cancel()
            Log.d(TAG, "Idle cleanup cancelled: key='$key' reason='$reason'")
        }
    }

    /**
     * Schedule an idle cleanup:
     * - Debounced: cancels previous scheduled cleanup for this key.
     * - Safe: checks lastUse/active/initInFlight before closing native objects.
     * - Token-guarded: will not close if a newer "use" happened.
     */
    private fun scheduleIdleCleanup(key: String, delayMs: Long, reason: String) {
        cancelScheduledCleanup(key, "reschedule:$reason")

        val tokenAtSchedule = getRunState(key).cleanupToken.get()

        val job = ioScope.launch {
            Log.d(TAG, "Idle cleanup scheduled: key='$key' in ${delayMs}ms reason='$reason'")
            delay(delayMs)

            // Close under lock with token + idleness checks.
            closeInstanceIfStillIdle(
                key = key,
                requiredIdleMs = delayMs,
                requiredToken = tokenAtSchedule,
                reason = "idle:$reason"
            )
        }

        cleanupJobs[key] = job
    }

    /**
     * Close and remove an instance NOW (best-effort).
     *
     * IMPORTANT:
     * - This method is the only place that removes from instances + closes native objects.
     * - It respects CLOSE_GRACE_MS after termination to avoid late-callback-vs-close races.
     */
    private suspend fun closeInstanceNowBestEffort(key: String, reason: String) {
        val instance: LiteRtLmInstance? = stateMutex.withLock {
            // Refuse to close while streaming.
            val rs = getRunState(key)
            if (rs.active.get()) return@withLock null
            pendingAfterStream.remove(key)
            instances.remove(key)
        }

        if (instance == null) {
            Log.d(TAG, "closeInstanceNowBestEffort: nothing to close (or active): key='$key' reason='$reason'")
            return
        }

        val rs = getRunState(key)
        val now = SystemClock.elapsedRealtime()
        val sinceTerminate = now - rs.lastTerminateAtMs.get()
        val extraDelay = if (sinceTerminate in 0..CLOSE_GRACE_MS) (CLOSE_GRACE_MS - sinceTerminate) else 0L
        if (extraDelay > 0) delay(extraDelay)

        runCatching { instance.conversation.close() }
            .onFailure { Log.e(TAG, "Failed to close conversation: key='$key' reason='$reason' err=${it.message}", it) }
        runCatching { instance.engine.close() }
            .onFailure { Log.e(TAG, "Failed to close engine: key='$key' reason='$reason' err=${it.message}", it) }

        Log.d(TAG, "LiteRT-LM closed: key='$key' reason='$reason'")
    }

    /**
     * Token + idleness guarded closer for idle cleanup.
     *
     * It will only close if:
     * - No active stream
     * - No init in flight
     * - Still idle for >= requiredIdleMs
     * - cleanupToken has not changed since scheduling
     */
    private suspend fun closeInstanceIfStillIdle(
        key: String,
        requiredIdleMs: Long,
        requiredToken: Long,
        reason: String,
    ) {
        val (instance, now, idleFor, tokenNow) = stateMutex.withLock {
            val rs = getRunState(key)
            val nowInner = SystemClock.elapsedRealtime()
            val idleForInner = nowInner - rs.lastUseAtMs.get()
            val tokenInner = rs.cleanupToken.get()

            if (rs.active.get()) {
                Log.d(TAG, "Idle cleanup skipped (active stream): key='$key'")
                return
            }
            if (initInFlight.contains(key)) {
                Log.d(TAG, "Idle cleanup skipped (init in flight): key='$key'")
                return
            }
            if (tokenInner != requiredToken) {
                Log.d(TAG, "Idle cleanup skipped (token changed): key='$key' required=$requiredToken now=$tokenInner")
                return
            }
            if (idleForInner < requiredIdleMs) {
                Log.d(TAG, "Idle cleanup skipped (recent use): key='$key' idleFor=${idleForInner}ms < ${requiredIdleMs}ms")
                return
            }

            pendingAfterStream.remove(key)
            val inst = instances.remove(key)
            if (inst == null) {
                Log.d(TAG, "Idle cleanup: nothing to close: key='$key'")
                return
            }

            Quad(inst, nowInner, idleForInner, tokenInner)
        } ?: return

        // Apply close grace based on lastTerminateAtMs.
        val rs = getRunState(key)
        val sinceTerminate = now - rs.lastTerminateAtMs.get()
        val extraDelay = if (sinceTerminate in 0..CLOSE_GRACE_MS) (CLOSE_GRACE_MS - sinceTerminate) else 0L
        if (extraDelay > 0) delay(extraDelay)

        runCatching { instance.conversation.close() }
            .onFailure { Log.e(TAG, "Failed to close conversation: key='$key' reason='$reason' err=${it.message}", it) }
        runCatching { instance.engine.close() }
            .onFailure { Log.e(TAG, "Failed to close engine: key='$key' reason='$reason' err=${it.message}", it) }

        Log.d(TAG, "LiteRT-LM closed: key='$key' reason='$reason' idleFor=${idleFor}ms token=$tokenNow")
    }

    /** Small tuple helper (avoids Pair nesting). */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Normalize accelerator string for stable backend selection. */
    private fun normalizedAccelerator(model: Model): String {
        return model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)
            .trim()
            .uppercase(Locale.US)
            .ifBlank { Accelerator.GPU.label }
    }

    /** Resolve preferred backend from model config. */
    private fun preferredBackend(model: Model): Backend {
        return when (normalizedAccelerator(model)) {
            Accelerator.CPU.label -> Backend.CPU
            Accelerator.GPU.label -> Backend.GPU
            else -> Backend.GPU
        }
    }

    /** Sanitize TopK - must be >= 1. */
    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)

    /** Sanitize TopP - must be in [0, 1]. */
    private fun sanitizeTopP(p: Float): Float = p.takeIf { it in 0f..1f } ?: DEFAULT_TOPP

    /** Sanitize Temperature - typical safe band [0, 2]. */
    private fun sanitizeTemperature(t: Float): Float = t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    /** Clean and compress error messages for UI/logging. */
    private fun cleanError(msg: String?): String {
        return msg
            ?.replace("INTERNAL:", "", ignoreCase = true)
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.take(ERROR_MAX_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown error"
    }

    /** Convert this Bitmap to PNG bytes. */
    private fun Bitmap.toPngByteArray(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /**
     * Build Content list for a single message (multimodal first, then text).
     */
    private fun buildContents(
        input: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
    ): List<Content> {
        val contents = mutableListOf<Content>()
        for (image in images) contents.add(Content.ImageBytes(image.toPngByteArray()))
        for (audio in audioClips) contents.add(Content.AudioBytes(audio))
        val t = input.trim()
        if (t.isNotEmpty()) contents.add(Content.Text(t))
        return contents
    }

    /**
     * Extract best-effort visible text from a [Message].
     *
     * WHY:
     * - Some LiteRT-LM builds populate message.contents(Text) reliably.
     * - Other builds may make message.toString() closer to what the sample app streams.
     *
     * Strategy:
     * - Extract both candidates and choose the more "human text" one.
     */
    private fun extractRenderedText(message: Message): String {
        val fromContents = runCatching {
            val parts = message.contents.filterIsInstance<Content.Text>()
            when (parts.size) {
                0 -> ""
                1 -> parts[0].text
                else -> parts.joinToString(separator = "") { it.text }
            }
        }.getOrElse { "" }

        val fromToString = runCatching { message.toString() }.getOrElse { "" }

        return chooseMoreHumanText(fromContents, fromToString)
    }

    /**
     * Choose the more "human text" candidate.
     *
     * Heuristics:
     * - Penalize debug-ish strings.
     * - Favor whitespace and length.
     */
    private fun chooseMoreHumanText(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a

        fun score(s: String): Int {
            var x = 0
            val debugish = listOf("Message(", "contents=", "Content.", "Text(", "engine=", "Conversation")
            if (debugish.any { s.contains(it) }) x -= 50
            x += s.length / 8
            x += s.count { it == ' ' || it == '\n' || it == '\t' }.coerceAtMost(40)
            x -= s.count { it == '=' || it == '[' || it == ']' || it == '{' || it == '}' }.coerceAtMost(30)
            return x
        }

        val sa = score(a)
        val sb = score(b)
        return if (sb > sa) b else a
    }

    /**
     * Normalize common tokenizer artifacts into plain text.
     *
     * IMPORTANT:
     * - Use this on DELTA more than on the SNAPSHOT.
     * - Normalizing snapshots can break prefix/overlap matching.
     */
    private fun normalizeDeltaText(s: String): String {
        if (s.isEmpty()) return s
        return s
            .replace('\u00A0', ' ') // NBSP
            .replace('\uFEFF', ' ') // BOM
            .replace('\u2581', ' ') // SentencePiece "▁"
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\u200B", "") // ZWSP
            .replace("\u200C", "") // ZWNJ
            .replace("\u200D", "") // ZWJ
    }

    /**
     * Compute overlap length where suffix of [a] matches prefix of [b].
     *
     * @param maxWindow Limits how many trailing chars of [a] we consider for overlap checks.
     */
    private fun overlapSuffixPrefix(a: String, b: String, maxWindow: Int = 1024): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val start = maxOf(0, a.length - maxWindow)
        val aWin = a.substring(start)
        val maxK = min(aWin.length, b.length)

        // Bounded O(n^2) but maxWindow is small; OK for streaming.
        for (k in maxK downTo 1) {
            val aPos = aWin.length - k
            if (aWin.regionMatches(aPos, b, 0, k, ignoreCase = false)) return k
        }
        return 0
    }

    /**
     * Delta extractor that works for:
     * - full cumulative snapshots (new startsWith(emittedSoFar))
     * - incremental chunks (no overlap)
     * - partial tails with overlap
     *
     * @return Pair(deltaToEmit, nextEmittedSoFar)
     */
    private fun computeDeltaSmart(emittedSoFar: String, newSnapshot: String): Pair<String, String> {
        if (newSnapshot.isEmpty()) return "" to emittedSoFar
        if (emittedSoFar.isEmpty()) return newSnapshot to newSnapshot

        if (newSnapshot.length >= emittedSoFar.length && newSnapshot.startsWith(emittedSoFar)) {
            val delta = newSnapshot.substring(emittedSoFar.length)
            return delta to newSnapshot
        }

        if (emittedSoFar.length > newSnapshot.length && emittedSoFar.startsWith(newSnapshot)) {
            return "" to emittedSoFar
        }

        val ov = overlapSuffixPrefix(emittedSoFar, newSnapshot)
        val delta = newSnapshot.substring(ov)
        return delta to (emittedSoFar + delta)
    }

    /**
     * Heuristic default max tokens by model name.
     */
    private fun defaultMaxTokensForModel(modelName: String): Int {
        val n = modelName.lowercase(Locale.US)
        return if (n.contains("functiongemma") || n.contains("270m") || n.contains("tinygarden")) 1024 else 4096
    }

    /**
     * Initialize LiteRT-LM Engine + Conversation (async).
     *
     * Rules:
     * - If a stream is active, init is rejected (caller should retry after termination).
     * - If init is already in progress for the same key, returns an error.
     * - Cancels any pending idle cleanup (init is a "use").
     */
    fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        // Mark "use" early so any idle cleanup that wakes up will skip closing.
        markUsed(key)
        cancelScheduledCleanup(key, "initialize")

        val accepted = initInFlight.add(key)
        if (!accepted) {
            postToMain { onDone("Initialization already in progress for key='$key'.") }
            return
        }

        ioScope.launch {
            var engineToCloseOnFailure: Engine? = null

            try {
                // Reject init while streaming for safety.
                stateMutex.withLock {
                    val rs = getRunState(key)
                    if (rs.active.get()) {
                        postToMain { onDone("Initialization rejected: active stream in progress for key='$key'.") }
                        return@launch
                    }
                }

                val defaultMax = defaultMaxTokensForModel(model.name)
                val maxTokensRaw =
                    model.getIntConfigValue(ConfigKey.MAX_TOKENS, defaultMax).coerceAtLeast(1)
                val maxTokens = maxTokensRaw.coerceIn(1, ABS_MAX_NUM_TOKENS)

                val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
                val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
                val temperature =
                    sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                val backend = preferredBackend(model)

                Log.d(TAG, "Initializing LiteRT-LM: model='${model.name}', key='$key'")
                Log.d(TAG, "Capabilities: image=$supportImage audio=$supportAudio")
                Log.d(
                    TAG,
                    "Backend=$backend maxNumTokens=$maxTokens (raw=$maxTokensRaw) topK=$topK topP=$topP temp=$temperature"
                )

                val modelPath = model.getPath()
                val cacheDirPath = runCatching { context.cacheDir?.absolutePath }.getOrNull()

                fun buildConfig(forBackend: Backend): EngineConfig {
                    return EngineConfig(
                        modelPath = modelPath,
                        backend = forBackend,
                        visionBackend = if (supportImage) Backend.GPU else null,
                        audioBackend = if (supportAudio) Backend.CPU else null,
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDirPath,
                    )
                }

                var engineConfig = buildConfig(backend)

                val engine = runCatching {
                    Engine(engineConfig).also {
                        engineToCloseOnFailure = it
                        it.initialize()
                    }
                }.getOrElse { first ->
                    if (backend == Backend.GPU && !supportImage && !supportAudio) {
                        Log.w(TAG, "GPU init failed; falling back to CPU: ${first.message}")
                        engineConfig = buildConfig(Backend.CPU)
                        Engine(engineConfig).also {
                            engineToCloseOnFailure = it
                            it.initialize()
                        }
                    } else {
                        throw first
                    }
                }

                val conversationConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = topK,
                        topP = topP.toDouble(),
                        temperature = temperature.toDouble(),
                    ),
                    systemMessage = systemMessage,
                    tools = tools,
                )

                val conversation = engine.createConversation(conversationConfig)

                // Swap instance under lock; close old later with a grace delay.
                val retired: LiteRtLmInstance? = stateMutex.withLock {
                    val prev = instances.remove(key)
                    instances[key] = LiteRtLmInstance(
                        engine = engine,
                        conversation = conversation,
                        supportImage = supportImage,
                        supportAudio = supportAudio,
                        engineConfigSnapshot = engineConfig,
                    )
                    prev
                }

                if (retired != null) {
                    ioScope.launch {
                        delay(RETIRED_CLOSE_GRACE_MS)
                        runCatching { retired.conversation.close() }
                            .onFailure { Log.w(TAG, "Failed to close retired conversation: ${it.message}", it) }
                        runCatching { retired.engine.close() }
                            .onFailure { Log.w(TAG, "Failed to close retired engine: ${it.message}", it) }
                    }
                }

                Log.d(TAG, "LiteRT-LM initialization succeeded: model='${model.name}', key='$key'")
                postToMain { onDone("") }
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM initialization failed: ${e.message}", e)
                runCatching { engineToCloseOnFailure?.close() }
                    .onFailure { Log.w(TAG, "Failed to close engine after init failure: ${it.message}", it) }
                postToMain { onDone(cleanError(e.message)) }
            } finally {
                initInFlight.remove(key)
            }
        }
    }

    /**
     * Suspend-style initializer.
     *
     * Uses apiMutex to prevent double init and to avoid init-vs-generateText overlap.
     * Cancels any pending idle cleanup (init is a "use").
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        markUsed(key)
        cancelScheduledCleanup(key, "initializeIfNeeded")

        stateMutex.withLock {
            if (instances.containsKey(key)) return
        }

        apiMutex.withLock {
            stateMutex.withLock {
                if (instances.containsKey(key)) return
            }

            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<Unit> { cont ->
                    initialize(
                        context = context,
                        model = model,
                        supportImage = supportImage,
                        supportAudio = supportAudio,
                        onDone = { error ->
                            if (error.isEmpty()) {
                                if (cont.isActive) cont.resume(Unit)
                            } else {
                                if (cont.isActive) {
                                    cont.resumeWithException(
                                        IllegalStateException("LiteRT-LM initialization failed: $error")
                                    )
                                }
                            }
                        },
                        systemMessage = systemMessage,
                        tools = tools,
                    )
                }
            }
        }
    }

    /**
     * Reset conversation (reuse engine) safely.
     *
     * If a stream is active, reset is deferred until the stream terminates.
     * Cancels any pending idle cleanup (reset is a "use").
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        markUsed(key)
        cancelScheduledCleanup(key, "resetConversation")

        ioScope.launch {
            val action: () -> Unit = {
                ioScope.launch {
                    try {
                        val instance = stateMutex.withLock { instances[key] } ?: return@launch

                        if (instance.supportImage != supportImage || instance.supportAudio != supportAudio) {
                            Log.w(
                                TAG,
                                "resetConversation capability mismatch; " +
                                        "init(image=${instance.supportImage}, audio=${instance.supportAudio}) vs " +
                                        "reset(image=$supportImage, audio=$supportAudio). Reinitialize if needed."
                            )
                        }

                        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
                        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
                        val temperature =
                            sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                        val newConversation = instance.engine.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(
                                    topK = topK,
                                    topP = topP.toDouble(),
                                    temperature = temperature.toDouble(),
                                ),
                                systemMessage = systemMessage,
                                tools = tools,
                            )
                        )

                        val oldConversation: Conversation? = stateMutex.withLock {
                            val latest = instances[key] ?: run {
                                runCatching { newConversation.close() }
                                return@withLock null
                            }
                            val old = latest.conversation
                            latest.conversation = newConversation
                            old
                        }

                        if (oldConversation != null) {
                            ioScope.launch {
                                delay(RETIRED_CLOSE_GRACE_MS)
                                runCatching { oldConversation.close() }
                                    .onFailure { Log.w(TAG, "Failed to close old conversation: ${it.message}", it) }
                            }
                        }

                        Log.d(TAG, "Conversation reset completed: key='$key'")
                    } catch (e: Exception) {
                        Log.e(TAG, "resetConversation failed: ${e.message}", e)
                    }
                }
            }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock { pendingAfterStream.getOrPut(key) { mutableListOf() }.add(action) }
                Log.w(TAG, "resetConversation deferred: active stream in progress for key='$key'")
                return@launch
            }

            action.invoke()
        }
    }

    /**
     * Request a deferred idle cleanup.
     *
     * IMPORTANT CHANGE:
     * - This no longer destroys the model immediately.
     * - It schedules cleanup after [IDLE_CLEANUP_MS] of idleness and cancels if used again.
     *
     * WHY:
     * - Prevents "cleanUp after every request" from causing "not initialized" on back-to-back calls.
     */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)

        // Do NOT markUsed() here; cleanup is not a "use".

        ioScope.launch {
            val action: () -> Unit = {
                scheduleIdleCleanup(key, IDLE_CLEANUP_MS, "explicit-cleanUp")
                postToMain { onDone() }
            }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock { pendingAfterStream.getOrPut(key) { mutableListOf() }.add(action) }
                Log.w(TAG, "cleanUp deferred (will schedule after stream): key='$key'")
                postToMain { onDone() }
                return@launch
            }

            action.invoke()
        }
    }

    /**
     * Force immediate teardown (use sparingly).
     *
     * Use cases:
     * - App shutdown / explicit memory pressure response.
     * - You truly want to free native memory now.
     *
     * NOTE:
     * - If a stream is active, the teardown is deferred until termination.
     */
    fun forceCleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)

        cancelScheduledCleanup(key, "forceCleanUp")

        ioScope.launch {
            val action: () -> Unit = {
                ioScope.launch {
                    closeInstanceNowBestEffort(key, "forceCleanUp")
                    postToMain { onDone() }
                }
            }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock { pendingAfterStream.getOrPut(key) { mutableListOf() }.add(action) }
                Log.w(TAG, "forceCleanUp deferred: active stream in progress for key='$key'")
                postToMain { onDone() }
                return@launch
            }

            action.invoke()
        }
    }

    /**
     * Low-level callback-based streaming API.
     *
     * Contract:
     * - Terminates exactly once with done=true (via resultListener("", true)).
     * - Partial callbacks are delivered on Main.
     * - Never closes engine/conversation here; cleanup/reset are deferred actions.
     */
    fun runInference(
        model: Model,
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
    ) {
        val key = runtimeKey(model)

        // Mark as used and cancel any scheduled cleanup immediately.
        markUsed(key)
        cancelScheduledCleanup(key, "runInference")

        ioScope.launch {

            // Start-window race fix:
            // Acquire stateMutex and set rs.active BEFORE any lifecycle ops can swap/close conversations.
            var instance: LiteRtLmInstance? = null
            var rs: RunState? = null
            var myRunId = 0L
            var conversation: Conversation? = null
            var rejectMsg: String? = null
            var cooldownDelayMs = 0L

            stateMutex.withLock {
                instance = instances[key]
                if (instance == null) {
                    rejectMsg = "LiteRT-LM model '${model.name}' is not initialized. Call initializeIfNeeded() first."
                    return@withLock
                }

                if (images.isNotEmpty() && instance?.supportImage != true) {
                    rejectMsg = "Vision input rejected: supportImage=false for key='$key'. Reinitialize with supportImage=true."
                    return@withLock
                }
                if (audioClips.isNotEmpty() && instance?.supportAudio != true) {
                    rejectMsg = "Audio input rejected: supportAudio=false for key='$key'. Reinitialize with supportAudio=true."
                    return@withLock
                }

                rs = getRunState(key)

                // Enforce post-terminate cooldown.
                val now = SystemClock.elapsedRealtime()
                val until = rs!!.cooldownUntilMs.get()
                cooldownDelayMs = max(0L, until - now)

                val acquired = rs!!.active.compareAndSet(false, true)
                if (!acquired) {
                    rejectMsg = "LiteRT-LM runInference rejected: another stream is already active for key='$key'."
                    return@withLock
                }

                // New runId; late callbacks from older runs will be ignored.
                myRunId = rs!!.runId.incrementAndGet()
                rs!!.terminated.set(false)
                rs!!.cancelRequested.set(false)

                // Install a minimal early terminator that only marks cancel.
                // This closes the tiny window where cancel() can happen before the full terminator is installed.
                rs!!.terminator.set { rs!!.cancelRequested.set(true) }

                conversation = instance!!.conversation
            }

            if (rejectMsg != null || instance == null || rs == null || conversation == null) {
                val msg = rejectMsg ?: "LiteRT-LM start rejected: unknown reason."
                Log.w(TAG, msg)
                postToMain {
                    onError(msg)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
                return@launch
            }

            // Apply cooldown outside lock.
            if (cooldownDelayMs > 0) {
                Log.d(TAG, "Post-terminate cooldown: delaying start ${cooldownDelayMs}ms for key='$key'")
                delay(cooldownDelayMs)
            }

            val contents = buildContents(input = input, images = images, audioClips = audioClips)

            var emittedSoFar = ""
            var msgCount = 0

            suspend fun runDeferredActions() {
                val deferred: List<() -> Unit> = stateMutex.withLock {
                    pendingAfterStream.remove(key)?.toList() ?: emptyList()
                }
                deferred.forEach { act ->
                    runCatching { act.invoke() }
                        .onFailure { t -> Log.w(TAG, "Deferred action failed for key='$key': ${t.message}", t) }
                }
            }

            fun terminateOnce(errorMessage: String? = null) {
                val rsLocal = rs!!
                if (!rsLocal.terminated.compareAndSet(false, true)) return

                val now = SystemClock.elapsedRealtime()
                rsLocal.lastTerminateAtMs.set(now)
                rsLocal.cooldownUntilMs.set(now + POST_TERMINATE_COOLDOWN_MS)

                // Mark inactive before deferred actions.
                rsLocal.active.set(false)

                // Clear terminator.
                rsLocal.terminator.set(null)

                postToMain {
                    if (!errorMessage.isNullOrBlank()) onError(errorMessage)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                        .onFailure { t -> Log.w(TAG, "cleanUpListener failed: ${t.message}", t) }
                }

                ioScope.launch { runDeferredActions() }
            }

            // Install the real terminator.
            stateMutex.withLock {
                rs!!.terminator.set { terminateOnce("Cancelled") }
            }

            // If cancel was requested during the start window, terminate immediately before calling native.
            if (rs!!.cancelRequested.get()) {
                Log.i(TAG, "LiteRT-LM start cancelled before sendMessageAsync: key='$key'")
                terminateOnce("Cancelled")
                return@launch
            }

            try {
                conversation!!.sendMessageAsync(
                    Message.of(contents),
                    object : MessageCallback {

                        override fun onMessage(message: Message) {
                            if (rs!!.runId.get() != myRunId) return
                            if (rs!!.terminated.get()) return

                            msgCount++

                            val snapshotRaw = extractRenderedText(message)
                            if (snapshotRaw.isEmpty()) return

                            val (deltaRaw, nextEmitted) = computeDeltaSmart(emittedSoFar, snapshotRaw)
                            if (deltaRaw.isEmpty()) {
                                emittedSoFar = nextEmitted
                                return
                            }

                            val delta = normalizeDeltaText(deltaRaw)
                            emittedSoFar = nextEmitted

                            if (DEBUG_STREAM && (msgCount == 1 || msgCount % DEBUG_STREAM_EVERY_N == 0)) {
                                val lead = deltaRaw.firstOrNull()
                                val leadInfo =
                                    if (lead == null) "null"
                                    else "U+${lead.code.toString(16).uppercase()} ws=${lead.isWhitespace()} ch='$lead'"

                                val dPreview = delta.take(DEBUG_PREFIX_CHARS).replace("\n", "\\n")
                                val sPreview = snapshotRaw.take(DEBUG_PREFIX_CHARS).replace("\n", "\\n")

                                Log.d(
                                    TAG,
                                    "stream[key=$key runId=$myRunId] msg#$msgCount " +
                                            "snapLen=${snapshotRaw.length} deltaLen=${delta.length} " +
                                            "lead=$leadInfo snapPreview='$sPreview' deltaPreview='$dPreview' emittedLen=${emittedSoFar.length}"
                                )
                            }

                            postToMain { resultListener(delta, false) }
                        }

                        override fun onDone() {
                            if (rs!!.runId.get() != myRunId) return
                            terminateOnce()
                        }

                        override fun onError(throwable: Throwable) {
                            if (rs!!.runId.get() != myRunId) return
                            if (rs!!.terminated.get()) return

                            if (throwable is CancellationException || rs!!.cancelRequested.get()) {
                                Log.i(TAG, "LiteRT-LM inference cancelled: key='$key'")
                                terminateOnce()
                            } else {
                                Log.e(TAG, "LiteRT-LM onError: key='$key'", throwable)
                                terminateOnce("Error: ${cleanError(throwable.message)}")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM sendMessageAsync failed: key='$key' msg=${e.message}", e)
                terminateOnce(cleanError(e.message))
            }
        }
    }

    /**
     * High-level suspend API:
     * - Serializes calls via apiMutex.
     * - Uses runInference internally and returns full aggregated text.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
    ): String = apiMutex.withLock {
        val key = runtimeKey(model)

        // Mark use + cancel any pending cleanup (generateText implies use).
        markUsed(key)
        cancelScheduledCleanup(key, "generateText")

        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException("LiteRT-LM is already busy with another request.")
        }

        try {
            suspendCancellableCoroutine { cont ->
                val buffer = StringBuilder()
                val sawError = AtomicBoolean(false)

                runInference(
                    model = model,
                    input = input,
                    images = images,
                    audioClips = audioClips,
                    resultListener = { partial, done ->
                        if (partial.isNotEmpty()) {
                            buffer.append(partial)
                            runCatching { onPartial(partial) }
                                .onFailure { t -> Log.w(TAG, "onPartial failed: ${t.message}", t) }
                        }
                        if (done && cont.isActive && !sawError.get()) {
                            cont.resume(buffer.toString())
                        }
                    },
                    cleanUpListener = {
                        // No-op by default; caller can use this hook if needed.
                    },
                    onError = { message ->
                        sawError.set(true)
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("LiteRT-LM generation error: $message")
                            )
                        }
                    },
                )

                cont.invokeOnCancellation {
                    Log.i(TAG, "generateText cancelled: key='$key'")
                    cancel(model)
                }
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * Best-effort logical cancellation.
     *
     * IMPORTANT:
     * - The SDK may not truly cancel an in-flight conversation.
     * - We request cancellation and then "logically terminate" if possible.
     */
    fun cancel(model: Model) {
        val key = runtimeKey(model)

        ioScope.launch {
            val rs = stateMutex.withLock { getRunState(key) }
            if (!rs.active.get()) return@launch

            rs.cancelRequested.set(true)
            rs.terminator.get()?.invoke()
        }
    }
}
