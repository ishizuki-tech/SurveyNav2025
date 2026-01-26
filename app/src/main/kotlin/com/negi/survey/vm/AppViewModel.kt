/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AppViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.BuildConfig
import com.negi.survey.utils.HeavyInitializer
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/* ───────────────────────────── Download State ───────────────────────────── */

/**
 * Represents the current model download lifecycle state.
 *
 * This sealed state is intentionally minimal and UI-friendly:
 * - It can be observed directly by Compose.
 * - It does not expose transport details (HTTP, resumable chunks, etc.).
 * - It is suitable for gating progression into SLM initialization.
 */
sealed class DlState {

    /**
     * No download in progress and no confirmed model file.
     *
     * This state is also used as a "pre-flight" state when the ViewModel is
     * initialized but has not yet been asked to ensure the model.
     */
    data object Idle : DlState()

    /**
     * Model download is currently in progress.
     *
     * @property downloaded Number of bytes downloaded so far.
     * @property total Total content length in bytes if known, or null when
     * the server does not provide it.
     */
    data class Downloading(
        val downloaded: Long,
        val total: Long?
    ) : DlState()

    /**
     * Download successfully completed.
     *
     * @property file Final model file location on disk.
     */
    data class Done(
        val file: File
    ) : DlState()

    /**
     * Download failed or was cancelled.
     *
     * @property message Human-readable error message suitable for UI.
     */
    data class Error(
        val message: String
    ) : DlState()
}

/* ───────────────────────────── ViewModel ───────────────────────────── */

/**
 * ViewModel responsible for ensuring the on-device SLM model exists locally.
 *
 * Core responsibilities:
 * - Provide a single-flight, resume-capable download entry point via [HeavyInitializer].
 * - Expose a stable [StateFlow] of [DlState] for Compose UI gates.
 * - Apply progress throttling to prevent excessive recompositions.
 *
 * Robustness upgrades:
 * - Run correlation (runId) to prevent stale state overrides after cancel/refresh.
 * - Job tracking to support force-refresh replacing an in-flight run.
 * - Best-effort local cache validation via sidecar meta + basic header heuristics.
 */
