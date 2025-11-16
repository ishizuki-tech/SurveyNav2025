/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubUploadWorker.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Foreground-capable WorkManager coroutine worker that uploads one
 *  local JSON payload to GitHub using the REST "Create or Update File
 *  Contents" API via [GitHubUploader].
 *
 *  This worker ensures compliance with Android 14+ background execution
 *  limits by declaring the `DATA_SYNC` foreground service type and by
 *  maintaining visible progress notifications during upload.
 *
 *  Features:
 *   • Safe, resumable background upload (WorkManager + Foreground Service)
 *   • Determinate progress reporting via both notification and WorkData
 *   • Automatic exponential backoff retry on transient failures
 *   • Robust input validation and output data reporting
 *   • Automatic deletion of successfully uploaded local files
 *
 *  Typical usage:
 *  ---------------------------------------------------------------------
 *  ```kotlin
 *  val cfg = GitHubUploader.GitHubConfig(
 *      owner = "ishizuki-tech",
 *      repo = "SurveyExports",
 *      token = GITHUB_TOKEN,
 *      branch = "main",
 *      pathPrefix = "" // date-based folder is injected by GitHubUploader
 *  )
 *
 *  GitHubUploadWorker.enqueue(
 *      context = context,
 *      cfg = cfg,
 *      fileName = "survey_${System.currentTimeMillis()}",
 *      jsonContent = jsonString
 *  )
 *  ```
 * =====================================================================
 */

package com.negi.survey.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.negi.survey.R
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Coroutine-based [WorkManager] worker responsible for uploading one local JSON file
 * to GitHub via [GitHubUploader]. Runs as a foreground service on Android 10+,
 * which is required for long-running background operations such as data syncs.
 *
 * Responsibilities:
 * - Read the specified file from the app's `pending_uploads` directory.
 * - Stream the file contents to GitHub with visible progress.
 * - Handle automatic retries using exponential backoff.
 * - Clean up successfully uploaded local files.
 */
