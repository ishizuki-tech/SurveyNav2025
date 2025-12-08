/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadQueueViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.negi.survey.net.GitHubUploadWorker
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/* ───────────────────────────── UI Model ───────────────────────────── */

/**
 * UI-facing description of a single upload task.
 *
 * @property id Stable identifier derived from [WorkInfo.id].
 * @property fileName Best-effort file name associated with the upload.
 * @property percent Upload progress percentage (0..100) or null if unknown.
 * @property state Current [WorkInfo.State] for the worker.
 * @property fileUrl URL returned by the worker on success, if available.
 * @property message Short human-readable status derived from [state].
 */
data class UploadItemUi(
    val id: String,
    val fileName: String,
    val percent: Int?,          // null = unknown / not reported
    val state: WorkInfo.State,
    val fileUrl: String?,       // populated on success
    val message: String? = null // short status line for the HUD
)

/* ───────────────────────────── ViewModel ───────────────────────────── */

/**
 * ViewModel that observes WorkManager for GitHub upload tasks and exposes
 * a compact stream of UI models for a HUD or queue display.
 *
 * Features:
 * - Observes all work with [GitHubUploadWorker.TAG].
 * - Maps [WorkInfo] into [UploadItemUi] with stable sorting.
 * - Uses a lightweight equality check to avoid unnecessary recompositions.
 * - Provides an optional HUD visibility flow for global overlays.
 *
 * Design note:
 * - WorkInfo does NOT expose inputData in many WorkManager versions.
 *   Therefore filename inference must rely on progress/output/tags.
 */
class UploadQueueViewModel(app: Application) : AndroidViewModel(app) {

    private val wm: WorkManager = WorkManager.getInstance(app)

    /**
     * Stream of upload items for the UI.
     *
     * Ordering:
     *  1. RUNNING
     *  2. ENQUEUED / BLOCKED
     *  3. SUCCEEDED
     *  4. FAILED / CANCELLED
     *
     * Within the same bucket, items are sorted by file name (case-insensitive)
     * for a stable, predictable visual order.
     */
    val itemsFlow: Flow<List<UploadItemUi>> =
        wm.getWorkInfosByTagLiveData(GitHubUploadWorker.TAG)
            .asFlow()
            .map { workList ->
                workList
                    .map { wi -> wi.toUploadItemUi() }
                    .sortedWith(
                        compareBy<UploadItemUi> { it.priorityRank() }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
                    )
            }
            /**
             * Emit only when something that affects rendering actually changed.
             */
            .distinctUntilChanged { old, new -> listsRenderEqual(old, new) }

    /**
     * Convenience flow for deciding whether a global upload HUD should be visible.
     *
     * Visible when there is any active or pending work.
     */
    val hudVisibleFlow: Flow<Boolean> =
        itemsFlow
            .map { list -> list.any { it.state.isActiveOrPending() } }
            .distinctUntilChanged()

    /* ─────────────────────── Mapping helpers ─────────────────────── */

    /**
     * Convert [WorkInfo] into an [UploadItemUi] with derived status text.
     */
    private fun WorkInfo.toUploadItemUi(): UploadItemUi {
        val pct: Int? = extractPercent(this)
        val name: String = extractFileName(this)
        val url: String? = outputData
            .getString(GitHubUploadWorker.OUT_FILE_URL)
            ?.takeIf { it.isNotBlank() }

        val msg = when (state) {
            WorkInfo.State.ENQUEUED  -> "Waiting for network…"
            WorkInfo.State.RUNNING   -> "Uploading…"
            WorkInfo.State.SUCCEEDED -> "Uploaded"
            WorkInfo.State.FAILED    -> "Failed"
            WorkInfo.State.BLOCKED   -> "Blocked"
            WorkInfo.State.CANCELLED -> "Cancelled"
        }

        return UploadItemUi(
            id = id.toString(),
            fileName = name,
            percent = pct,
            state = state,
            fileUrl = url,
            message = msg
        )
    }