class AppViewModel(
    val modelUrl: String = DEFAULT_MODEL_URL,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val uiThrottleMs: Long = DEFAULT_UI_THROTTLE_MS,
    private val uiMinDeltaBytes: Long = DEFAULT_UI_MIN_DELTA_BYTES
) : ViewModel() {

    private val _state = MutableStateFlow<DlState>(DlState.Idle)

    /**
     * Exposes the current download state for observers.
     */
    val state: StateFlow<DlState> = _state.asStateFlow()

    private val lock = Any()

    private val runSeq = AtomicLong(0L)
    private val activeRunId = AtomicLong(0L)

    @Volatile
    private var downloadJob: Job? = null

    /**
     * Ensures that the model file is available on disk.
     *
     * Behavior summary:
     * - If [forceFresh] is false and a valid existing model file is found,
     *   this method immediately emits [DlState.Done] and returns.
     * - If a download is in-flight:
     *   - forceFresh=false: this call becomes a no-op.
     *   - forceFresh=true: cancels the active run and starts a new one.
     * - Otherwise, [HeavyInitializer.ensureInitialized] is used to perform:
     *   - single-flight download
     *   - resume support
     *   - optional integrity checks (implementation-dependent)
     * - Progress is bridged into [DlState.Downloading] with throttling.
     *
     * Threading:
     * - Orchestration runs on [Dispatchers.IO].
     * - [MutableStateFlow] is safe for background emissions.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun ensureModelDownloaded(
        appContext: Context,
        forceFresh: Boolean = false
    ) {
        val app = appContext.applicationContext
        val urlHash = sha256Hex(modelUrl).take(URL_HASH_LEN)
        val desiredName = buildLocalFileName(
            url = modelUrl,
            fallback = fileName,
            urlHash = urlHash,
            forceHashSuffix = (fileName == DEFAULT_FILE_NAME)
        )

        synchronized(lock) {
            val job = downloadJob
            val jobActive = (job?.isActive == true)

            // Fast-path: already done with a cache-valid file.
            val cur = _state.value
            if (!forceFresh && cur is DlState.Done && isUsableModelFile(cur.file, urlHash)) {
                return
            }

            // If we can satisfy from disk, do it immediately.
            if (!forceFresh) {
                findExistingModelFile(app, desiredName, urlHash)?.let { existing ->
                    _state.value = DlState.Done(existing)
                    return
                }
            }

            if (jobActive) {
                if (!forceFresh) {
                    // In-flight and not forcing refresh: keep current run.
                    return
                }
                // forceFresh: replace the active run.
                cancelDownloadInternalLocked(
                    app = app,
                    setUiState = false,
                    reason = "Replaced by force refresh"
                )
            }

            val runId = runSeq.incrementAndGet()
            activeRunId.set(runId)

            // Start the new run.
            downloadJob = viewModelScope.launch(Dispatchers.IO) {
                runDownload(
                    app = app,
                    runId = runId,
                    urlHash = urlHash,
                    desiredName = desiredName,
                    forceFresh = forceFresh
                )
            }
        }
    }

    /**
     * Requests cancellation of any in-flight model initialization/download.
     *
     * This is a best-effort signal to [HeavyInitializer]. The underlying
     * implementation may:
     * - cancel active network work
     * - keep partially downloaded files for future resume
     *
     * UI semantics:
     * - Sets [DlState.Error] with a user-cancel message.
     * - Uses runId invalidation to prevent stale progress/success overrides.
     */
    fun cancelDownload() {
        val appRef: Context? = null
        synchronized(lock) {
            // Invalidate run first to block stale updates.
            activeRunId.set(0L)
            runSeq.incrementAndGet()

            downloadJob?.cancel()
            downloadJob = null

            // Best-effort cancel in background.
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { HeavyInitializer.cancel() }
            }

            _state.value = DlState.Error("Canceled by user")
        }
    }

    /**
     * Debug-only reset entry point.
     *
     * This clears both:
     * - UI state in this ViewModel
     * - any internal single-flight bookkeeping in [HeavyInitializer]
     *
     * This should not be exposed in production UI.
     */
    fun resetForDebug() {
        synchronized(lock) {
            activeRunId.set(0L)
            runSeq.incrementAndGet()
            downloadJob?.cancel()
            downloadJob = null
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { HeavyInitializer.resetForDebug() }
        }
        _state.value = DlState.Idle
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun runDownload(
        app: Context,
        runId: Long,
        urlHash: String,
        desiredName: String,
        forceFresh: Boolean
    ) {
        fun isActive(): Boolean = activeRunId.get() == runId

        fun commitState(s: DlState) {
            if (!isActive()) return
            _state.value = s
        }

        try {
            // Guard against rare races where a run is immediately replaced.
            if (!isActive()) return

            val token = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() }

            commitState(DlState.Downloading(downloaded = 0L, total = null))

            // Throttling state for progress-to-UI updates.
            var lastEmitNs = System.nanoTime()
            var lastBytes = 0L

            val progressBridge: (Long, Long?) -> Unit = progress@{ got, total ->
                // IMPORTANT: Return from THIS lambda, not from the outer function.
                if (!isActive()) return@progress

                val now = System.nanoTime()
                val elapsedMs = (now - lastEmitNs) / 1_000_000L
                val deltaBytes = got - lastBytes

                val shouldEmit =
                    elapsedMs >= uiThrottleMs ||
                            deltaBytes >= uiMinDeltaBytes ||
                            (total != null && got >= total)

                if (shouldEmit) {
                    lastEmitNs = now
                    lastBytes = got
                    commitState(DlState.Downloading(got, total))
                }
            }

            val result = HeavyInitializer.ensureInitialized(
                context = app,
                modelUrl = modelUrl,
                hfToken = token,
                fileName = desiredName,
                timeoutMs = timeoutMs,
                forceFresh = forceFresh,
                onProgress = progressBridge
            )

            if (!isActive()) return

            result.fold(
                onSuccess = { file ->
                    // Persist sidecar meta to avoid stale cache problems on later launches.
                    runCatching { writeModelMetaAtomic(file, modelUrl, urlHash) }

                    // Final validation (best-effort): avoid accepting obvious HTML/error payloads.
                    if (!isUsableModelFile(file, urlHash)) {
                        commitState(DlState.Error("Downloaded file failed validation (cache payload looks invalid)"))
                    } else {
                        commitState(DlState.Done(file))
                    }
                },
                onFailure = { error ->
                    val msg = error.message ?: "Download failed"
                    commitState(DlState.Error(msg))
                }
            )
        } catch (e: CancellationException) {
            // Do not override UI here; cancellation is handled by invalidation + caller path.
        } catch (t: Throwable) {
            if (activeRunId.get() == runId) {
                _state.value = DlState.Error(t.message ?: "Download failed")
            }
        } finally {
            synchronized(lock) {
                if (activeRunId.get() == runId) {
                    activeRunId.set(0L)
                }
                if (downloadJob?.isCancelled != false && downloadJob?.isActive != true) {
                    // Keep consistent if the job ended and no newer run is set.
                    downloadJob = null
                }
            }
        }
    }

    private fun cancelDownloadInternalLocked(
        app: Context,
        setUiState: Boolean,
        reason: String
    ) {
        // Invalidate current run first so stale updates cannot win.
        activeRunId.set(0L)
        runSeq.incrementAndGet()

        downloadJob?.cancel()
        downloadJob = null

        // Best-effort cancel the underlying initializer.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { HeavyInitializer.cancel() }
        }

        if (setUiState) {
            _state.value = DlState.Error(reason)
        }
    }

    companion object {

        /**
         * Default hosted model URL.
         *
         * This can be overridden by YAML `model_defaults.default_model_url`.
         */
        const val DEFAULT_MODEL_URL: String =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm"

        /**
         * Default local file name used when URL inference is unavailable.
         */
        private const val DEFAULT_FILE_NAME: String = "model.litertlm"

        /**
         * Default hard timeout for model acquisition.
         */
        private const val DEFAULT_TIMEOUT_MS: Long = 30L * 60L * 1000L

        /**
         * Minimum time interval between progress-to-UI emissions.
         */
        private const val DEFAULT_UI_THROTTLE_MS: Long = 250L

        /**
         * Minimum byte delta required to trigger a UI emission.
         */
        private const val DEFAULT_UI_MIN_DELTA_BYTES: Long = 1L * 1024L * 1024L

        private const val URL_HASH_LEN = 10

        /**
         * Compose-friendly factory using compiled defaults.
         */
        fun factory(): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AppViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return AppViewModel() as T
                }
            }

        /**
         * Factory that accepts nullable overrides (e.g., from YAML model_defaults).
         */
        fun factoryFromOverrides(
            modelUrlOverride: String? = null,
            fileNameOverride: String? = null,
            timeoutMsOverride: Long? = null,
            uiThrottleMsOverride: Long? = null,
            uiMinDeltaBytesOverride: Long? = null
        ): ViewModelProvider.Factory {
            val url = modelUrlOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_URL
            val name = fileNameOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_FILE_NAME
            val timeout = timeoutMsOverride?.takeIf { it > 0L } ?: DEFAULT_TIMEOUT_MS
            val throttle = uiThrottleMsOverride?.takeIf { it >= 0L } ?: DEFAULT_UI_THROTTLE_MS
            val minDelta = uiMinDeltaBytesOverride?.takeIf { it >= 0L } ?: DEFAULT_UI_MIN_DELTA_BYTES

            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AppViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return AppViewModel(
                        modelUrl = url,
                        fileName = name,
                        timeoutMs = timeout,
                        uiThrottleMs = throttle,
                        uiMinDeltaBytes = minDelta
                    ) as T
                }
            }
        }

        /**
         * Build a stable local file name for the model.
         *
         * Strategy:
         * - Use the last path segment of the URL if available.
         * - Strip query parameters.
         * - Sanitize to safe filesystem characters.
         * - Optionally suffix a short URL hash to avoid cache contamination when URLs change.
         */
        private fun buildLocalFileName(
            url: String,
            fallback: String,
            urlHash: String,
            forceHashSuffix: Boolean
        ): String {
            val raw = url.substringAfterLast('/').ifBlank { fallback }
            val stripped = raw.substringBefore('?').substringBefore('#').ifBlank { fallback }
            val safe = sanitizeFileName(stripped.ifBlank { fallback })

            val hasExt = safe.contains('.')
            val (base, ext) = if (hasExt) {
                val e = safe.substringAfterLast('.', missingDelimiterValue = "")
                val b = safe.substringBeforeLast('.', missingDelimiterValue = safe)
                b to e
            } else {
                safe to ""
            }

            val shortHash = urlHash.take(URL_HASH_LEN)

            // If the name is too generic (or default), suffix the hash to avoid stale cache.
            val shouldSuffix = forceHashSuffix || safe == fallback

            val base2 = if (shouldSuffix && !base.contains(shortHash)) {
                "${truncateBase(base, 64)}-$shortHash"
            } else {
                truncateBase(base, 72)
            }

            return if (ext.isNotBlank()) "$base2.$ext" else base2
        }

        /**
         * Best-effort search for an already-present model file that matches the current URL.
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun findExistingModelFile(context: Context, name: String, urlHash: String): File? {
            val privateModelsDir = runCatching { context.getDir("models", Context.MODE_PRIVATE) }.getOrNull()

            val candidates = buildList {
                add(File(context.filesDir, name))
                add(File(context.filesDir, "models/$name"))
                if (privateModelsDir != null) add(File(privateModelsDir, name))
                add(File(context.cacheDir, name))
                add(File(context.cacheDir, "models/$name"))
            }

            return candidates.firstOrNull { f ->
                isUsableModelFile(f, urlHash)
            }
        }

        /**
         * Decide whether a file is a plausible model payload for the current URL.
         *
         * Validation layers (best-effort):
         * - Exists, file, non-empty
         * - Sidecar meta matches current URL hash (if present)
         * - Basic "HTML/error payload" header heuristic (guards common 403/404 downloads)
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun isUsableModelFile(f: File, urlHash: String): Boolean {
            if (!f.exists() || !f.isFile || f.length() <= 0L) return false

            // If meta exists, require it to match (stronger guarantee).
            readModelMeta(f)?.let { meta ->
                if (!meta.urlHash.equals(urlHash, ignoreCase = true)) return false
                if (meta.length > 0L && f.length() != meta.length) return false
            }

            // Avoid accepting obvious HTML/error bodies stored as "model".
            if (looksLikeHtmlOrErrorPayload(f)) return false

            return true
        }

        /**
         * Heuristic guard against downloading an HTML/error response (e.g., 403/404 page)
         * into a model file.
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun looksLikeHtmlOrErrorPayload(f: File): Boolean {
            return runCatching {
                val n = minOf(256, f.length().toInt())
                if (n <= 0) return@runCatching false
                val bytes = f.inputStream().use { it.readNBytes(n) }
                if (bytes.isEmpty()) return@runCatching false

                val s = bytes.toString(Charsets.UTF_8).trimStart()
                val head = s.take(120).lowercase(Locale.US)

                head.startsWith("<!doctype") ||
                        head.startsWith("<html") ||
                        head.startsWith("<head") ||
                        head.contains("accessdenied") ||
                        head.contains("forbidden") ||
                        head.contains("not found") ||
                        head.contains("error") && head.contains("http")
            }.getOrElse { false }
        }

        private data class ModelMeta(
            val url: String,
            val urlHash: String,
            val length: Long
        )

        /**
         * Write a sidecar meta file atomically next to the model file.
         */
        private fun writeModelMetaAtomic(modelFile: File, url: String, urlHash: String) {
            val metaFile = metaFileFor(modelFile)
            val tmp = File(metaFile.parentFile, metaFile.name + ".part")

            val obj = JSONObject()
                .put("url", url)
                .put("urlHash", urlHash)
                .put("length", modelFile.length())

            tmp.parentFile?.mkdirs()
            tmp.writeText(obj.toString(), Charsets.UTF_8)

            if (metaFile.exists()) {
                runCatching { metaFile.delete() }
            }
            if (!tmp.renameTo(metaFile)) {
                // Fallback: try overwrite directly if rename fails.
                metaFile.writeText(obj.toString(), Charsets.UTF_8)
                runCatching { tmp.delete() }
            }
        }

        /**
         * Read sidecar meta file if present and parse it.
         */
        private fun readModelMeta(modelFile: File): ModelMeta? {
            val metaFile = metaFileFor(modelFile)
            if (!metaFile.exists() || !metaFile.isFile) return null

            return runCatching {
                val txt = metaFile.readText(Charsets.UTF_8)
                val obj = JSONObject(txt)
                ModelMeta(
                    url = obj.optString("url", ""),
                    urlHash = obj.optString("urlHash", ""),
                    length = obj.optLong("length", 0L)
                )
            }.getOrNull()
        }

        private fun metaFileFor(modelFile: File): File {
            return File(modelFile.parentFile, "${modelFile.name}.meta.json")
        }

        private fun sanitizeFileName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return DEFAULT_FILE_NAME

            val sb = StringBuilder(trimmed.length)
            for (ch in trimmed) {
                val ok =
                    ch.isLetterOrDigit() ||
                            ch == '.' || ch == '_' || ch == '-' ||
                            ch == '+'
                sb.append(if (ok) ch else '_')
            }
            return sb.toString().take(128).ifBlank { DEFAULT_FILE_NAME }
        }

        private fun truncateBase(base: String, max: Int): String {
            return if (base.length <= max) base else base.take(max)
        }

        private fun sha256Hex(input: String): String = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }.getOrElse { "sha256_error" }

        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0L).toDouble()
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            val tb = gb * 1024.0

            return when {
                b >= tb -> String.format(Locale.US, "%.2f TB", b / tb)
                b >= gb -> String.format(Locale.US, "%.2f GB", b / gb)
                b >= mb -> String.format(Locale.US, "%.2f MB", b / mb)
                b >= kb -> String.format(Locale.US, "%.2f KB", b / kb)
                else -> String.format(Locale.US, "%.0f B", b)
            }
        }
    }
}