class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * Main coroutine entry point for WorkManager execution.
     *
     * Implementation steps:
     *  1. Parse and validate GitHub configuration and file inputs.
     *  2. Create a foreground notification for upload progress.
     *  3. Load the JSON file into memory.
     *  4. Call [GitHubUploader.uploadJson] with a date-based remote path
     *     (path construction handled inside GitHubUploader via GitHubConfig).
     *  5. On success, delete the local file and emit output metadata.
     *  6. On failure, schedule retry or fail permanently depending on attempts.
     *
     * @return [Result.success] on success, [Result.retry] on transient failure,
     *         or [Result.failure] when unrecoverable.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    override suspend fun doWork(): Result {
        // ------------------------------------------------------------
        // 1️⃣ Parse and validate inputs
        // ------------------------------------------------------------
        val cfg = GitHubUploader.GitHubConfig(
            owner = inputData.getString(KEY_OWNER).orEmpty(),
            repo = inputData.getString(KEY_REPO).orEmpty(),
            token = inputData.getString(KEY_TOKEN).orEmpty(),
            branch = inputData.getString(KEY_BRANCH).orEmpty(),
            pathPrefix = inputData.getString(KEY_PATH_PREFIX).orEmpty()
        )

        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: File(filePath).name

        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            return Result.failure(
                workDataOf(ERROR_MESSAGE to "Invalid GitHub configuration (owner/repo/token).")
            )
        }

        if (filePath.isBlank()) {
            return Result.failure(workDataOf(ERROR_MESSAGE to "Missing file path."))
        }

        val pendingFile = File(filePath)
        if (!pendingFile.exists()) {
            return Result.failure(
                workDataOf(ERROR_MESSAGE to "Pending file not found: $filePath")
            )
        }

        // Remote path used only for output metadata (for UI / logs).
        // Actual upload path is constructed inside GitHubUploader based on:
        //   prefix + yyyy-MM-dd + fileName
        val remotePath = buildDatedRemotePath(cfg.pathPrefix, fileName)

        // ------------------------------------------------------------
        // 2️⃣ Prepare notification (required for foreground execution)
        // ------------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel()
        }
        val notifId = NOTIF_BASE + (abs(fileName.hashCode()) % 8000)
        setForegroundAsync(
            foregroundInfo(
                notificationId = notifId,
                pct = 0,
                title = "Uploading $fileName"
            )
        )

        // ------------------------------------------------------------
        // 3️⃣ Load file content into memory
        // ------------------------------------------------------------
        val json = runCatching { pendingFile.readText(Charsets.UTF_8) }.getOrElse {
            return Result.failure(
                workDataOf(ERROR_MESSAGE to "Failed to read file: ${it.message}")
            )
        }

        var lastPct = 0

        // ------------------------------------------------------------
        // 4️⃣ Execute upload and update progress in real time
        // ------------------------------------------------------------
        return try {
            val result = GitHubUploader.uploadJson(
                cfg = cfg,
                relativePath = fileName,
                content = json,
                message = "Upload $fileName (deferred)"
            ) { pct ->
                lastPct = pct.coerceIn(0, 100)
                setProgressAsync(
                    workDataOf(
                        PROGRESS_PCT to lastPct,
                        PROGRESS_FILE to fileName
                    )
                )
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = lastPct,
                        title = "Uploading $fileName"
                    )
                )
            }

            // --------------------------------------------------------
            // 5️⃣ Finalization: mark as success and clean up
            // --------------------------------------------------------
            setProgressAsync(
                workDataOf(
                    PROGRESS_PCT to 100,
                    PROGRESS_FILE to fileName
                )
            )
            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 100,
                    title = "Uploaded $fileName",
                    finished = true
                )
            )
            runCatching { pendingFile.delete() }

            Result.success(
                workDataOf(
                    OUT_FILE_NAME to fileName,
                    OUT_REMOTE_PATH to remotePath,
                    OUT_COMMIT_SHA to (result.commitSha ?: ""),
                    OUT_FILE_URL to (result.fileUrl ?: "")
                )
            )
        } catch (t: Throwable) {
            // --------------------------------------------------------
            // 6️⃣ Failure path: display failure and schedule retry
            // --------------------------------------------------------
            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = max(0, lastPct),
                    title = "Upload failed: $fileName",
                    error = true
                )
            )
            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(failData)
            }
        }
    }

    // -----------------------------------------------------------------
    // 🔧 Utility Functions
    // -----------------------------------------------------------------

    /**
     * Build a date-based remote path consistent with GitHubUploader:
     *
     *   prefix + yyyy-MM-dd + fileName
     *
     * Examples:
     *   prefix = ""          → "2025-11-15/file.json"
     *   prefix = "exports"   → "exports/2025-11-15/file.json"
     */
    private fun buildDatedRemotePath(prefix: String, fileName: String): String {
        val date = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        return listOf(
            prefix.trim('/'),
            date,
            fileName.trim('/')
        )
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    /**
     * Build [ForegroundInfo] with an upload progress notification.
     *
     * @param notificationId Stable per-file ID to avoid collisions.
     * @param pct Progress percentage (0–100).
     * @param title Notification title text.
     * @param finished Whether upload is fully completed.
     * @param error Whether this notification represents an error state.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun foregroundInfo(
        notificationId: Int,
        pct: Int,
        title: String,
        finished: Boolean = false,
        error: Boolean = false
    ): ForegroundInfo {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished && !error)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (finished || error) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(100, pct.coerceIn(0, 100), false)
        }

        return ForegroundInfo(
            notificationId,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /**
     * Ensure the notification channel for upload progress exists.
     * Safe to call multiple times.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannel() {
        val nm =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Uploads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays progress for ongoing uploads to GitHub."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    // -----------------------------------------------------------------
    // ⚙️ Companion Object: constants & enqueue helpers
    // -----------------------------------------------------------------
    companion object {

        /** Tag used for identifying GitHub upload work requests. */
        const val TAG = "github_upload"

        /** Notification channel ID for upload progress. */
        private const val CHANNEL_ID = "uploads"

        /** Base ID offset for unique per-file notifications. */
        private const val NOTIF_BASE = 3200

        /** Maximum number of retry attempts before final failure. */
        private const val MAX_ATTEMPTS = 5

        // Progress keys (emitted in intermediate workData)
        const val PROGRESS_PCT = "pct"
        const val PROGRESS_FILE = "file"

        // Input keys (provided via workDataOf)
        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_TOKEN = "token"
        const val KEY_BRANCH = "branch"
        const val KEY_PATH_PREFIX = "pathPrefix"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_NAME = "fileName"

        // Output keys (emitted in Result.success)
        const val OUT_FILE_NAME = "out.fileName"
        const val OUT_REMOTE_PATH = "out.remotePath"
        const val OUT_COMMIT_SHA = "out.commitSha"
        const val OUT_FILE_URL = "out.fileUrl"

        /** Output key for human-readable error messages. */
        const val ERROR_MESSAGE = "error"

        /**
         * Enqueue a work request to upload an existing file.
         *
         * - Enforced unique per file to prevent duplicate uploads.
         * - Requires an active network connection.
         * - Applies exponential backoff with a 30s initial delay.
         * - Runs expedited when WorkManager quota allows.
         *
         * @param context The [Context] to use for [WorkManager] operations.
         * @param cfg The [GitHubUploader.GitHubConfig] defining upload target.
         * @param file The file to upload.
         */
        fun enqueueExistingPayload(context: Context, cfg: GitHubUploader.GitHubConfig, file: File) {
            val name = file.name

            // English comment:
            // Use OneTimeWorkRequest explicitly so that enqueueUniqueWork(...) overload resolves correctly.
            val req: OneTimeWorkRequest = OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_OWNER to cfg.owner,
                        KEY_REPO to cfg.repo,
                        KEY_TOKEN to cfg.token,
                        KEY_BRANCH to cfg.branch,
                        KEY_PATH_PREFIX to cfg.pathPrefix,
                        KEY_FILE_PATH to file.absolutePath,
                        KEY_FILE_NAME to name
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .addTag("$TAG:file:$name")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("upload_$name", ExistingWorkPolicy.KEEP, req)
        }

        /**
         * Write JSON content into `/files/pending_uploads` and schedule an upload.
         *
         * If a file with the same name already exists, a numeric suffix (`_1`, `_2`, …)
         * is appended to avoid overwriting existing payloads.
         *
         * @param context Application context.
         * @param cfg GitHub upload configuration.
         * @param fileName Desired file name (e.g., `"results_2025"` or `"results_2025.json"`).
         * @param jsonContent Serialized JSON string to store and upload.
         */
        fun enqueue(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            fileName: String,
            jsonContent: String
        ) {
            require(fileName.isNotBlank()) { "fileName is blank." }

            val safeName = sanitizeName(fileName)
                .let { if (it.endsWith(".json", ignoreCase = true)) it else "$it.json" }

            val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
            val target = uniqueIfExists(File(dir, safeName))

            target.writeText(jsonContent, Charsets.UTF_8)
            enqueueExistingPayload(context, cfg, target)
        }

        /**
         * Replace all non `[A-Za-z0-9_.-]` characters with underscores to
         * produce a safe and portable file name.
         */
        private fun sanitizeName(name: String): String =
            name.replace(Regex("""[^\w\-.]"""), "_")

        /**
         * If the specified file already exists, append a numeric suffix (`_1`, `_2`, …)
         * until a non-existing candidate is found.
         */
        private fun uniqueIfExists(file: File): File {
            if (!file.exists()) return file
            val base = file.nameWithoutExtension
            val ext = file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
            var idx = 1
            while (true) {
                val candidate = File(file.parentFile, "${base}_$idx$ext")
                if (!candidate.exists()) return candidate
                idx++
            }
        }
    }
}