    /**
     * Extract upload progress percentage from [WorkInfo], if available.
     *
     * Progress sources (in order of priority):
     *  1. `progress[PROGRESS_PCT]` during upload.
     *  2. `outputData[PROGRESS_PCT]` on completion.
     *
     * Returns null when progress is missing or invalid.
     */
    private fun extractPercent(wi: WorkInfo): Int? {
        val fromProgress = wi.progress.getInt(GitHubUploadWorker.PROGRESS_PCT, -1)
        val fromOutput = wi.outputData.getInt(GitHubUploadWorker.PROGRESS_PCT, -1)
        val raw = when {
            fromProgress >= 0 -> fromProgress
            fromOutput >= 0 -> fromOutput
            else -> -1
        }
        return raw.takeIf { it in 0..100 }
    }

    /**
     * Resolve the best-available filename for display.
     *
     * Resolution order:
     *  1. `progress[PROGRESS_FILE]` during upload.
     *  2. `outputData[OUT_FILE_NAME]` after success/failure.
     *  3. A tag formatted as `"${GitHubUploadWorker.TAG}:file:<name>"`.
     *  4. `input_file` from progress or output.
     *  5. Fallback `"upload-<4chars>.json"` using work ID prefix.
     *
     * Note:
     * - WorkInfo does not reliably expose inputData, so we cannot read
     *   the original WorkRequest input here.
     */
    private fun extractFileName(wi: WorkInfo): String {
        progressName(wi)?.let { return it }
        outputName(wi)?.let { return it }
        tagName(wi)?.let { return it }
        inputName(wi)?.let { return it }
        return "upload-${wi.id.toString().take(4).lowercase(Locale.ROOT)}.json"
    }

    private fun progressName(wi: WorkInfo): String? =
        wi.progress
            .getString(GitHubUploadWorker.PROGRESS_FILE)
            ?.takeIf { it.isNotBlank() }

    private fun outputName(wi: WorkInfo): String? =
        wi.outputData
            .getString(GitHubUploadWorker.OUT_FILE_NAME)
            ?.takeIf { it.isNotBlank() }

    /**
     * Best-effort name inference from progress/output.
     *
     * This assumes the worker echoes the input file name into progress
     * early and into output on completion.
     */
    private fun inputName(wi: WorkInfo): String? {
        wi.progress
            .getString(KEY_INPUT_FILE)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        wi.outputData
            .getString(KEY_INPUT_FILE)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    /**
     * Extract filename from a structured tag.
     *
     * Expected tag format:
     *  - "${GitHubUploadWorker.TAG}:file:<name>"
     */
    private fun tagName(wi: WorkInfo): String? {
        val prefix = "${GitHubUploadWorker.TAG}:file:"
        val tag = wi.tags.firstOrNull { it.startsWith(prefix) } ?: return null
        val name = tag.removePrefix(prefix)
        return name.takeIf { it.isNotBlank() }
    }

    /**
     * Compute UI priority for an upload item.
     *
     * Lower rank → shown earlier in the list.
     */
    private fun UploadItemUi.priorityRank(): Int = when (state) {
        WorkInfo.State.RUNNING -> 0
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED -> 1
        WorkInfo.State.SUCCEEDED -> 2
        WorkInfo.State.FAILED,
        WorkInfo.State.CANCELLED -> 3
    }

    /**
     * Lightweight deep equality for the fields that affect rendering.
     *
     * This intentionally ignores [UploadItemUi.message], because it is
     * derived from [UploadItemUi.state].
     */
    private fun listsRenderEqual(
        a: List<UploadItemUi>,
        b: List<UploadItemUi>
    ): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x.id != y.id) return false
            if (x.state != y.state) return false
            if ((x.percent ?: -1) != (y.percent ?: -1)) return false
            if (x.fileUrl != y.fileUrl) return false
            if (!x.fileName.equals(y.fileName, ignoreCase = true)) return false
        }
        return true
    }

    /**
     * True when a work state should keep the HUD visible.
     */
    private fun WorkInfo.State.isActiveOrPending(): Boolean = when (this) {
        WorkInfo.State.RUNNING,
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED -> true
        WorkInfo.State.SUCCEEDED,
        WorkInfo.State.FAILED,
        WorkInfo.State.CANCELLED -> false
    }

    companion object {
        private const val KEY_INPUT_FILE = "input_file"

        /**
         * Compose-friendly factory.
         *
         * Usage:
         * ```kotlin
         * val app = LocalContext.current.applicationContext as Application
         * val vm: UploadQueueViewModel = viewModel(
         *     factory = UploadQueueViewModel.factory(app)
         * )
         * ```
         */
        fun factory(app: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UploadQueueViewModel(app) as T
        }
    }
}
