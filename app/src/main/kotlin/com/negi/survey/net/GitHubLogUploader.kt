/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubLogUploader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Collect logcat for current PID, gzip-compress it, and upload to GitHub
 *  using GitHubUploader (Contents API).
 *
 *  Design notes:
 *   - Reuses GitHubUploader to avoid duplicate HTTP logic.
 *   - Progress is mapped to 0..100:
 *       0..20  = collect/build
 *      20..35  = gzip (and size guard)
 *      35..100 = upload progress (GitHubUploader progress scaled)
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process as AndroidProcess
import android.util.Log
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "GitHubLogUploader"

/**
 * Collects app logcat output (current PID) and uploads it to GitHub via [GitHubUploader].
 *
 * Notes:
 * - Android restricts log access; PID-filtered collection is best-effort.
 * - Contents API has practical size constraints; we trim tail and gzip.
 */
object GitHubLogUploader {

    /**
     * Result payload for log upload.
     */
    data class LogUploadResult(
        val remotePath: String,
        val fileUrl: String?,
        val commitSha: String?,
        val bytesRaw: Int,
        val bytesGz: Int,
    )

    /**
     * Collect and upload a logcat snapshot.
     *
     * @param context Android context.
     * @param cfg GitHub config (owner/repo/token/branch).
     * @param remoteDir Repo directory (e.g., "diagnostics/logs").
     * @param addDateSubdir If true, inserts yyyy-MM-dd as a folder.
     * @param includeDeviceHeader If true, prepends device/app header.
     * @param maxUncompressedBytes Tail bytes (overall, header preserved).
     * @param includeCrashBuffer If true, tries to append crash buffer too.
     * @param logcatTimeoutMs Timeout for each logcat process execution.
     * @param onProgress Progress callback (0..100).
     */
    suspend fun collectAndUploadLogcat(
        context: Context,
        cfg: GitHubUploader.GitHubConfig,
        remoteDir: String = "diagnostics/logs",
        addDateSubdir: Boolean = true,
        includeDeviceHeader: Boolean = true,
        maxUncompressedBytes: Int = 850_000,
        includeCrashBuffer: Boolean = true,
        logcatTimeoutMs: Long = 8_000L,
        onProgress: (Int) -> Unit = {},
    ): LogUploadResult = withContext(Dispatchers.IO) {

        onProgress(0)

        val pid = AndroidProcess.myPid()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dateDir = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val remoteName = "logcat_${stamp}_pid${pid}.log.gz"
        val remotePath = buildRemotePath(
            remoteDir = remoteDir,
            addDateSubdir = addDateSubdir,
            dateDir = dateDir,
            fileName = remoteName,
        )

        val headerBytes = if (includeDeviceHeader) {
            buildHeader(context, pid).toByteArray(Charsets.UTF_8)
        } else {
            ByteArray(0)
        }

        onProgress(5)

        // Collect logs as tail-bytes to avoid allocating huge strings in memory.
        val mainLogBytes = collectLogcatTailBytesForPid(
            pid = pid,
            buffer = null,
            maxTailBytes = maxUncompressedBytes,
            timeoutMs = logcatTimeoutMs,
        )

        val crashLogBytes = if (includeCrashBuffer) {
            collectLogcatTailBytesForPid(
                pid = pid,
                buffer = "crash",
                maxTailBytes = maxUncompressedBytes,
                timeoutMs = logcatTimeoutMs,
            )
        } else {
            ByteArray(0)
        }

        onProgress(15)

        val combinedRest = ByteArrayOutputStream().use { bos ->
            bos.write(mainLogBytes)

            if (includeCrashBuffer && !isBlankUtf8(crashLogBytes)) {
                bos.write("\n".toByteArray(Charsets.UTF_8))
                bos.write("=== crash buffer ===\n".toByteArray(Charsets.UTF_8))
                bos.write(crashLogBytes)
            }

            bos.toByteArray()
        }

        // Preserve header always; trim only the rest (tail) to fit maxUncompressedBytes.
        val trimmed = trimToTailPreservingPrefix(
            prefix = headerBytes,
            rest = combinedRest,
            maxTotalBytes = maxUncompressedBytes,
        )

        onProgress(20)

        val gz = gzipAndFitToContentsLimit(trimmed)

        onProgress(35)

        val uploadResult = GitHubUploader.uploadFile(
            owner = cfg.owner,
            repo = cfg.repo,
            branch = cfg.branch,
            path = remotePath,
            token = cfg.token,
            bytes = gz,
            message = "Upload diagnostics log ($stamp)",
            onProgress = { p ->
                val mapped = 35 + ((p.coerceIn(0, 100) / 100.0) * 65.0).toInt()
                onProgress(mapped.coerceIn(35, 100))
            }
        )

        onProgress(100)

        Log.d(
            TAG,
            "Uploaded logcat: path=$remotePath raw=${trimmed.size}B gz=${gz.size}B sha=${uploadResult.commitSha}"
        )

        LogUploadResult(
            remotePath = remotePath,
            fileUrl = uploadResult.fileUrl,
            commitSha = uploadResult.commitSha,
            bytesRaw = trimmed.size,
            bytesGz = gz.size,
        )
    }

