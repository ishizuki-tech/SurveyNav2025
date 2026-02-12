/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseLogUploader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SupabaseLogUploader"

object SupabaseLogUploader {

    data class LogUploadResult(
        val objectPath: String,
        val publicUrl: String?,
        val etag: String?,
        val requestId: String?,
        val bytesRaw: Int,
        val bytesGz: Int
    )

    suspend fun collectAndUploadLogcat(
        context: Context,
        cfg: SupabaseUploader.SupabaseConfig,
        remoteDir: String = "logcat",
        addDateSubdir: Boolean = true,
        includeDeviceHeader: Boolean = true,
        maxUncompressedBytes: Int = 850_000,
        includeCrashBuffer: Boolean = true,
        tokenOverride: String? = null,
        onProgress: (Int) -> Unit = {}
    ): LogUploadResult = withContext(Dispatchers.IO) {

        onProgress(0)

        val pid = Process.myPid()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dateDir = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val remoteName = "logcat_${stamp}_pid${pid}.log.gz"
        val objectPath = buildRemotePath(
            prefix = cfg.pathPrefix,
            remoteDir = remoteDir,
            addDateSubdir = addDateSubdir,
            dateDir = dateDir,
            fileName = remoteName
        )

        val header = if (includeDeviceHeader) buildHeader(context, pid) else ""

        onProgress(5)

        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val budgetTotal = maxUncompressedBytes.coerceAtLeast(50_000)

        val budgetMain = if (includeCrashBuffer) (budgetTotal * 3) / 4 else budgetTotal
        val budgetCrash = (budgetTotal - budgetMain).coerceAtLeast(10_000)

        val mainLog = collectLogcatForPidTail(pid = pid, buffer = null, maxBytes = budgetMain)
        val crashLog = if (includeCrashBuffer) {
            collectLogcatForPidTail(pid = pid, buffer = "crash", maxBytes = budgetCrash)
        } else {
            ""
        }

        onProgress(15)

        val combinedText = buildString {
            append(header)
            append(mainLog)
            if (includeCrashBuffer && crashLog.isNotBlank()) {
                appendLine()
                appendLine("=== crash buffer ===")
                append(crashLog)
            }
        }

        val combinedBytes = combinedText.toByteArray(Charsets.UTF_8)
        val trimmed = trimToTail(combinedBytes, budgetTotal)

        onProgress(20)

        val maxGzBytes = min(cfg.maxRawBytesHint, 900_000L).toInt().coerceAtLeast(50_000)
        val gz = gzipAndFitToMaxBytes(trimmed, maxGzBytes)

        onProgress(35)

        val res = SupabaseUploader.uploadBytes(
            cfg = cfg,
            objectPath = objectPath,
            bytes = gz,
            contentType = "application/gzip",
            upsert = false,
            tokenOverride = tokenOverride,
            onProgress = { p ->
                val mapped = 35 + ((p.coerceIn(0, 100) / 100.0) * 65.0).toInt()
                onProgress(mapped.coerceIn(35, 100))
            }
        )

        onProgress(100)

        LogUploadResult(
            objectPath = res.objectPath,
            publicUrl = res.publicUrl,
            etag = res.etag,
            requestId = res.requestId,
            bytesRaw = trimmed.size,
            bytesGz = gz.size
        )
    }

    private fun collectLogcatForPidTail(pid: Int, buffer: String?, maxBytes: Int): String {
        val base = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            base.add("-b")
            base.add(buffer)
        }
        base.add("--pid=$pid")

        val firstTry = runCatching { runProcessTail(base.toTypedArray(), maxBytes) }.getOrElse { "" }
        if (firstTry.isNotBlank() && !looksLikePidUnsupported(firstTry)) return firstTry

        val fallback = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            fallback.add("-b")
            fallback.add(buffer)
        }

        val out = runCatching { runProcessTail(fallback.toTypedArray(), maxBytes) }.getOrElse { t ->
            Log.w(TAG, "collectLogcatForPidTail failed: ${t.message}", t)
            "collectLogcatForPidTail failed: ${t.message}\n"
        }

        return buildString {
            appendLine("=== WARNING ===")
            appendLine("PID-filtered logcat is not available on this device/runtime.")
            appendLine("Fallback logcat dump may include other processes. Output is tail-captured.")
            appendLine("================")
            appendLine()
            append(out)
        }
    }

    private fun runProcessTail(cmd: Array<String>, maxBytes: Int): String {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()

        val tail = TailBuffer(maxBytes.coerceAtLeast(10_000))
        proc.inputStream.use { stream ->
            pumpToTail(stream, tail)
        }

        runCatching { proc.destroy() }
        return String(tail.toByteArray(), Charsets.UTF_8)
    }

    private fun pumpToTail(stream: InputStream, tail: TailBuffer) {
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            tail.append(buf, 0, n)
        }
    }

    private fun looksLikePidUnsupported(output: String): Boolean {
        val s = output.lowercase(Locale.US)
        return s.contains("unknown option") && s.contains("pid")
    }

    private fun buildHeader(context: Context, pid: Int): String {
        val pkg = context.packageName
        val pm = context.packageManager
        val pkgInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()

        val versionName = pkgInfo?.versionName ?: "unknown"
        val versionCode = runCatching {
            if (pkgInfo != null) PackageInfoCompat.getLongVersionCode(pkgInfo) else -1L
        }.getOrDefault(-1L)

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val utc = sdf.format(Date())

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

    private fun trimToTail(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        val start = bytes.size - maxBytes
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun gzipAndFitToMaxBytes(input: ByteArray, maxGzBytes: Int): ByteArray {
        var current = input

        repeat(4) { attempt ->
            val gz = gzip(current)
            if (gz.size <= maxGzBytes) return gz

            val nextMax = (current.size * 0.75).toInt().coerceAtLeast(50_000)
            val trimmed = trimToTail(current, nextMax)

            Log.w(TAG, "gzip too large (attempt=$attempt gz=${gz.size} > maxGzBytes=$maxGzBytes). trimming to $nextMax")
            current = trimmed
        }

        return gzip(current)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun buildRemotePath(
        prefix: String,
        remoteDir: String,
        addDateSubdir: Boolean,
        dateDir: String,
        fileName: String
    ): String {
        val parts = mutableListOf<String>()
        prefix.trim('/').takeIf { it.isNotBlank() }?.let(parts::add)
        remoteDir.trim('/').takeIf { it.isNotBlank() }?.let(parts::add)
        if (addDateSubdir) parts.add(dateDir)
        parts.add(fileName.trim('/'))
        return parts.joinToString("/")
    }

    private class TailBuffer(private val capacity: Int) {
        private val buf = ByteArray(capacity)
        private var pos = 0
        private var size = 0

        fun append(src: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                val spaceToEnd = capacity - pos
                val n = min(spaceToEnd, l)
                System.arraycopy(src, o, buf, pos, n)
                pos = (pos + n) % capacity
                size = min(capacity, size + n)
                o += n
                l -= n
            }
        }

        fun toByteArray(): ByteArray {
            if (size == 0) return ByteArray(0)
            if (size < capacity) return buf.copyOfRange(0, size)

            val out = ByteArray(capacity)
            val tailLen = capacity - pos
            System.arraycopy(buf, pos, out, 0, tailLen)
            System.arraycopy(buf, 0, out, tailLen, pos)
            return out
        }
    }
}
