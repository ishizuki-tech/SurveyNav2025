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
 *  Foreground-capable WorkManager coroutine worker that uploads either:
 *   - one local payload file (text/binary) using GitHubUploader (Contents API), OR
 *   - a large file (e.g., WAV) using GitHubReleaseUploader (Release Assets, streaming), OR
 *   - a collected logcat snapshot (gzip) using GitHubLogUploader.
 *
 *  Hardening goals:
 *   - Never crash on missing/empty/oversized files (skip-success instead).
 *   - Prefer streaming uploads for large binaries (Release Assets).
 *   - Never OOM due to naive readBytes()/base64 (guard + streaming).
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
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coroutine-based [WorkManager] worker responsible for uploading either:
 *  - a local file (small, via Contents API),
 *  - a large file (via Release Assets),
 *  - a logcat snapshot.
 */
class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private class ProgressTracker {
        @Volatile var latestPct: Int = 0
        @Volatile var lastUiPct: Int = -1
        @Volatile var lastUiAtMs: Long = 0L
    }

    override suspend fun doWork(): Result {
        val cfg = GitHubUploader.GitHubConfig(
            owner = inputData.getString(KEY_OWNER).orEmpty(),
            repo = inputData.getString(KEY_REPO).orEmpty(),
            token = inputData.getString(KEY_TOKEN).orEmpty(),
            branch = inputData.getString(KEY_BRANCH)?.takeIf { it.isNotBlank() } ?: "main",
            pathPrefix = inputData.getString(KEY_PATH_PREFIX).orEmpty()
        )

        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            return Result.failure(
                workDataOf(ERROR_MESSAGE to "Invalid GitHub configuration (owner/repo/token).")
            )
        }

        val mode = inputData.getString(KEY_MODE)?.lowercase(Locale.US) ?: MODE_FILE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel()
        }

        val notifTitleBase = when (mode) {
            MODE_LOGCAT -> "Uploading logcat"
            else -> "Uploading payload"
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val notifId = NOTIF_BASE + (abs((mode + stamp).hashCode()) % 8000)

        setForeground(
            foregroundInfo(
                notificationId = notifId,
                pct = 0,
                title = "$notifTitleBase…"
            )
        )

        val tracker = ProgressTracker()

        val progressCallback: (Int) -> Unit = progressCallback@{ pct ->
            val clamped = pct.coerceIn(0, 100)
            tracker.latestPct = clamped

            val now = System.currentTimeMillis()
            val changed = (clamped != tracker.lastUiPct)

            if (!changed) return@progressCallback
            if (clamped in 1..99 && (now - tracker.lastUiAtMs) < PROGRESS_UI_THROTTLE_MS) {
                return@progressCallback
            }

            tracker.lastUiPct = clamped
            tracker.lastUiAtMs = now

            runCatching {
                setProgressAsync(workDataOf(PROGRESS_PCT to clamped, PROGRESS_MODE to mode))
            }

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = clamped,
                        title = "$notifTitleBase…"
                    )
                )
            }
        }

        return when (mode) {
            MODE_LOGCAT -> doLogcatUpload(cfg, notifId, tracker, progressCallback)
            else -> doFileUpload(cfg, notifId, tracker, progressCallback)
        }
    }

    /**
     * File upload mode:
     * - Small files -> Contents API (existing GitHubUploader)
     * - Large binaries (e.g., WAV) -> Release Assets (streaming)
     */
    private suspend fun doFileUpload(
        cfg: GitHubUploader.GitHubConfig,
        notifId: Int,
        tracker: ProgressTracker,
        onProgress: (Int) -> Unit,
    ): Result {

        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: File(filePath).name

        if (filePath.isBlank()) {
            return Result.failure(workDataOf(ERROR_MESSAGE to "Missing file path."))
        }

        val pendingFile = File(filePath)

        if (!pendingFile.exists() || !pendingFile.isFile) {
            setForegroundAsync(
                foregroundInfo(notifId, 100, "Skipped (missing): $fileName", finished = true)
            )
            return Result.success(
                workDataOf(
                    OUT_MODE to MODE_FILE,
                    OUT_FILE_NAME to fileName,
                    OUT_SKIPPED to true,
                    OUT_SKIP_REASON to "missing_file:$filePath"
                )
            )
        }

        val fileSize = pendingFile.length()
        if (fileSize <= 0L) {
            setForegroundAsync(
                foregroundInfo(notifId, 100, "Skipped (empty): $fileName", finished = true)
            )
            return Result.success(
                workDataOf(
                    OUT_MODE to MODE_FILE,
                    OUT_FILE_NAME to fileName,
                    OUT_SKIPPED to true,
                    OUT_SKIP_REASON to "empty_file:$filePath"
                )
            )
        }

        val useReleaseForLarge = inputData.getBoolean(KEY_FILE_USE_RELEASE_ASSETS, true)
        val forceReleaseForWavOver = inputData.getLong(KEY_WAV_FORCE_RELEASE_OVER_BYTES, DEFAULT_WAV_FORCE_RELEASE_OVER_BYTES)
        val isWav = pendingFile.extension.equals("wav", ignoreCase = true)

        val estB64 = estimateBase64Bytes(fileSize)
        val tooBigForContents = fileSize > MAX_CONTENTS_API_BYTES_HINT || estB64 > MAX_CONTENTS_API_BASE64_BYTES_HINT
        val shouldUseRelease = useReleaseForLarge && (tooBigForContents || (isWav && fileSize >= forceReleaseForWavOver))

        val remotePathForUi = buildDatedRemotePath(cfg.pathPrefix, fileName)

        Log.d(
            TAG,
            "doFileUpload: owner=${cfg.owner} repo=${cfg.repo} branch=${cfg.branch} " +
                    "prefix='${cfg.pathPrefix}' filePath=$filePath fileName=$fileName size=$fileSize " +
                    "isWav=$isWav tooBigForContents=$tooBigForContents shouldUseRelease=$shouldUseRelease"
        )

        return try {
            onProgress(1)

            val result = withContext(Dispatchers.IO) {
                if (shouldUseRelease) {
                    val tag = inputData.getString(KEY_RELEASE_TAG)?.takeIf { it.isNotBlank() }
                        ?: defaultReleaseTag()

                    val releaseName = inputData.getString(KEY_RELEASE_NAME)?.takeIf { it.isNotBlank() }
                        ?: "Uploads $tag"

                    val overwrite = inputData.getBoolean(KEY_RELEASE_OVERWRITE_ASSET, true)
                    val createIfMissing = inputData.getBoolean(KEY_RELEASE_CREATE_IF_MISSING, true)

                    val upload = GitHubReleaseUploader.uploadAssetFromFile(
                        cfg = cfg,
                        tagName = tag,
                        releaseName = releaseName,
                        file = pendingFile,
                        assetName = fileName,
                        createIfMissing = createIfMissing,
                        overwriteIfExists = overwrite,
                        onProgress = onProgress
                    )

                    UploadOutcome.ReleaseAsset(upload)
                } else {
                    val extension = pendingFile.extension.lowercase(Locale.US)
                    val isText = TEXT_EXTENSIONS.contains(extension)

                    if (isText) {
                        val text = runCatching { pendingFile.readText(Charsets.UTF_8) }.getOrElse {
                            throw IOException("Failed to read text file: ${it.message}", it)
                        }

                        val out = GitHubUploader.uploadJson(
                            cfg = cfg,
                            relativePath = fileName,
                            content = text,
                            message = "Upload $fileName (deferred)",
                            onProgress = onProgress
                        )
                        UploadOutcome.Contents(out)
                    } else {
                        val bytes = readFileBytesCapped(
                            file = pendingFile,
                            capBytes = MAX_SAFE_IN_MEMORY_BYTES
                        )

                        val out = GitHubUploader.uploadFile(
                            cfg = cfg,
                            relativePath = fileName,
                            bytes = bytes,
                            message = "Upload $fileName (deferred)",
                            onProgress = onProgress
                        )
                        UploadOutcome.Contents(out)
                    }
                }
            }

            setForegroundAsync(
                foregroundInfo(notifId, 100, "Uploaded $fileName", finished = true)
            )

            val deleteOnSuccess = inputData.getBoolean(KEY_FILE_DELETE_ON_SUCCESS, true)
            if (deleteOnSuccess) runCatching { pendingFile.delete() }

            when (result) {
                is UploadOutcome.Contents -> {
                    Result.success(
                        workDataOf(
                            OUT_MODE to MODE_FILE,
                            OUT_FILE_NAME to fileName,
                            OUT_REMOTE_PATH to remotePathForUi,
                            OUT_COMMIT_SHA to (result.out.commitSha ?: ""),
                            OUT_FILE_URL to (result.out.fileUrl ?: ""),
                            OUT_SKIPPED to false,
                            OUT_UPLOAD_KIND to "contents"
                        )
                    )
                }
                is UploadOutcome.ReleaseAsset -> {
                    Result.success(
                        workDataOf(
                            OUT_MODE to MODE_FILE,
                            OUT_FILE_NAME to fileName,
                            OUT_REMOTE_PATH to "release:${result.out.releaseId}/$fileName",
                            OUT_RELEASE_ID to result.out.releaseId,
                            OUT_ASSET_ID to result.out.assetId,
                            OUT_ASSET_URL to result.out.assetUrl,
                            OUT_DOWNLOAD_URL to result.out.browserDownloadUrl,
                            OUT_SKIPPED to false,
                            OUT_UPLOAD_KIND to "release_asset"
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "doFileUpload: upload failed for $filePath", t)

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = tracker.latestPct.coerceIn(0, 100),
                    title = "Upload failed: $fileName",
                    error = true
                )
            )

            val failData = workDataOf(
                ERROR_MESSAGE to (t.message ?: "Unknown error"),
                OUT_FILE_NAME to fileName,
                OUT_REMOTE_PATH to remotePathForUi
            )

            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failData)
        }
    }

    private sealed class UploadOutcome {
        data class Contents(val out: GitHubUploader.UploadResult) : UploadOutcome()
        data class ReleaseAsset(val out: GitHubReleaseUploader.UploadResult) : UploadOutcome()
    }

    private suspend fun doLogcatUpload(
        cfg: GitHubUploader.GitHubConfig,
        notifId: Int,
        tracker: ProgressTracker,
        onProgress: (Int) -> Unit,
    ): Result {

        val remoteDir = inputData.getString(KEY_LOG_REMOTE_DIR) ?: "diagnostics/logs"
        val addDate = inputData.getBoolean(KEY_LOG_ADD_DATE, true)
        val includeHeader = inputData.getBoolean(KEY_LOG_INCLUDE_HEADER, true)
        val includeCrash = inputData.getBoolean(KEY_LOG_INCLUDE_CRASH, true)
        val maxBytes = inputData.getInt(KEY_LOG_MAX_UNCOMPRESSED, 850_000)

        return try {
            val out = withContext(Dispatchers.IO) {
                GitHubLogUploader.collectAndUploadLogcat(
                    context = applicationContext,
                    cfg = cfg,
                    remoteDir = remoteDir,
                    addDateSubdir = addDate,
                    includeDeviceHeader = includeHeader,
                    maxUncompressedBytes = maxBytes,
                    includeCrashBuffer = includeCrash,
                    onProgress = onProgress,
                )
            }

            setForegroundAsync(
                foregroundInfo(notifId, 100, "Uploaded logcat", finished = true)
            )

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_LOGCAT,
                    OUT_REMOTE_PATH to out.remotePath,
                    OUT_COMMIT_SHA to (out.commitSha ?: ""),
                    OUT_FILE_URL to (out.fileUrl ?: ""),
                    OUT_BYTES_RAW to out.bytesRaw,
                    OUT_BYTES_GZ to out.bytesGz,
                    OUT_SKIPPED to false
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doLogcatUpload: upload failed", t)

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = tracker.latestPct.coerceIn(0, 100),
                    title = "Log upload failed",
                    error = true
                )
            )

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))

            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failData)
        }
    }

    private fun buildDatedRemotePath(prefix: String, fileName: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return listOf(prefix.trim('/'), date, fileName.trim('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    private fun shouldRetry(t: Throwable): Boolean {
        val msg = t.message.orEmpty()

        if (msg.contains("too large", ignoreCase = true)) return false
        if (msg.contains("invalid github configuration", ignoreCase = true)) return false
        if (msg.contains("bad credentials", ignoreCase = true)) return false
        if (msg.contains("requires authentication", ignoreCase = true)) return false

        if (msg.contains("401")) return false
        if (msg.contains("403")) return false
        if (msg.contains("404")) return false

        if (t is IOException) return true
        if (msg.contains("timeout", ignoreCase = true)) return true
        if (msg.contains("temporarily", ignoreCase = true)) return true
        if (msg.contains("rate limit", ignoreCase = true)) return true

        return false
    }

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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

    private fun readFileBytesCapped(file: File, capBytes: Int): ByteArray {
        val len = file.length()
        if (len <= 0L) return ByteArray(0)
        if (len > capBytes.toLong()) {
            throw IOException("File exceeds in-memory cap: name=${file.name} size=$len cap=$capBytes")
        }

        FileInputStream(file).use { fis ->
            val input = BufferedInputStream(fis)
            val baos = ByteArrayOutputStream(len.toInt().coerceAtMost(capBytes))
            val buf = ByteArray(64 * 1024)

            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > capBytes) {
                    throw IOException("Read exceeds cap: name=${file.name} total=$total cap=$capBytes")
                }
                baos.write(buf, 0, n)
            }
            return baos.toByteArray()
        }
    }

    private fun estimateBase64Bytes(rawBytes: Long): Long {
        return ((rawBytes + 2L) / 3L) * 4L
    }

    private fun defaultReleaseTag(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "uploads-$date"
    }

    companion object {
        const val TAG = "github_upload"

        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_BASE = 3200
        private const val MAX_ATTEMPTS = 5

        private const val MAX_CONTENTS_API_BYTES_HINT = 900_000L
        private const val MAX_CONTENTS_API_BASE64_BYTES_HINT = 1_250_000L

        private const val MAX_SAFE_IN_MEMORY_BYTES = 4 * 1024 * 1024 // 4MB
        private const val PROGRESS_UI_THROTTLE_MS = 250L

        private const val DEFAULT_WAV_FORCE_RELEASE_OVER_BYTES = 1_000_000L

        private val TEXT_EXTENSIONS = setOf("json", "jsonl", "txt", "csv")

        private const val MODE_FILE = "file"
        private const val MODE_LOGCAT = "logcat"

        const val PROGRESS_PCT = "pct"
        const val PROGRESS_MODE = "mode"

        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_TOKEN = "token"
        const val KEY_BRANCH = "branch"
        const val KEY_PATH_PREFIX = "pathPrefix"
        const val KEY_MODE = "mode"

        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_NAME = "fileName"

        // Large-file strategy keys
        const val KEY_FILE_USE_RELEASE_ASSETS = "file.useReleaseAssets"
        const val KEY_FILE_DELETE_ON_SUCCESS = "file.deleteOnSuccess"
        const val KEY_WAV_FORCE_RELEASE_OVER_BYTES = "file.wavForceReleaseOverBytes"

        // Release config keys
        const val KEY_RELEASE_TAG = "release.tag"
        const val KEY_RELEASE_NAME = "release.name"
        const val KEY_RELEASE_CREATE_IF_MISSING = "release.createIfMissing"
        const val KEY_RELEASE_OVERWRITE_ASSET = "release.overwriteAsset"

        const val KEY_LOG_REMOTE_DIR = "log.remoteDir"
        const val KEY_LOG_ADD_DATE = "log.addDate"
        const val KEY_LOG_INCLUDE_HEADER = "log.includeHeader"
        const val KEY_LOG_INCLUDE_CRASH = "log.includeCrash"
        const val KEY_LOG_MAX_UNCOMPRESSED = "log.maxUncompressed"

        const val OUT_MODE = "out.mode"
        const val OUT_FILE_NAME = "out.fileName"
        const val OUT_REMOTE_PATH = "out.remotePath"
        const val OUT_COMMIT_SHA = "out.commitSha"
        const val OUT_FILE_URL = "out.fileUrl"
        const val OUT_BYTES_RAW = "out.bytesRaw"
        const val OUT_BYTES_GZ = "out.bytesGz"

        const val OUT_SKIPPED = "out.skipped"
        const val OUT_SKIP_REASON = "out.skipReason"

        // Extra outputs for Release Assets
        const val OUT_UPLOAD_KIND = "out.uploadKind"
        const val OUT_RELEASE_ID = "out.releaseId"
        const val OUT_ASSET_ID = "out.assetId"
        const val OUT_ASSET_URL = "out.assetUrl"
        const val OUT_DOWNLOAD_URL = "out.downloadUrl"

        const val ERROR_MESSAGE = "error"

        private fun sanitizeWorkName(value: String): String {
            return value
                .trim()
                .replace(Regex("""[^\w\-.]+"""), "_")
                .take(120)
        }

        fun enqueueExistingPayload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            file: File
        ) {
            val name = file.name
            val safeUnique = sanitizeWorkName(name)
            val uniqueWorkName = "upload_$safeUnique"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_FILE,
                            KEY_OWNER to cfg.owner,
                            KEY_REPO to cfg.repo,
                            KEY_TOKEN to cfg.token,
                            KEY_BRANCH to cfg.branch,
                            KEY_PATH_PREFIX to cfg.pathPrefix,
                            KEY_FILE_PATH to file.absolutePath,
                            KEY_FILE_NAME to name,

                            // Default: large files go to Release Assets.
                            KEY_FILE_USE_RELEASE_ASSETS to true,
                            KEY_FILE_DELETE_ON_SUCCESS to true,
                            KEY_WAV_FORCE_RELEASE_OVER_BYTES to DEFAULT_WAV_FORCE_RELEASE_OVER_BYTES,

                            // Release behavior defaults.
                            KEY_RELEASE_CREATE_IF_MISSING to true,
                            KEY_RELEASE_OVERWRITE_ASSET to true
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
                    .addTag("$TAG:file:$safeUnique")
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, req)
        }

        fun enqueueLogcatUpload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            remoteDir: String = "diagnostics/logs",
            addDateSubdir: Boolean = true,
            includeDeviceHeader: Boolean = true,
            includeCrashBuffer: Boolean = true,
            maxUncompressedBytes: Int = 850_000,
        ) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val uniqueName = "upload_logcat_$stamp"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_LOGCAT,
                            KEY_OWNER to cfg.owner,
                            KEY_REPO to cfg.repo,
                            KEY_TOKEN to cfg.token,
                            KEY_BRANCH to cfg.branch,
                            KEY_PATH_PREFIX to cfg.pathPrefix,

                            KEY_LOG_REMOTE_DIR to remoteDir,
                            KEY_LOG_ADD_DATE to addDateSubdir,
                            KEY_LOG_INCLUDE_HEADER to includeDeviceHeader,
                            KEY_LOG_INCLUDE_CRASH to includeCrashBuffer,
                            KEY_LOG_MAX_UNCOMPRESSED to maxUncompressedBytes,
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
                    .addTag("$TAG:logcat")
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
        }
    }
}
