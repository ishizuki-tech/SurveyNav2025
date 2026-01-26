/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: HttpUrlFileDownloader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  A robust coroutine-based HTTP file downloader built upon HttpURLConnection.
 *  Provides resumable, integrity-verified transfers with exponential backoff,
 *  progress tracking, and Hugging Face token support.
 *
 *  Compatibility hardening:
 *   • Avoid getHeaderFieldLong (use string parse)
 *   • Avoid ByteArray.decodeToString (use String(bytes, charset))
 *   • Avoid coroutineContext.ensureActive extension (use Job.isActive)
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.pow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-safe downloader for large, resumable HTTP transfers.
 *
 * @property hfToken Optional Hugging Face token ("hf_xxx"), applied only for `huggingface.co` hosts.
 * @property debugLogs Enables verbose diagnostic logs.
 * @property fsyncOnFinish If true, calls FileDescriptor.sync() after writing a file.
 */
class HttpUrlFileDownloader(
    private val hfToken: String? = null,
    private val debugLogs: Boolean = true,
    private val fsyncOnFinish: Boolean = true
) {
    private val tag = "HttpUrlFileDl"

    /**
     * Downloads a file from the given [url] into [dst], resuming if partially complete.
     *
     * @throws IOException When the operation fails permanently.
     */
    suspend fun downloadToFile(
        url: String,
        dst: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        expectedSha256: String? = null,
        connectTimeoutMs: Int = 20_000,
        firstByteTimeoutMs: Int = 30_000,
        stallTimeoutMs: Int = 90_000,
        ioBufferBytes: Int = 1 * 1024 * 1024,
        maxRetries: Int = 3,
        progressMinIntervalMs: Long = 200L,
        progressMinDeltaBytes: Long = 256L * 1024L
    ) = withContext(Dispatchers.IO) {

        val parent = dst.absoluteFile.parentFile
            ?: throw IOException("Invalid destination: ${dst.absolutePath}")

        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create destination directory: ${parent.absolutePath}")
        }

        val part = File(parent, dst.name + ".part")
        val meta = MetaFile(part)

        preflightSanityCleanup(dst = dst, part = part, meta = meta)

        // Fast path: if total is known and dst already matches size + optional hash, skip.
        runCatching { probeHeadOrGet(url, connectTimeoutMs, firstByteTimeoutMs).total }.getOrNull()
            ?.let { total ->
                val okSize = dst.exists() && dst.length() == total
                val okHash = expectedSha256 == null || sha256(dst).equals(expectedSha256, true)
                if (okSize && okHash) {
                    onProgress(dst.length(), dst.length())
                    logd("Already complete, skipping download.")
                    return@withContext
                }
            }

        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < maxRetries) {
            try {
                ensureActiveCompat(coroutineContext)

                val probe = probeHeadOrGet(url, connectTimeoutMs, firstByteTimeoutMs)
                val total = probe.total
                var finalUrl = probe.finalUrl

                if (total == null) {
                    logw("Missing Content-Length; falling back to non-resumable download.")
                    runCatching { if (part.exists()) part.delete() }
                    meta.delete()

                    downloadNonResumable(
                        initialUrl = finalUrl,
                        dst = dst,
                        part = part,
                        expectedSha256 = expectedSha256,
                        onProgress = onProgress,
                        connectTimeoutMs = connectTimeoutMs,
                        readTimeoutMs = stallTimeoutMs,
                        ioBufferBytes = ioBufferBytes,
                        progressMinIntervalMs = progressMinIntervalMs,
                        progressMinDeltaBytes = progressMinDeltaBytes
                    )
                    logd("Saved ${dst.name} (${dst.length()} bytes) [non-resumable]")
                    return@withContext
                }

                // If meta says different total, reset partial.
                meta.read()?.let { m ->
                    val prevTotal = m.total
                    if (prevTotal != null && prevTotal != total) {
                        logw("Meta total differs from remote total; resetting partial state.")
                        runCatching { if (part.exists()) part.delete() }
                        meta.delete()
                    }
                }

                // If part is larger than total, local state is corrupt; reset.
                if (part.exists() && part.length() > total) {
                    logw("Local .part larger than remote total; resetting partial state.")
                    runCatching { part.delete() }
                    meta.delete()
                }

                val already = if (part.exists()) part.length() else 0L

                // If part already complete, promote locally with validation.
                if (already == total) {
                    val done = promotePartToFinalAndValidate(
                        dst = dst,
                        part = part,
                        meta = meta,
                        total = total,
                        expectedSha256 = expectedSha256,
                        onProgress = onProgress
                    )
                    if (done) return@withContext
                }

                if (!probe.acceptRanges && already > 0L) {
                    logw("Accept-Ranges missing, but partial exists; will attempt resume anyway.")
                }

                checkFreeSpaceOrThrow(parent, max(0L, (total - already)) + 50L * 1024 * 1024)

                // Persist validators atomically for If-Range.
                meta.writeAtomic(Meta(probe.etag, probe.lastModified, total))

                var resumeFrom = already.coerceIn(0, total)
                var triesOnThisStream = 0
                var redirectHops = 0

                // Progress throttling.
                var lastProgressAtMs = 0L
                var lastProgressBytes = -1L
                fun emitProgress(downloaded: Long, force: Boolean = false) {
                    val now = System.currentTimeMillis()
                    val timeOk = (now - lastProgressAtMs) >= progressMinIntervalMs
                    val byteOk = (lastProgressBytes < 0L) || (downloaded - lastProgressBytes) >= progressMinDeltaBytes
                    if (force || timeOk || byteOk) {
                        lastProgressAtMs = now
                        lastProgressBytes = downloaded
                        onProgress(downloaded, total)
                    }
                }

                STREAM@ while (true) {
                    ensureActiveCompat(coroutineContext)

                    if (triesOnThisStream > 0) {
                        val refreshed = probeHeadOrGet(url, connectTimeoutMs, firstByteTimeoutMs)
                        if (refreshed.total != null && refreshed.total != total) {
                            throw IOException("Remote size changed (old=$total new=${refreshed.total})")
                        }
                        finalUrl = refreshed.finalUrl
                    }

                    val conn = openConnNoRedirect(finalUrl, "GET", connectTimeoutMs, stallTimeoutMs)
                    try {
                        setCommonHeaders(conn, finalUrl)

                        if (resumeFrom > 0) {
                            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                            meta.read()?.let { m ->
                                val ifRange = m.etag ?: m.lastModified
                                if (ifRange != null) conn.setRequestProperty("If-Range", ifRange)
                            }
                        }

                        conn.connect()
                        val code = conn.responseCode

                        // Manual redirect handling to preserve headers.
                        if (code in 300..399) {
                            val loc = conn.getHeaderField("Location")
                                ?: throw IOException("Redirect without Location.")
                            finalUrl = URL(URL(finalUrl), loc).toString()
                            if (++redirectHops > MAX_REDIRECTS) throw IOException("Too many redirects.")
                            continue@STREAM
                        }

                        if (code == 429 || code == 503) {
                            throw HttpExceptionWithRetryAfter("GET HTTP $code", readRetryAfterMs(conn))
                        }

                        when (code) {
                            HttpURLConnection.HTTP_UNAUTHORIZED,
                            HttpURLConnection.HTTP_FORBIDDEN -> {
                                logw("GET $code: may need refreshed access. Retrying probe.")
                                triesOnThisStream++
                                resumeFrom = part.length().coerceIn(0, total)
                                continue@STREAM
                            }

                            HttpURLConnection.HTTP_OK -> if (resumeFrom > 0) {
                                logw("Server ignored Range, restarting from 0.")
                                runCatching { part.delete() }
                                meta.delete()
                                resumeFrom = 0L
                                if (++triesOnThisStream <= MAX_STREAM_RETRIES) continue@STREAM
                                throw IOException("Server ignored Range repeatedly.")
                            }

                            HttpURLConnection.HTTP_PARTIAL -> {
                                // Strict Content-Range validation for resumed 206.
                                if (resumeFrom > 0) {
                                    val cr = conn.getHeaderField("Content-Range")
                                    val parsed = cr?.let { parseContentRange(it) }
                                    if (parsed == null) {
                                        logw("Missing/invalid Content-Range for resumed 206; restarting.")
                                        runCatching { part.delete() }
                                        meta.delete()
                                        resumeFrom = 0L
                                        if (++triesOnThisStream <= MAX_STREAM_RETRIES) continue@STREAM
                                        throw IOException("Missing Content-Range for resumed 206 repeatedly.")
                                    } else {
                                        val (start, _, totalFromHeader) = parsed
                                        if (start != resumeFrom) {
                                            logw("Content-Range start mismatch (expected=$resumeFrom got=$start). Restarting.")
                                            runCatching { part.delete() }
                                            meta.delete()
                                            resumeFrom = 0L
                                            if (++triesOnThisStream <= MAX_STREAM_RETRIES) continue@STREAM
                                            throw IOException("Content-Range start mismatch repeatedly.")
                                        }
                                        if (totalFromHeader != null && totalFromHeader != total) {
                                            throw IOException("Content-Range total mismatch (expected=$total got=$totalFromHeader)")
                                        }
                                    }
                                }
                            }

                            416 -> {
                                val done = handleRangeNotSatisfiable(
                                    dst = dst,
                                    part = part,
                                    meta = meta,
                                    total = total,
                                    expectedSha256 = expectedSha256,
                                    onProgress = onProgress
                                )
                                if (done) return@withContext

                                resumeFrom = 0L
                                if (++triesOnThisStream <= MAX_STREAM_RETRIES) continue@STREAM
                                throw IOException("416 reconciliation failed repeatedly.")
                            }
                        }

                        if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                            val snippet = readErrorSnippet(conn)
                            throw IOException("GET HTTP $code${snippet?.let { ": $it" } ?: ""}")
                        }

                        val bufSize = ioBufferBytes.coerceIn(64 * 1024, 2 * 1024 * 1024)
                        var downloaded = resumeFrom

                        emitProgress(downloaded, force = true)

                        try {
                            conn.inputStream.use { input ->
                                FileOutputStream(part, resumeFrom > 0).use { fos ->
                                    BufferedOutputStream(fos, bufSize).use { out ->
                                        val buf = ByteArray(bufSize)
                                        while (true) {
                                            ensureActiveCompat(coroutineContext)
                                            val n = input.read(buf)
                                            if (n == -1) break
                                            out.write(buf, 0, n)
                                            downloaded += n
                                            emitProgress(downloaded)
                                        }
                                        out.flush()
                                    }
                                    if (fsyncOnFinish) runCatching { fos.fd.sync() }
                                }
                            }
                        } catch (t: SocketTimeoutException) {
                            logw("Stall timeout; resuming.")
                            resumeFrom = part.length().coerceIn(0, total)
                            if (++triesOnThisStream <= MAX_STREAM_RETRIES) continue@STREAM
                            throw t
                        } catch (t: IOException) {
                            if (isNoSpaceLeft(t)) throw t
                            logw("Stream error: ${t.message}")
                            resumeFrom = part.length().coerceIn(0, total)
                            if (++triesOnThisStream <= MAX_STREAM_RETRIES) continue@STREAM
                            throw t
                        }

                        // Promote .part → final.
                        safeReplaceFile(from = part, to = dst)
                        meta.delete()

                        if (dst.length() != total) {
                            throw IOException("Size mismatch: expected=$total got=${dst.length()}")
                        }
                        if (expectedSha256 != null) {
                            val got = sha256(dst)
                            if (!got.equals(expectedSha256, true)) {
                                runCatching { dst.delete() }
                                throw IOException("SHA-256 mismatch: expected=$expectedSha256 got=$got")
                            }
                        }

                        emitProgress(total, force = true)
                        logd("Saved ${dst.name} (${dst.length()} bytes)")
                        return@withContext
                    } finally {
                        runCatching { conn.disconnect() }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                lastError = t
                logw("Attempt ${attempt + 1} failed: ${t.javaClass.simpleName}: ${t.message}")

                if (t is IOException && isNoSpaceLeft(t)) throw t

                val retryAfterMs = (t as? HttpExceptionWithRetryAfter)?.retryAfterMs

                if (attempt < maxRetries - 1) {
                    val backoffMs = retryAfterMs ?: (500.0 * 2.0.pow(attempt.toDouble())).toLong()
                    logw("Retrying in ${backoffMs}ms …")
                    delay(backoffMs)
                }
            }
            attempt++
        }

        throw IOException(
            "Download failed after $maxRetries attempts: ${lastError?.message}",
            lastError
        )
    }

    // ----------------------------------------------------------
    // Probe (HEAD with manual redirects; fallback GET probe)
    // ----------------------------------------------------------

    private data class Probe(
        val total: Long?,
        val acceptRanges: Boolean,
        val etag: String?,
        val lastModified: String?,
        val finalUrl: String
    )

    private fun probeHeadOrGet(srcUrl: String, connectTimeoutMs: Int, readTimeoutMs: Int): Probe {
        return runCatching { headProbe(srcUrl, connectTimeoutMs, readTimeoutMs) }
            .getOrElse { e ->
                logw("HEAD probe failed (${e.javaClass.simpleName}: ${e.message}), trying GET range probe.")
                rangeProbeViaGet(srcUrl, connectTimeoutMs, readTimeoutMs)
            }
    }

    private fun headProbe(srcUrl: String, connectTimeoutMs: Int, readTimeoutMs: Int): Probe {
        var current = srcUrl
        var hops = 0

        while (true) {
            val conn = openConnNoRedirect(current, "HEAD", connectTimeoutMs, readTimeoutMs)
            try {
                setCommonHeaders(conn, current)
                conn.connect()

                val code = conn.responseCode

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location.")
                    current = URL(URL(current), loc).toString()
                    if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects.")
                    continue
                }

                if (code == 429 || code == 503) {
                    throw HttpExceptionWithRetryAfter("HEAD HTTP $code", readRetryAfterMs(conn))
                }

                if (code !in 200..299) {
                    throw IOException("HEAD HTTP $code${readErrorSnippet(conn)?.let { ": $it" } ?: ""}")
                }

                val total = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
                val acceptRanges = (conn.getHeaderField("Accept-Ranges") ?: "").contains("bytes", true)
                val etag = conn.getHeaderField("ETag")
                val lastMod = conn.getHeaderField("Last-Modified")
                val finalUrl = conn.url.toString()

                return Probe(total, acceptRanges, etag, lastMod, finalUrl)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    private fun rangeProbeViaGet(srcUrl: String, connectTimeoutMs: Int, readTimeoutMs: Int): Probe {
        var current = srcUrl
        var hops = 0

        while (true) {
            val conn = openConnNoRedirect(current, "GET", connectTimeoutMs, readTimeoutMs)
            try {
                setCommonHeaders(conn, current)
                conn.setRequestProperty("Range", "bytes=0-0")
                conn.connect()

                val code = conn.responseCode

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location.")
                    current = URL(URL(current), loc).toString()
                    if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects.")
                    continue
                }

                if (code == 429 || code == 503) {
                    throw HttpExceptionWithRetryAfter("GET-probe HTTP $code", readRetryAfterMs(conn))
                }

                if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL) && code !in 200..299) {
                    throw IOException("GET-probe HTTP $code${readErrorSnippet(conn)?.let { ": $it" } ?: ""}")
                }

                val acceptRanges = (conn.getHeaderField("Accept-Ranges") ?: "").contains("bytes", true)
                val etag = conn.getHeaderField("ETag")
                val lastMod = conn.getHeaderField("Last-Modified")
                val finalUrl = conn.url.toString()

                val totalFromRange = conn.getHeaderField("Content-Range")?.let { parseContentRange(it)?.third }
                val totalFromLen = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
                val total = totalFromRange ?: totalFromLen

                return Probe(total, acceptRanges, etag, lastMod, finalUrl)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    // ----------------------------------------------------------
    // Meta file (atomic)
    // ----------------------------------------------------------

    private data class Meta(val etag: String?, val lastModified: String?, val total: Long?)

    private class MetaFile(private val part: File) {
        private val file = File(part.parentFile, part.name + ".meta")

        fun read(): Meta? = runCatching {
            if (!file.exists()) return null
            val map = file.readLines().mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            Meta(map["etag"], map["lastModified"], map["total"]?.toLongOrNull())
        }.getOrNull()

        fun writeAtomic(meta: Meta) {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(
                    buildString {
                        meta.etag?.let { append("etag=$it\n") }
                        meta.lastModified?.let { append("lastModified=$it\n") }
                        meta.total?.let { append("total=$it\n") }
                    }
                )
                if (file.exists()) runCatching { file.delete() }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    runCatching { tmp.delete() }
                }
            }
        }

        fun delete() {
            runCatching { if (file.exists()) file.delete() }
        }
    }

    // ----------------------------------------------------------
    // 416 reconciliation + local promotion
    // ----------------------------------------------------------

    private fun handleRangeNotSatisfiable(
        dst: File,
        part: File,
        meta: MetaFile,
        total: Long,
        expectedSha256: String?,
        onProgress: (Long, Long?) -> Unit
    ): Boolean {
        val onDisk = part.length()

        if (onDisk == total) {
            safeReplaceFile(from = part, to = dst)
            meta.delete()

            if (expectedSha256 != null) {
                val got = sha256(dst)
                if (!got.equals(expectedSha256, true)) {
                    runCatching { dst.delete() }
                    throw IOException("SHA mismatch after 416 reconciliation.")
                }
            }

            onProgress(total, total)
            logd("Completed via 416 reconciliation.")
            return true
        }

        logw("416 mismatch (part=$onDisk, total=$total), restarting from 0.")
        runCatching { part.delete() }
        meta.delete()
        return false
    }

    private fun promotePartToFinalAndValidate(
        dst: File,
        part: File,
        meta: MetaFile,
        total: Long,
        expectedSha256: String?,
        onProgress: (Long, Long?) -> Unit
    ): Boolean {
        if (!part.exists() || part.length() != total) return false

        safeReplaceFile(from = part, to = dst)
        meta.delete()

        if (expectedSha256 != null) {
            val got = sha256(dst)
            if (!got.equals(expectedSha256, true)) {
                runCatching { dst.delete() }
                throw IOException("SHA mismatch after local promotion.")
            }
        }

        onProgress(total, total)
        logd("Promoted local .part to final without network.")
        return true
    }

    // ----------------------------------------------------------
    // Non-resumable download (unknown total)
    // ----------------------------------------------------------

    private suspend fun downloadNonResumable(
        initialUrl: String,
        dst: File,
        part: File,
        expectedSha256: String?,
        onProgress: (Long, Long?) -> Unit,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        ioBufferBytes: Int,
        progressMinIntervalMs: Long,
        progressMinDeltaBytes: Long
    ) {
        var currentUrl = initialUrl
        var hops = 0

        runCatching { if (part.exists()) part.delete() }
        runCatching { if (dst.exists()) dst.delete() }

        var lastProgressAtMs = 0L
        var lastProgressBytes = -1L
        fun emitProgress(downloaded: Long, force: Boolean = false) {
            val now = System.currentTimeMillis()
            val timeOk = (now - lastProgressAtMs) >= progressMinIntervalMs
            val byteOk = (lastProgressBytes < 0L) || (downloaded - lastProgressBytes) >= progressMinDeltaBytes
            if (force || timeOk || byteOk) {
                lastProgressAtMs = now
                lastProgressBytes = downloaded
                onProgress(downloaded, null)
            }
        }

        while (true) {
            ensureActiveCompat(coroutineContext)

            val conn = openConnNoRedirect(currentUrl, "GET", connectTimeoutMs, readTimeoutMs)
            try {
                setCommonHeaders(conn, currentUrl)
                conn.connect()

                val code = conn.responseCode

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect without Location.")
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects.")
                    continue
                }

                if (code == 429 || code == 503) {
                    throw HttpExceptionWithRetryAfter("GET HTTP $code", readRetryAfterMs(conn))
                }

                if (code !in 200..299) {
                    val snippet = readErrorSnippet(conn)
                    throw IOException("GET HTTP $code${snippet?.let { ": $it" } ?: ""}")
                }

                val bufSize = ioBufferBytes.coerceIn(64 * 1024, 2 * 1024 * 1024)
                var downloaded = 0L
                emitProgress(downloaded, force = true)

                conn.inputStream.use { input ->
                    FileOutputStream(part, false).use { fos ->
                        BufferedOutputStream(fos, bufSize).use { out ->
                            val buf = ByteArray(bufSize)
                            while (true) {
                                ensureActiveCompat(coroutineContext)
                                val n = input.read(buf)
                                if (n == -1) break
                                out.write(buf, 0, n)
                                downloaded += n
                                emitProgress(downloaded)
                            }
                            out.flush()
                        }
                        if (fsyncOnFinish) runCatching { fos.fd.sync() }
                    }
                }

                safeReplaceFile(from = part, to = dst)

                if (expectedSha256 != null) {
                    val got = sha256(dst)
                    if (!got.equals(expectedSha256, true)) {
                        runCatching { dst.delete() }
                        throw IOException("SHA-256 mismatch: expected=$expectedSha256 got=$got")
                    }
                }

                emitProgress(dst.length(), force = true)
                return
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    // ----------------------------------------------------------
    // Content-Range parsing
    // ----------------------------------------------------------

    private fun parseContentRange(value: String): Triple<Long, Long, Long?>? {
        val v = value.trim()
        if (!v.startsWith("bytes", ignoreCase = true)) return null

        val parts = v.substringAfter("bytes", "").trim()
        val rangePart = parts.substringBefore('/').trim()
        val totalPart = parts.substringAfter('/', "").trim()

        val dash = rangePart.indexOf('-')
        if (dash <= 0) return null

        val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
        val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
        val total = totalPart.takeIf { it != "*" }?.toLongOrNull()

        return Triple(start, end, total)
    }

    // ----------------------------------------------------------
    // Utility
    // ----------------------------------------------------------

    private fun ensureActiveCompat(ctx: CoroutineContext) {
        val job = ctx[Job]
        if (job != null && !job.isActive) throw CancellationException("Cancelled")
    }

    private fun preflightSanityCleanup(dst: File, part: File, meta: MetaFile) {
        val m = meta.read()

        if (m != null && !part.exists()) {
            logw("Meta exists but .part missing; deleting stale meta.")
            meta.delete()
        }

        if (dst.exists() && dst.length() <= 0L) {
            logw("Destination exists but empty; deleting corrupt dst.")
            runCatching { dst.delete() }
        }

        if (part.exists() && part.length() == 0L && m != null) {
            logw("Part is empty but meta exists; deleting stale meta.")
            meta.delete()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { fis ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openConnNoRedirect(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection {
        val u = URL(url)
        return (u.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            doInput = true
            doOutput = false
        }
    }

    private fun setCommonHeaders(conn: HttpURLConnection, url: String) {
        conn.setRequestProperty("User-Agent", "AndroidSLM/1.0 (HttpUrlFileDownloader)")
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("Accept-Charset", "UTF-8")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("Connection", "close")

        if (isHfHost(url) && !hfToken.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $hfToken")
        }
    }

    private fun readErrorSnippet(conn: HttpURLConnection, maxBytes: Int = 2048): String? {
        return try {
            val es = conn.errorStream ?: return null
            es.use { stream ->
                val buf = ByteArray(maxBytes)
                val n = stream.read(buf)
                if (n <= 0) return null
                String(buf, 0, n, Charsets.UTF_8)
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .trim()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readRetryAfterMs(conn: HttpURLConnection): Long? {
        val raw = conn.getHeaderField("Retry-After")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parseRetryAfterToMillis(raw)
    }

    private fun parseRetryAfterToMillis(raw: String): Long? {
        raw.toLongOrNull()?.let { sec ->
            return (sec.coerceAtLeast(0L) * 1000L)
        }

        return runCatching {
            val df = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                isLenient = true
                timeZone = TimeZone.getTimeZone("GMT")
            }
            val date = df.parse(raw) ?: return null
            val delta = date.time - System.currentTimeMillis()
            delta.coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun checkFreeSpaceOrThrow(dir: File, required: Long) {
        val fs = StatFs(dir.absolutePath)
        val avail = max(0L, fs.availableBytes)
        if (avail < required) {
            throw IOException("Not enough space: need ${required}B, available ${avail}B")
        }
    }

    private fun isHfHost(u: String): Boolean {
        val host = runCatching { URL(u).host ?: "" }.getOrElse { "" }
        return host == "huggingface.co" || host.endsWith(".huggingface.co")
    }

    private fun isNoSpaceLeft(t: IOException): Boolean {
        val msg = t.message.orEmpty()
        return msg.contains("ENOSPC", ignoreCase = true) ||
                msg.contains("No space left", ignoreCase = true) ||
                msg.contains("disk full", ignoreCase = true)
    }

    private fun safeReplaceFile(from: File, to: File) {
        if (to.exists()) runCatching { to.delete() }

        if (from.renameTo(to)) return

        from.copyTo(to, overwrite = true)
        runCatching { from.delete() }
    }

    private fun logd(msg: String) {
        if (debugLogs) Log.d(tag, msg)
    }

    private fun logw(msg: String) {
        if (debugLogs) Log.w(tag, msg)
    }

    private class HttpExceptionWithRetryAfter(
        message: String,
        val retryAfterMs: Long?
    ) : IOException(message)

    private companion object {
        private const val MAX_REDIRECTS = 10
        private const val MAX_STREAM_RETRIES = 3
    }
}
