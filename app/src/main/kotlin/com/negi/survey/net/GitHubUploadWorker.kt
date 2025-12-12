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
 *  local payload file to GitHub using the REST "Create or Update File
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
 *   • Supports both JSON (text) and binary payloads (e.g., WAV audio)
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Coroutine-based [WorkManager] worker responsible for uploading one local file
 * to GitHub via [GitHubUploader].
 *
 * Responsibilities:
 * - Read the specified file from disk (text vs binary).
 * - Stream the file contents to GitHub with visible progress.
 * - Handle automatic retries using exponential backoff.
 * - Clean up successfully uploaded local files.
 */
class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * Execute a single upload job.
     *
     * Steps:
     *  1. Parse and validate inputs.
     *  2. Check file existence and size safety for Contents API.
     *  3. Start a foreground notification.
     *  4. Load content as text or bytes based on file extension.
     *  5. Upload via [GitHubUploader].
     *  6. On success, delete local file and emit output metadata.
     *  7. On failure, retry if transient and attempts remain.
     */
    override suspend fun doWork(): Result {
        // ------------------------------------------------------------
        // 1) Parse and validate inputs
        // ------------------------------------------------------------
        val cfg = GitHubUploader.GitHubConfig(
            owner = inputData.getString(KEY_OWNER).orEmpty(),
            repo = inputData.getString(KEY_REPO).orEmpty(),
            token = inputData.getString(KEY_TOKEN).orEmpty(),
            branch = inputData.getString(KEY_BRANCH)?.takeIf { it.isNotBlank() } ?: "main",
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

        val fileSize = pendingFile.length()
        if (fileSize <= 0L) {
            return Result.failure(
                workDataOf(ERROR_MESSAGE to "Pending file is empty: $filePath")
            )
        }

        // This guard mirrors the practical constraints of the GitHub Contents API.
        // For large audio, prefer Git LFS or Releases-based upload flows.
        if (fileSize > MAX_CONTENTS_API_BYTES_HINT) {
            return Result.failure(
                workDataOf(
                    ERROR_MESSAGE to
                            "File too large for GitHub Contents API " +
                            "(size=$fileSize, limit~$MAX_CONTENTS_API_BYTES_HINT)."
                )
            )
        }

        Log.d(
            TAG,
            "doWork: owner=${cfg.owner} repo=${cfg.repo} branch=${cfg.branch} " +
                    "prefix='${cfg.pathPrefix}' filePath=$filePath fileName=$fileName size=$fileSize"
        )

        // Remote path is used only for output metadata (UI/logs).
        val remotePath = buildDatedRemotePath(cfg.pathPrefix, fileName)

        // ------------------------------------------------------------
        // 2) Prepare notification (foreground execution)
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

        var lastPct = -1

        val progressCallback: (Int) -> Unit = progressCallback@{ pct ->
            val clamped = pct.coerceIn(0, 100)

            // Avoid noisy notification churn by only updating when progress changes.
            if (clamped == lastPct) return@progressCallback

            lastPct = clamped

            setProgressAsync(
                workDataOf(
                    PROGRESS_PCT to clamped,
                    PROGRESS_FILE to fileName
                )
            )

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = clamped,
                    title = "Uploading $fileName"
                )
            )
        }

        // ------------------------------------------------------------
        // 3) Load file content and execute upload
        // ------------------------------------------------------------
        return try {
            val extension = pendingFile.extension.lowercase(Locale.US)

            Log.d(TAG, "doWork: extension=$extension text=${TEXT_EXTENSIONS.contains(extension)}")

            val result = if (TEXT_EXTENSIONS.contains(extension)) {
                val text = runCatching { pendingFile.readText(Charsets.UTF_8) }.getOrElse {
                    return Result.failure(
                        workDataOf(ERROR_MESSAGE to "Failed to read text file: ${it.message}")
                    )
                }

                GitHubUploader.uploadJson(
                    cfg = cfg,
                    relativePath = fileName,
                    content = text,
                    message = "Upload $fileName (deferred)",
                    onProgress = progressCallback
                )
            } else {
                val bytes = runCatching { pendingFile.readBytes() }.getOrElse {
                    return Result.failure(
                        workDataOf(ERROR_MESSAGE to "Failed to read binary file: ${it.message}")
                    )
                }

                GitHubUploader.uploadFile(
                    cfg = cfg,
                    relativePath = fileName,
                    bytes = bytes,
                    message = "Upload $fileName (deferred)",
                    onProgress = progressCallback
                )
            }

            Log.d(TAG, "doWork: upload success fileUrl=${result.fileUrl} sha=${result.commitSha}")

            // --------------------------------------------------------
            // 4) Finalization
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

            runCatching {
                if (pendingFile.delete()) {
                    Log.d(TAG, "doWork: deleted local file $filePath")
                } else {
                    Log.d(TAG, "doWork: failed to delete local file $filePath")
                }
            }

            Result.success(
                workDataOf(
                    OUT_FILE_NAME to fileName,
                    OUT_REMOTE_PATH to remotePath,
                    OUT_COMMIT_SHA to (result.commitSha ?: ""),
                    OUT_FILE_URL to (result.fileUrl ?: "")
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doWork: upload failed for $filePath", t)

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = max(0, lastPct),
                    title = "Upload failed: $fileName",
                    error = true
                )
            )

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))

            // Only retry when it looks like a transient failure.
            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(failData)
            }
        }
    }

    // -----------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------

    /**
     * Build a date-based remote path consistent with GitHubUploader:
     *
     *   prefix + yyyy-MM-dd + fileName
     */
    private fun buildDatedRemotePath(prefix: String, fileName: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return listOf(
            prefix.trim('/'),
            date,
            fileName.trim('/')
        )
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    /**
     * Determine if the throwable should be treated as transient.
     *
     * This heuristic is conservative:
     * - Network-ish failures and generic IO issues are retried.
     * - Obvious permanent failures (size guard, auth config, etc.) are not.
     */
    private fun shouldRetry(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        if (msg.contains("too large", ignoreCase = true)) return false
        if (msg.contains("Invalid GitHub configuration", ignoreCase = true)) return false
        return t is IOException || msg.contains("timeout", ignoreCase = true)
    }

    /**
     * Build [ForegroundInfo] with an upload progress notification.
     *
     * - On Android 10+ uses DATA_SYNC foreground service type.
     * - On older versions falls back to the 2-arg constructor.
     */
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

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Ensure the notification channel for upload progress exists.
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
    // Companion Object
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

        /**
         * Hint size limit for Contents API usage.
         *
         * Keep this aligned with the guard in GitHubUploader.
         */
        private const val MAX_CONTENTS_API_BYTES_HINT = 900_000L

        /** Extensions treated as UTF-8 text payloads. */
        private val TEXT_EXTENSIONS = setOf(
            "json",
            "jsonl",
            "txt",
            "csv"
        )

        // Progress keys
        const val PROGRESS_PCT = "pct"
        const val PROGRESS_FILE = "file"

        // Input keys
        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_TOKEN = "token"
        const val KEY_BRANCH = "branch"
        const val KEY_PATH_PREFIX = "pathPrefix"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_NAME = "fileName"

        // Output keys
        const val OUT_FILE_NAME = "out.fileName"
        const val OUT_REMOTE_PATH = "out.remotePath"
        const val OUT_COMMIT_SHA = "out.commitSha"
        const val OUT_FILE_URL = "out.fileUrl"

        /** Output key for human-readable error messages. */
        const val ERROR_MESSAGE = "error"

        /**
         * Enqueue a work request to upload an existing file.
         *
         * - Unique per file name to prevent duplicate uploads.
         * - Requires a connected network.
         * - Exponential backoff with a 30s initial delay.
         * - Runs expedited when quota allows.
         */
        fun enqueueExistingPayload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            file: File
        ) {
            val name = file.name

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
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
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS
                    )
                    .setExpedited(
                        OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                    )
                    .addTag(TAG)
                    .addTag("$TAG:file:$name")
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "upload_$name",
                    ExistingWorkPolicy.KEEP,
                    req
                )
        }

        /**
         * Write JSON content into `/files/pending_uploads` and schedule an upload.
         *
         * If a file with the same name already exists, a numeric suffix is appended.
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
         * Replace all non `[A-Za-z0-9_.-]` characters with underscores.
         */
        private fun sanitizeName(name: String): String =
            name.replace(Regex("""[^\w\-.]"""), "_")

        /**
         * Append a numeric suffix until a non-existing file name is found.
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