    /**
     * Collect logcat output for the given PID, returning ONLY the tail bytes (UTF-8).
     *
     * Uses:
     * - logcat -d --pid=<pid> -v threadtime
     * - optionally: -b <buffer>
     *
     * If --pid is not supported on the device, this falls back to a non-PID dump.
     * This may include other processes. Output is still tail-trimmed.
     */
    private suspend fun collectLogcatTailBytesForPid(
        pid: Int,
        buffer: String?,
        maxTailBytes: Int,
        timeoutMs: Long,
    ): ByteArray {

        val base = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            base.add("-b")
            base.add(buffer)
        }
        base.add("--pid=$pid")

        val firstTry = runCatching {
            runProcessTailBytes(
                cmd = base,
                maxTailBytes = maxTailBytes,
                timeoutMs = timeoutMs,
            )
        }.getOrElse { t ->
            Log.w(TAG, "collectLogcatTailBytesForPid (pid) failed: ${t.message}", t)
            "collectLogcatTailBytesForPid(pid) failed: ${t.message}\n".toByteArray(Charsets.UTF_8)
        }

        // Only fallback when we detect "--pid" is truly unsupported.
        val firstTryText = runCatching { firstTry.toString(Charsets.UTF_8) }.getOrElse { "" }
        if (!looksLikePidUnsupported(firstTryText)) {
            return firstTry
        }

