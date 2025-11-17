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

package com.negi.survey.vm

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Represents the current download state for the model.
 */
sealed class DlState {
    /**
     * No download in progress and no file yet.
     */
    data object Idle : DlState()

    /**
     * A download is in progress.
     *
     * @param downloaded Number of bytes downloaded so far.
     * @param total Total byte length if known, or null when the server does not provide it.
     */
    data class Downloading(val downloaded: Long, val total: Long?) : DlState()

    /**
     * Download finished successfully.
     *
     * @param file The final model file on disk.
     */
    data class Done(val file: File) : DlState()

    /**
     * Download failed with an error.
     *
     * @param message Human-readable error message.
     */
    data class Error(val message: String) : DlState()
}

/**
 * ViewModel responsible for managing the download and persistence of the SLM model file.
 *
 * Responsibilities:
 * - Delegate heavy initialization to [HeavyInitializer].
 * - Expose [DlState] as a [StateFlow] for UI.
 * - Apply timeout and basic UI throttling based on configuration.
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
     * Live download state for observers.
     */
    val state: StateFlow<DlState> = _state

    /**
     * Ensures that the model is downloaded once.
     *
     * Behavior:
     * - If [forceFresh] is false and a previously downloaded file exists on disk,
     *   this method short-circuits and updates state to [DlState.Done] without
     *   calling [HeavyInitializer].
     * - Otherwise, uses [HeavyInitializer] for single-flight + resume +
     *   integrity check.
     * - If [forceFresh] is true, cached files are ignored and re-downloaded.
     * - Safe to call from several places; HeavyInitializer collapses concurrent calls.
     */
    fun ensureModelDownloaded(
        appContext: Context,
        forceFresh: Boolean = false
    ) {
        val app = appContext.applicationContext

        // If we already have a Done state with an existing file and we are not
        // forcing a refresh, keep it and skip any new work.
        val currentState = _state.value
        if (!forceFresh && currentState is DlState.Done && currentState.file.exists()) {
            return
        }

        // Try to detect an existing model file on disk before starting a download.
        if (!forceFresh) {
            val safeName = suggestFileName(modelUrl, fileName)
            // This must match the directory HeavyInitializer uses to place the file.
            // Here we assume filesDir with a flat filename, which is the common case.
            val existing = File(app.filesDir, safeName)
            if (existing.exists() && existing.isFile && existing.length() > 0L) {
                _state.value = DlState.Done(existing)
                return
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            if (!forceFresh && (current is DlState.Downloading || current is DlState.Done)) {
                return@launch
            }

            val safeName = suggestFileName(modelUrl, fileName)
            val token = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() }

            _state.value = DlState.Downloading(downloaded = 0L, total = null)

            var lastEmitNs = 0L
            var lastBytes = 0L

            // Bridge HeavyInitializer's raw progress into throttled DlState updates.
            val progressBridge: (Long, Long?) -> Unit = { got, total ->
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
                    _state.value = DlState.Downloading(got, total)
                }
            }

            val result = HeavyInitializer.ensureInitialized(
                context = app,
                modelUrl = modelUrl,
                hfToken = token,
                fileName = safeName,
                timeoutMs = timeoutMs,
                forceFresh = forceFresh,
                onProgress = progressBridge
            )

            _state.value = result.fold(
                onSuccess = { file -> DlState.Done(file) },
                onFailure = { error ->
                    val msg = error.message ?: "download failed"
                    DlState.Error(msg)
                }
            )
        }
    }

    /**
     * Attempts to cancel any running HeavyInitializer task.
     *
     * Call from UI when the user taps a "Cancel" button.
     */
    fun cancelDownload() {
        viewModelScope.launch {
            HeavyInitializer.cancel()
            _state.value = DlState.Error("Canceled by user")
        }
    }

    /**
     * Debug-only reset that also clears HeavyInitializer internal state.
     *
     * Useful in dev builds when testing repeated downloads.
     */
    fun resetForDebug() {
        HeavyInitializer.resetForDebug()
        _state.value = DlState.Idle
    }

    companion object {

        /**
         * Default model URL for the LiteRT-LM Gemma variant.
         */
        const val DEFAULT_MODEL_URL: String =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm"

        /**
         * Default local file name for the model.
         */
        private const val DEFAULT_FILE_NAME: String = "model.litertlm"

        /**
         * Default hard timeout for the whole download (30 minutes).
         */
        private const val DEFAULT_TIMEOUT_MS: Long = 30L * 60L * 1000L

        /**
         * Default minimum interval between UI progress updates in milliseconds.
         */
        private const val DEFAULT_UI_THROTTLE_MS: Long = 250L

        /**
         * Default minimum byte delta between UI progress updates.
         */
        private const val DEFAULT_UI_MIN_DELTA_BYTES: Long = 1L * 1024L * 1024L

        /**
         * ViewModel factory to be used with Compose [androidx.lifecycle.viewmodel.compose.viewModel].
         *
         * Uses fully compiled-in defaults.
         */
        fun factory(): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppViewModel() as T
                }
            }

        /**
         * High-level factory that accepts nullable overrides (from YAML model_defaults).
         *
         * Pass values directly from SurveyConfig.modelDefaults.
         * Any null or invalid value falls back to a compiled default.
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
            val minDelta = uiMinDeltaBytesOverride?.takeIf { it >= 0L }
                ?: DEFAULT_UI_MIN_DELTA_BYTES

            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
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
         * Derive a safe filename from the given URL or fall back to [fallback].
         */
        private fun suggestFileName(url: String, fallback: String): String {
            val raw = url.substringAfterLast('/').ifBlank { fallback }
            return raw.substringBefore('?').ifBlank { raw }
        }
    }
}

/**
 * UI component that gates access to the main content until model download completes.
 *
 * Shows:
 * - Progress UI while downloading.
 * - Error with retry button on failure.
 * - Delegates to [content] on success.
 */
@Composable
fun DownloadGate(
    state: DlState,
    onRetry: () -> Unit,
    content: @Composable (modelFile: File) -> Unit
) {
    when (state) {
        is DlState.Idle,
        is DlState.Downloading -> {
            val (got, total) = when (state) {
                is DlState.Downloading -> state.downloaded to state.total
                else -> 0L to null
            }
            val pct: Int? = total?.let { t ->
                if (t > 0L) (got * 100.0 / t.toDouble()).toInt() else null
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Downloading the target SLM…")
                Spacer(Modifier.height(12.dp))
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$pct%  ($got / ${total} bytes)")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("$got bytes")
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
                Button(onClick = onRetry) { Text("Retry") }
            }
        }

        is DlState.Done -> {
            content(state.file)
        }
    }
}
