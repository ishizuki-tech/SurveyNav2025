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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * UI-facing description of a single upload.
 *
 * English comment:
 * - [percent] == null means "unknown / not reported".
 * - [fileUrl] is filled only after success (worker writes OUT_FILE_URL).
 */
data class UploadItemUi(
    val id: String,
    val fileName: String,
    val percent: Int?,          // null = unknown
    val state: WorkInfo.State,
    val fileUrl: String?,       // populated on success
    val message: String? = null // short human text derived from state
)

/**
 * Observes WorkManager for tasks tagged with [GitHubUploadWorker.TAG] and
 * maps their WorkInfo into a light UI model stream.
 *
 * English comment:
 * - We consume WorkManager's LiveData via .asFlow() for reliability (it is
 *   the officially supported observation path for WorkManager state).
 * - Mapping keeps allocations small and avoids unnecessary recompositions
 *   via a hand-rolled distinctUntilChanged comparator.
 */
class UploadQueueViewModel(app: Application) : AndroidViewModel(app) {

    private val wm: WorkManager = WorkManager.getInstance(app)

    /**
     * Emits the current list of uploads, sorted by priority:
     *   RUNNING → ENQUEUED/BLOCKED → SUCCEEDED → (FAILED/CANCELLED) → else
     * then by file name (case-insensitive) for a stable visual order.
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
            // Only emit when something that affects rendering actually changed.
            .distinctUntilChanged { old, new -> listsRenderEqual(old, new) }

    /* -------------------------- Mapping helpers -------------------------- */

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
     * English comment:
     * Try progress sources in this order:
     *  1) progress[PROGRESS_PCT] (live updates)
     *  2) outputData[PROGRESS_PCT] (some workers finalize here)
     *  -> clamp to 0..100, return null if missing/invalid.
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
     * English comment:
     * Resolve the best-available filename for display:
     *  1) progress[PROGRESS_FILE] during upload
     *  2) outputData[OUT_FILE_NAME] after success/failure
     *  3) a tag like "GitHubUpload:file:<name>"
     *  4) inputData (in case you pass the name there)
     *  5) deterministic fallback "upload-<4chars>.json"
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

    private fun inputName(wi: WorkInfo): String? =
        wi.progress.keyValueMap["input_file"] as? String
            ?: wi.outputData.keyValueMap["input_file"] as? String

    private fun tagName(wi: WorkInfo): String? =
        wi.tags.firstOrNull { it.startsWith("${GitHubUploadWorker.TAG}:file:") }
            ?.substringAfter(":file:")
            ?.takeIf { it.isNotBlank() }

    /**
     * English comment:
     * Rank uploads for the UI list.
     * Lower number = higher in the list.
     */
    private fun UploadItemUi.priorityRank(): Int = when (state) {
        WorkInfo.State.RUNNING -> 0
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> 1
        WorkInfo.State.SUCCEEDED -> 2
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> 3
    }

    /**
     * English comment:
     * Lightweight deep-equality for render-critical fields.
     * We purposefully ignore [message] text — it derives from [state].
     */
    private fun listsRenderEqual(a: List<UploadItemUi>, b: List<UploadItemUi>): Boolean {
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

    companion object {
        /**
         * English comment:
         * Compose-friendly factory so you can do:
         *
         * val vm = viewModel<UploadQueueViewModel>(
         *     factory = UploadQueueViewModel.factory(app)
         * )
         */
        fun factory(app: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UploadQueueViewModel(app) as T
        }
    }
}
