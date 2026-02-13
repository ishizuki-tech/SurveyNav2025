/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseUploadWorker.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Foreground-capable WorkManager coroutine worker that uploads either:
 *   - one local payload file (streamed) to Supabase Storage, OR
 *   - a collected logcat snapshot (gzip) to Supabase Storage.
 *
 *  Debug / robustness upgrades:
 *   - Avoid 20MB default guard mismatch for large WAV by auto-expanding maxRawBytesHint.
 *   - Optional date-subdir for FILE uploads (default: false to match immediate path style).
 *   - Prevent double prefix (surveyapp/surveyapp/...) when remoteDir already contains prefix.
 *   - NotificationChannel is created only on API 26+ (safe for older devices).
 *   - More explicit logs: inputs, normalized paths, objectPath.
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

class SupabaseUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE)?.lowercase(Locale.US) ?: MODE_FILE

        val connectTimeoutMs = inputData.getInt(KEY_CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS)
            .coerceIn(3_000, 120_000)
        val readTimeoutMs = inputData.getInt(KEY_READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS)
            .coerceIn(10_000, 300_000)

        val cfg = SupabaseUploader.SupabaseConfig(
            supabaseUrl = inputData.getString(KEY_URL).orEmpty(),
            anonKey = inputData.getString(KEY_ANON_KEY).orEmpty(),
            bucket = inputData.getString(KEY_BUCKET).orEmpty(),
            pathPrefix = inputData.getString(KEY_PATH_PREFIX).orEmpty().ifBlank { DEFAULT_PREFIX_FALLBACK },
            maxRawBytesHint = inputData.getLong(KEY_MAX_BYTES_HINT, DEFAULT_MAX_BYTES_HINT).coerceAtLeast(1L),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )

        if (cfg.supabaseUrl.isBlank() || cfg.anonKey.isBlank() || cfg.bucket.isBlank()) {
            return Result.failure(
                workDataOf(ERROR_MESSAGE to "Invalid Supabase configuration (url/anonKey/bucket).")
            )
        }

        maybeEnsureChannel()

        val notifTitleBase = when (mode) {
            MODE_LOGCAT -> "Uploading logcat (Supabase)"
            else -> "Uploading payload (Supabase)"
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val notifId = NOTIF_BASE + (abs((mode + stamp).hashCode()) % 8000)

        runCatching {
            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 0,
                    title = "$notifTitleBase…"
                )
            )
        }.onFailure { t ->
            Log.w(TAG, "setForegroundAsync failed (continuing): ${t.message}", t)
        }

        val lastPctRef = intArrayOf(-1)
        val progressCallback: (Int) -> Unit = progressCallback@{ pct ->
            val clamped = pct.coerceIn(0, 100)
            if (clamped == lastPctRef[0]) return@progressCallback
            lastPctRef[0] = clamped

            runCatching { setProgressAsync(workDataOf(PROGRESS_PCT to clamped, PROGRESS_MODE to mode)) }
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

        val currentPct: () -> Int = { lastPctRef[0].coerceAtLeast(0) }

        Log.d(
            TAG,
            "doWork: mode=$mode attempt=$runAttemptCount urlSet=${cfg.supabaseUrl.isNotBlank()} " +
                    "bucket=${cfg.bucket} prefix=${cfg.pathPrefix} timeouts=${cfg.connectTimeoutMs}/${cfg.readTimeoutMs} " +
                    "maxHint=${cfg.maxRawBytesHint}"
        )

        return when (mode) {
            MODE_LOGCAT -> doLogcatUpload(cfg, notifId, progressCallback, currentPct)
            else -> doFileUpload(cfg, notifId, progressCallback, currentPct)
        }
    }

    private suspend fun doFileUpload(
        cfg: SupabaseUploader.SupabaseConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int
    ): Result {

        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        if (filePath.isBlank()) return Result.failure(workDataOf(ERROR_MESSAGE to "Missing file path."))

        val f = File(filePath)
        if (!f.exists()) return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file not found: $filePath"))

        val size = f.length()
        if (size <= 0L) return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file is empty: $filePath"))

        val userJwt = inputData.getString(KEY_USER_JWT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val rawRemoteDir = inputData.getString(KEY_REMOTE_DIR)
            ?.trim()
            .orEmpty()
            .ifBlank { "regular" }

        val fileNameRaw = inputData.getString(KEY_FILE_NAME) ?: f.name
        val fileName = fileNameRaw.trim().trimStart('/')

        val addDateSubdir = inputData.getBoolean(KEY_FILE_ADD_DATE, false)
        val upsert = inputData.getBoolean(KEY_UPSERT, false)
        val contentType = inputData.getString(KEY_CONTENT_TYPE) ?: "application/octet-stream"

        val prefix = normalizePrefix(cfg.pathPrefix)
        val remoteDir = normalizeRemoteDir(prefix = prefix, remoteDir = rawRemoteDir)
        val basePath = listOf(prefix, remoteDir).filter { it.isNotBlank() }.joinToString("/")

        val objectPath = if (addDateSubdir) {
            SupabaseUploader.buildDatedObjectPath(basePath, fileName)
        } else {
            listOf(basePath, fileName).filter { it.isNotBlank() }.joinToString("/")
        }

        // IMPORTANT: auto-expand the raw-bytes guard for large files (e.g., WAV 30–50MB).
        val effectiveCfg = cfg.copy(maxRawBytesHint = max(cfg.maxRawBytesHint, size + 1024L))

        Log.d(
            TAG,
            "doFileUpload: local=${f.name} bytes=$size contentType=$contentType upsert=$upsert addDate=$addDateSubdir " +
                    "rawRemoteDir=$rawRemoteDir normRemoteDir=$remoteDir prefix=$prefix objectPath=$objectPath bucket=${cfg.bucket}"
        )

        return try {
            val res = SupabaseUploader.uploadFile(
                cfg = effectiveCfg,
                objectPath = objectPath,
                file = f,
                contentType = contentType,
                upsert = upsert,
                tokenOverride = userJwt,
                onProgress = onProgress
            )

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = 100,
                        title = "Uploaded $fileName (Supabase)",
                        finished = true
                    )
                )
            }

            runCatching { f.delete() }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_FILE,
                    OUT_FILE_NAME to fileName,
                    OUT_OBJECT_PATH to res.objectPath,
                    OUT_PUBLIC_URL to (res.publicUrl ?: ""),
                    OUT_ETAG to (res.etag ?: ""),
                    OUT_REQUEST_ID to (res.requestId ?: "")
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doFileUpload: upload failed file=$filePath objectPath=$objectPath", t)

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = currentPct(),
                        title = "Upload failed: $fileName (Supabase)",
                        error = true
                    )
                )
            }

            val failData = workDataOf(
                ERROR_MESSAGE to buildString {
                    append(t.message ?: "Unknown error")
                    append(" | objectPath=").append(objectPath)
                    append(" | bytes=").append(size)
                }
            )

            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(failData)
        }
    }

    private suspend fun doLogcatUpload(
        cfg: SupabaseUploader.SupabaseConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int
    ): Result {
        val remoteDir = inputData.getString(KEY_REMOTE_DIR)?.ifBlank { "logcat" } ?: "logcat"
        val addDate = inputData.getBoolean(KEY_LOG_ADD_DATE, true)
        val includeHeader = inputData.getBoolean(KEY_LOG_INCLUDE_HEADER, true)
        val includeCrash = inputData.getBoolean(KEY_LOG_INCLUDE_CRASH, true)
        val maxBytes = inputData.getInt(KEY_LOG_MAX_UNCOMPRESSED, 850_000)

        val userJwt = inputData.getString(KEY_USER_JWT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return try {
            val out = SupabaseLogUploader.collectAndUploadLogcat(
                context = applicationContext,
                cfg = cfg,
                remoteDir = remoteDir,
                addDateSubdir = addDate,
                includeDeviceHeader = includeHeader,
                maxUncompressedBytes = maxBytes,
                includeCrashBuffer = includeCrash,
                tokenOverride = userJwt,
                onProgress = onProgress
            )

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = 100,
                        title = "Uploaded logcat (Supabase)",
                        finished = true
                    )
                )
            }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_LOGCAT,
                    OUT_OBJECT_PATH to out.objectPath,
                    OUT_PUBLIC_URL to (out.publicUrl ?: ""),
                    OUT_ETAG to (out.etag ?: ""),
                    OUT_REQUEST_ID to (out.requestId ?: ""),
                    OUT_BYTES_RAW to out.bytesRaw,
                    OUT_BYTES_GZ to out.bytesGz
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doLogcatUpload: upload failed", t)

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = currentPct(),
                        title = "Log upload failed (Supabase)",
                        error = true
                    )
                )
            }

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))
            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(failData)
        }
    }

    private fun shouldRetry(t: Throwable): Boolean {
        val msg = t.message.orEmpty()

        if (msg.contains("too large", ignoreCase = true)) return false
        if (msg.contains("invalid supabase configuration", ignoreCase = true)) return false

        if (msg.contains("401")) return false
        if (msg.contains("403")) return false
        if (msg.contains("row-level security", ignoreCase = true)) return false

        return t is IOException || msg.contains("timeout", ignoreCase = true)
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

        if (finished || error) builder.setProgress(0, 0, false)
        else builder.setProgress(100, pct.coerceIn(0, 100), false)

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Create notification channel only on API 26+.
     */
    private fun maybeEnsureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Uploads (Supabase)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays progress for ongoing uploads to Supabase Storage."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Normalize prefix. If blank, fall back to DEFAULT_PREFIX_FALLBACK.
     */
    private fun normalizePrefix(prefix: String): String {
        return prefix.trim().trim('/').ifBlank { DEFAULT_PREFIX_FALLBACK }
    }

    /**
     * Prevent double prefix:
     * - remoteDir == prefix -> ""
     * - remoteDir startsWith "prefix/" -> remove it
     */
    private fun normalizeRemoteDir(prefix: String, remoteDir: String): String {
        val dir = remoteDir.trim().trim('/')
        if (dir.isBlank()) return ""
        if (dir == prefix) return ""
        if (dir.startsWith("$prefix/")) return dir.removePrefix("$prefix/").trim('/')
        return dir
    }

    companion object {
        const val TAG = "supabase_upload"

        private const val CHANNEL_ID = "uploads_supabase"
        private const val NOTIF_BASE = 4200
        private const val MAX_ATTEMPTS = 5

        private const val MODE_FILE = "file"
        private const val MODE_LOGCAT = "logcat"

        private const val DEFAULT_PREFIX_FALLBACK = "surveyapp"

        const val PROGRESS_PCT = "pct"
        const val PROGRESS_MODE = "mode"

        // Config keys
        const val KEY_URL = "sb.url"
        const val KEY_ANON_KEY = "sb.anonKey"
        const val KEY_BUCKET = "sb.bucket"
        const val KEY_PATH_PREFIX = "sb.pathPrefix"
        const val KEY_MODE = "mode"
        const val KEY_USER_JWT = "sb.userJwt"
        const val KEY_MAX_BYTES_HINT = "file.maxBytesHint"
        const val KEY_CONNECT_TIMEOUT_MS = "sb.connectTimeoutMs"
        const val KEY_READ_TIMEOUT_MS = "sb.readTimeoutMs"

        // File keys
        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_REMOTE_DIR = "remoteDir"
        const val KEY_CONTENT_TYPE = "contentType"
        const val KEY_UPSERT = "upsert"
        const val KEY_FILE_ADD_DATE = "file.addDate"

        // Logcat keys
        const val KEY_LOG_ADD_DATE = "log.addDate"
        const val KEY_LOG_INCLUDE_HEADER = "log.includeHeader"
        const val KEY_LOG_INCLUDE_CRASH = "log.includeCrash"
        const val KEY_LOG_MAX_UNCOMPRESSED = "log.maxUncompressed"

        // Output keys
        const val OUT_MODE = "out.mode"
        const val OUT_FILE_NAME = "out.fileName"
        const val OUT_OBJECT_PATH = "out.objectPath"
        const val OUT_PUBLIC_URL = "out.publicUrl"
        const val OUT_ETAG = "out.etag"
        const val OUT_REQUEST_ID = "out.requestId"
        const val OUT_BYTES_RAW = "out.bytesRaw"
        const val OUT_BYTES_GZ = "out.bytesGz"

        const val ERROR_MESSAGE = "error"

        private const val DEFAULT_MAX_BYTES_HINT = 20_000_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000
        private const val DEFAULT_READ_TIMEOUT_MS = 120_000

        /**
         * Sanitize a unique work name so WorkManager never sees illegal characters.
         */
        private fun sanitizeWorkName(value: String): String {
            return value
                .trim()
                .replace(Regex("""[^\w\-.]+"""), "_")
                .take(120)
        }

        fun enqueueExistingPayload(
            context: Context,
            cfg: SupabaseUploader.SupabaseConfig,
            file: File,
            remoteDir: String = "regular",
            contentType: String = "application/octet-stream",
            upsert: Boolean = false,
            userJwt: String? = null,
            maxBytesHint: Long = cfg.maxRawBytesHint,
            addDateSubdir: Boolean = false,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
            readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
        ) {
            val name = file.name
            val safeName = sanitizeWorkName("$remoteDir-$name")

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SupabaseUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_FILE,
                            KEY_URL to cfg.supabaseUrl,
                            KEY_ANON_KEY to cfg.anonKey,
                            KEY_BUCKET to cfg.bucket,
                            KEY_PATH_PREFIX to cfg.pathPrefix,
                            KEY_FILE_PATH to file.absolutePath,
                            KEY_FILE_NAME to name,
                            KEY_REMOTE_DIR to remoteDir,
                            KEY_CONTENT_TYPE to contentType,
                            KEY_UPSERT to upsert,
                            KEY_USER_JWT to (userJwt ?: ""),
                            KEY_MAX_BYTES_HINT to maxBytesHint,
                            KEY_FILE_ADD_DATE to addDateSubdir,
                            KEY_CONNECT_TIMEOUT_MS to connectTimeoutMs,
                            KEY_READ_TIMEOUT_MS to readTimeoutMs
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
                    .addTag("$TAG:file:$safeName")
                    .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("sb_upload_$safeName", ExistingWorkPolicy.KEEP, req)
        }

        fun enqueueLogcatUpload(
            context: Context,
            cfg: SupabaseUploader.SupabaseConfig,
            remoteDir: String = "logcat",
            addDateSubdir: Boolean = true,
            includeDeviceHeader: Boolean = true,
            includeCrashBuffer: Boolean = true,
            maxUncompressedBytes: Int = 850_000,
            userJwt: String? = null,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
            readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
        ) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val uniqueName = "sb_upload_logcat_$stamp"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SupabaseUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_LOGCAT,
                            KEY_URL to cfg.supabaseUrl,
                            KEY_ANON_KEY to cfg.anonKey,
                            KEY_BUCKET to cfg.bucket,
                            KEY_PATH_PREFIX to cfg.pathPrefix,
                            KEY_REMOTE_DIR to remoteDir,
                            KEY_LOG_ADD_DATE to addDateSubdir,
                            KEY_LOG_INCLUDE_HEADER to includeDeviceHeader,
                            KEY_LOG_INCLUDE_CRASH to includeCrashBuffer,
                            KEY_LOG_MAX_UNCOMPRESSED to maxUncompressedBytes,
                            KEY_USER_JWT to (userJwt ?: ""),
                            KEY_CONNECT_TIMEOUT_MS to connectTimeoutMs,
                            KEY_READ_TIMEOUT_MS to readTimeoutMs
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

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
        }
    }
}