        val fallback = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            fallback.add("-b")
            fallback.add(buffer)
        }

        val out = runCatching {
            runProcessTailBytes(
                cmd = fallback,
                maxTailBytes = maxTailBytes,
                timeoutMs = timeoutMs,
            )
        }.getOrElse { t ->
            Log.w(TAG, "collectLogcatTailBytesForPid fallback failed: ${t.message}", t)
            ("collectLogcatTailBytesForPid fallback failed: ${t.message}\n")
                .toByteArray(Charsets.UTF_8)
        }

        val warning = buildString {
            appendLine("=== WARNING ===")
            appendLine("PID-filtered logcat is not available on this device/runtime.")
            appendLine("Fallback logcat dump may include other processes. Output is tail-trimmed.")
            appendLine("================")
            appendLine()
        }.toByteArray(Charsets.UTF_8)

        return warning + out
    }

    /**
     * Run a process and return UTF-8 output tail-bytes only (to avoid huge allocations).
     *
     * Implementation detail:
     * - Reads stdout+stderr (redirectErrorStream=true)
     * - Keeps only the last [maxTailBytes] bytes in memory.
     * - Applies a coroutine timeout; on timeout, destroys the process and returns captured tail so far.
     */
    private suspend fun runProcessTailBytes(
        cmd: List<String>,
        maxTailBytes: Int,
        timeoutMs: Long,
    ): ByteArray {

        val tail = TailBuffer(maxTailBytes.coerceAtLeast(1))

        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        val completed = runCatching {
            withTimeoutOrNull(timeoutMs) {
                proc.inputStream.use { ins ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        tail.append(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrElse {
            Log.w(TAG, "runProcessTailBytes read failed: ${it.message}", it)
            false
        }

        if (!completed) {
            Log.w(TAG, "runProcessTailBytes timeout after ${timeoutMs}ms: ${cmd.joinToString(" ")}")
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
        } else {
            runCatching { proc.waitFor(200, TimeUnit.MILLISECONDS) }
        }

        runCatching { proc.destroy() }
        return tail.toByteArray()
    }

    /**
     * Heuristic: detect "unknown option" patterns for --pid.
     */
    private fun looksLikePidUnsupported(output: String): Boolean {
        val s = output.lowercase(Locale.US)
        return (s.contains("unknown option") || s.contains("invalid option") || s.contains("unrecognized option")) &&
                s.contains("pid")
    }

    /**
     * Build a short header to help debugging.
     */
    private fun buildHeader(context: Context, pid: Int): String {
        val pkg = context.packageName
        val pm = context.packageManager

        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        }.getOrNull()

        val versionName = pkgInfo?.versionName ?: "unknown"

        val versionCode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo?.longVersionCode ?: -1L
            } else {
                @Suppress("DEPRECATION")
                pkgInfo?.versionCode?.toLong() ?: -1L
            }
        }.getOrElse { -1L }

        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        return buildString {
            appendLine("=== Diagnostics Header ===")
            appendLine("time_utc=$utc")
            appendLine("package=$pkg")
            appendLine("versionName=$versionName")
            appendLine("versionCode=$versionCode")
            appendLine("pid=$pid")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("==========================")
            appendLine()
        }
    }

    /**
     * Returns true if the UTF-8 content is blank-ish (only whitespace/newlines).
     */
    private fun isBlankUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        for (b in bytes) {
            when (b.toInt().toChar()) {
                ' ', '\n', '\r', '\t' -> Unit
                else -> return false
            }
        }
        return true
    }

    /**
     * Preserve [prefix] exactly, and tail-trim [rest] so (prefix + restTail) <= [maxTotalBytes].
     */
    private fun trimToTailPreservingPrefix(
        prefix: ByteArray,
        rest: ByteArray,
        maxTotalBytes: Int,
    ): ByteArray {
        if (maxTotalBytes <= 0) return ByteArray(0)
        if (prefix.isEmpty() && rest.size <= maxTotalBytes) return rest
        if (prefix.size >= maxTotalBytes) return prefix.copyOfRange(0, maxTotalBytes)

        val remaining = maxTotalBytes - prefix.size
        val restTail = if (rest.size <= remaining) {
            rest
        } else {
            rest.copyOfRange(rest.size - remaining, rest.size)
        }

        return prefix + restTail
    }

    /**
     * Gzip compress and ensure the gzip size is under GitHub Contents API raw-byte guard.
     *
     * GitHubUploader uses a raw bytes guard (~900k). Logs compress well, but we still
     * handle worst cases by trimming further and recompressing a few times.
     */
    private fun gzipAndFitToContentsLimit(input: ByteArray): ByteArray {
        val hardLimit = 900_000 // must match your GitHubUploader guard

        var current = input
        repeat(4) { attempt ->
            val gz = gzip(current)
            if (gz.size <= hardLimit) return gz

            val nextMax = (current.size * 0.75).toInt().coerceAtLeast(50_000)
            val trimmed = if (current.size <= nextMax) {
                current
            } else {
                current.copyOfRange(current.size - nextMax, current.size)
            }

            Log.w(
                TAG,
                "gzip too large (attempt=$attempt gz=${gz.size}). Trimming tail to $nextMax bytes and retrying."
            )
            current = trimmed
        }

        return gzip(current)
    }

    /**
     * Gzip compress.
     */
    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    /**
     * Build remote path.
     */
    private fun buildRemotePath(
        remoteDir: String,
        addDateSubdir: Boolean,
        dateDir: String,
        fileName: String,
    ): String {
        val parts = mutableListOf<String>()
        remoteDir.trim('/').takeIf { it.isNotBlank() }?.let(parts::add)
        if (addDateSubdir) parts.add(dateDir)
        parts.add(fileName.trim('/'))
        return parts.joinToString("/")
    }

    /**
     * A small ring buffer that keeps only the last [capacity] bytes appended.
     */
    private class TailBuffer(private val capacity: Int) {
        private val buf = ByteArray(capacity)
        private var size = 0
        private var start = 0

        fun append(src: ByteArray, off: Int, len: Int) {
            if (capacity <= 0 || len <= 0) return

            // If incoming chunk is larger than capacity, keep only its tail.
            if (len >= capacity) {
                val from = off + (len - capacity)
                System.arraycopy(src, from, buf, 0, capacity)
                start = 0
                size = capacity
                return
            }

            // Ensure we have space by dropping oldest bytes if needed.
            val free = capacity - size
            if (len > free) {
                val drop = len - free
                start = (start + drop) % capacity
                size -= drop
            }

            // Write into ring.
            val writePos = (start + size) % capacity
            val first = minOf(len, capacity - writePos)
            System.arraycopy(src, off, buf, writePos, first)
            val remaining = len - first
            if (remaining > 0) {
                System.arraycopy(src, off + first, buf, 0, remaining)
            }
            size += len
        }

        fun toByteArray(): ByteArray {
            if (size <= 0) return ByteArray(0)
            val out = ByteArray(size)
            val first = minOf(size, capacity - start)
            System.arraycopy(buf, start, out, 0, first)
            val remaining = size - first
            if (remaining > 0) {
                System.arraycopy(buf, 0, out, first, remaining)
            }
            return out
        }
    }
}
