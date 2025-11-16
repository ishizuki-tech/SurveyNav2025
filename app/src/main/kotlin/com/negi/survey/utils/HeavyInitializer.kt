/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: HeavyInitializer.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  One-shot, single-flight initializer for large model files or heavy assets.
 *  Handles download, resume, integrity check, and atomic replacement with
 *  coroutine cancellation propagation and friendly error reporting.
 * =====================================================================
 */

package com.negi.survey.utils

import android.content.Context
import android.util.Log
import com.negi.survey.BuildConfig
import com.negi.survey.net.HttpUrlFileDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Manages single-flight initialization for heavy assets (e.g., model download).
 *
 * Key features:
 * - Ensures only one concurrent initialization via [AtomicReference].
 * - Writes to a temporary file, then atomically replaces the final file.
 * - Fully cancellation-safe (propagates caller coroutine cancellation).
 * - Validates free space and remote content length before downloading.
 */
object HeavyInitializer {

    private const val TAG = "HeavyInitializer"
    private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L // 64 MiB

    private val inFlight = AtomicReference<CompletableDeferred<Result<File>>?>(null)
    @Volatile
    private var runningJob: Job? = null

    /** Checks if a valid file already exists and matches remote size. */
    fun isAlreadyComplete(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String
    ): Boolean {
        val dst = File(context.filesDir, fileName)
        if (!dst.exists() || dst.length() <= 0L) return false
        val remoteLen = runCatching { headContentLengthForVerify(modelUrl, hfToken) }.getOrNull()
        return remoteLen != null && remoteLen == dst.length()
    }

    // --- HEAD probe for strict content-length validation ---
    private fun headContentLengthForVerify(srcUrl: String, hfToken: String?): Long? {
        var current = srcUrl
        repeat(10) {
            val u = URL(current)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "SurveyNav/1.0 (Android)")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if ((u.host == "huggingface.co" || u.host.endsWith(".huggingface.co")) && !hfToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(u, loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val len = conn.getHeaderFieldLong("Content-Length", -1L)
                return len.takeIf { it >= 0L }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /**
     * Ensures that the model or asset is initialized, downloading it if needed.
     *
     * @param forceFresh If true, deletes any cached file before downloading.
     */
    suspend fun ensureInitialized(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String,
        timeoutMs: Long,
        forceFresh: Boolean,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ): Result<File> {
        inFlight.get()?.let { return it.await() }
        val deferred = CompletableDeferred<Result<File>>()
        if (!inFlight.compareAndSet(null, deferred)) {
            return inFlight.get()!!.await()
        }

        val callerJob = currentCoroutineContext()[Job]
        runningJob = callerJob
        val token = hfToken?.takeIf { it.isNotBlank() }

        try {
            val dir = context.filesDir
            val finalFile = File(dir, fileName)
            val tmpFile = File(dir, "$fileName.tmp")

            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { finalFile.delete() }
            }

            val downloader = HttpUrlFileDownloader(hfToken = token, debugLogs = BuildConfig.DEBUG)
            val remoteLen = runCatching { headContentLength(modelUrl, token) }.getOrNull()

            // Skip if already valid
            if (!forceFresh && remoteLen != null && finalFile.exists() && finalFile.length() == remoteLen) {
                onProgress(finalFile.length(), finalFile.length())
                deferred.complete(Result.success(finalFile))
                return deferred.await()
            }

            // Free-space validation
            if (remoteLen != null) {
                val existing = if (!forceFresh && finalFile.exists()) finalFile.length() else 0L
                val needed = max(0L, remoteLen - existing) + FREE_SPACE_MARGIN_BYTES
                if (dir.usableSpace < needed) {
                    deferred.complete(Result.failure(IOException("Not enough free space")))
                    return deferred.await()
                }
            }

            withTimeout(timeoutMs) {
                val progressBridge: (Long, Long?) -> Unit = { cur, total ->
                    if (callerJob?.isActive != true) throw CancellationException("canceled by caller")
                    onProgress(cur, total)
                }

                runCatching { tmpFile.delete() }
                tmpFile.parentFile?.mkdirs()
                tmpFile.createNewFile()

                try {
                    downloader.downloadToFile(modelUrl, tmpFile, progressBridge)
                } catch (e: IOException) {
                    val msg = e.message?.lowercase().orEmpty()
                    val rangeIssue = "416" in msg || "range" in msg
                    if (!forceFresh && rangeIssue && callerJob?.isActive == true) {
                        tmpFile.delete()
                        tmpFile.createNewFile()
                        downloader.downloadToFile(modelUrl, tmpFile, progressBridge)
                    } else throw e
                }
            }

            replaceFinal(tmpFile, finalFile)
            deferred.complete(Result.success(finalFile))

        } catch (ce: CancellationException) {
            File(context.filesDir, "$fileName.tmp").delete()
            deferred.complete(Result.failure(IOException("Canceled")))
        } catch (te: TimeoutCancellationException) {
            deferred.complete(Result.failure(IOException("Timeout ($timeoutMs ms)")))
        } catch (t: Throwable) {
            val msg = userFriendlyMessage(t)
            Log.w(TAG, "Initialization error: $msg", t)
            File(context.filesDir, "$fileName.tmp").delete()
            deferred.complete(Result.failure(IOException(msg, t)))
        } finally {
            if (inFlight.get() === deferred && deferred.isCompleted) {
                inFlight.set(null)
            }
            runningJob = null
        }

        return deferred.await()
    }

    /** Cancels any running initialization. */
    suspend fun cancel() {
        runningJob?.cancel(CancellationException("canceled by user"))
        Log.w(TAG, "Initialization canceled.")
    }

    /** Clears all in-flight and debug state. */
    fun resetForDebug() {
        inFlight.getAndSet(null)?.cancel(CancellationException("resetForDebug"))
        runningJob?.cancel(CancellationException("resetForDebug"))
        runningJob = null
        Log.w(TAG, "resetForDebug(): cleared in-flight state")
    }

    // ---- internal utilities ----
    private fun headContentLength(srcUrl: String, hfToken: String?): Long? =
        headContentLengthForVerify(srcUrl, hfToken)

    private fun userFriendlyMessage(t: Throwable): String {
        val raw = t.message ?: t::class.java.simpleName
        val s = raw.lowercase()
        return when {
            "unauthorized" in s || "401" in s -> "Authorization failed (HF token?)"
            "forbidden" in s || "403" in s -> "Access denied (token/permissions?)"
            "timeout" in s -> "Network timeout"
            "space" in s -> "Not enough free space"
            "content-range" in s || "416" in s -> "Resume failed (server refused range)"
            "unknown host" in s || "dns" in s -> "Unknown host (check connectivity)"
            else -> raw
        }
    }

    /** Approximates atomic replace (same directory, no cross-device rename). */
    private fun replaceFinal(tmp: File, dst: File): Boolean {
        if (dst.exists() && !dst.delete()) return false
        return tmp.renameTo(dst)
    }

    /** Returns true if a download is currently active. */
    @JvmStatic
    fun isInFlight(): Boolean = inFlight.get() != null
}