/* ───────────────────────────── UI Gate ───────────────────────────── */

/**
 * UI gate that blocks entry into the SLM-dependent flow until
 * the model file is available locally.
 *
 * Design notes:
 * - [DlState.Idle] is rendered similarly to downloading states
 *   to avoid UI flicker during short pre-flight checks.
 * - The UI deliberately avoids binding to transport details.
 */
@Composable
fun DownloadGate(
    state: DlState,
    onRetry: () -> Unit,
    content: @Composable (modelFile: File) -> Unit
) {
    when (state) {
        is DlState.Idle -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Checking local model cache…")
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        is DlState.Downloading -> {
            val got = state.downloaded.coerceAtLeast(0L)
            val total = state.total?.takeIf { it > 0L }

            val pct: Int? = total?.let { t ->
                ((got * 100.0) / t.toDouble()).toInt().coerceIn(0, 100)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Downloading the target SLM…")
                Spacer(Modifier.height(12.dp))

                if (pct != null && total != null) {
                    LinearProgressIndicator(
                        progress = (pct / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$pct%  (${AppViewModel.formatBytes(got)} / ${AppViewModel.formatBytes(total)})")
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(AppViewModel.formatBytes(got))
                }
            }
        }

        is DlState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Failed to download model: ${state.message}")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }

        is DlState.Done -> {
            content(state.file)
        }
    }
}
