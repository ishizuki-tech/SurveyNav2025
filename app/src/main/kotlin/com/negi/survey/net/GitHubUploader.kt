/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubUploader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Coroutine-based GitHub file uploader for JSON, text, and binary files.
 *
 *  This object wraps the GitHub Contents API with:
 *   • Path building with optional date folder insertion
 *   • SHA lookup for create/update semantics
 *   • Base64 encoding
 *   • Retry with exponential backoff (and Retry-After support)
 *   • Lightweight progress callbacks
 *
 *  Notes:
 *   • GitHub Contents API is intended for relatively small files.
 *     For large binaries (e.g., long WAV), consider Git LFS or Releases.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Coroutine-based GitHub file uploader for JSON, text, and binary files.
 *
 * This object abstracts away the GitHub Contents API details:
 * - Detects whether a file exists and reuses its SHA for updates.
 * - Uploads content using Base64 encoding.
 * - Retries transient errors with backoff.
 * - Reports simple progress as integer percentage.
 *
 * Intended usage:
 * - Call from background contexts or WorkManager workers.
 * - Prefer small to medium payloads.
 */
object GitHubUploader {

    private const val TAG = "GitHubUploader"

    private const val API_BASE = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"
    private const val USER_AGENT = "AndroidSLM/1.0"

    private const val DEFAULT_MESSAGE = "Upload via SurveyNav"

    /**
     * Conservative upper bound for Contents API payload bytes.
     *
     * GitHub's create/update contents endpoint is not meant for large files.
     * This guard prevents confusing server-side failures for oversized payloads.
     *
     * Adjust this if your real-world measurements prove safe for slightly larger
     * payloads, but consider alternative upload strategies for large media.
     */
    private const val MAX_CONTENTS_API_BYTES = 900_000

    // ---------------------------------------------------------------------
    // Configuration Models
    // ---------------------------------------------------------------------

    /**
     * Configuration container for GitHub upload operations.
     *
     * @property owner      Repository owner (user or organization).
     * @property repo       Repository name.
     * @property token      GitHub Personal Access Token with contents write permission.
     * @property branch     Target branch (default: main).
     * @property pathPrefix Logical root folder inside the repo.
     *
     * When using [uploadJson] / [uploadFile] overloads that take [GitHubConfig],
     * the final path becomes:
     *
     *   pathPrefix / yyyy-MM-dd / relativePath
     *
     * If [pathPrefix] is empty:
     *
     *   yyyy-MM-dd / relativePath
     */
    data class GitHubConfig(
        val owner: String,
        val repo: String,
        val token: String,
        val branch: String = "main",
        val pathPrefix: String = ""
    )

    /**
     * Result of a successful upload or update.
     *
     * @property fileUrl   Public HTML URL of the file on GitHub.
     * @property commitSha Commit SHA from the operation (if available).
     */
    data class UploadResult(
        val fileUrl: String?,
        val commitSha: String?
    )

    // ---------------------------------------------------------------------
    // Public APIs — JSON/Text Upload
    // ---------------------------------------------------------------------

    /**
     * Upload a JSON or text file using [GitHubConfig].
     *
     * The final GitHub path includes an automatic date folder.
     */
    suspend fun uploadJson(
        cfg: GitHubConfig,
        relativePath: String,
        content: String,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadJson(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, relativePath),
        token = cfg.token,
        content = content,
        message = message,
        onProgress = onProgress
    )

    /**
     * Core JSON/text upload function.
     *
     * [path] is treated as a fully-resolved path.
     */
    suspend fun uploadJson(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        content: String,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadBytes(
        owner = owner,
        repo = repo,
        branch = branch,
        path = path,
        token = token,
        contentBytes = content.toByteArray(Charsets.UTF_8),
        message = message,
        onProgress = onProgress
    )

    // ---------------------------------------------------------------------
    // Public APIs — Binary Upload
    // ---------------------------------------------------------------------

    /**
     * Upload a binary file (e.g., WAV) using [GitHubConfig].
     *
     * The final GitHub path includes an automatic date folder.
     */
    suspend fun uploadFile(
        cfg: GitHubConfig,
        relativePath: String,
        bytes: ByteArray,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadFile(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, relativePath),
        token = cfg.token,
        bytes = bytes,
        message = message,
        onProgress = onProgress
    )

    /**
     * Core binary upload function.
     */
    suspend fun uploadFile(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        bytes: ByteArray,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadBytes(
        owner = owner,
        repo = repo,
        branch = branch,
        path = path,
        token = token,
        contentBytes = bytes,
        message = message,
        onProgress = onProgress
    )

    // ---------------------------------------------------------------------
    // Shared JSON/Binary Implementation
    // ---------------------------------------------------------------------

    /**
     * Shared implementation for both JSON/text and binary uploads.
     *
     * This method:
     * 1) Validates arguments.
     * 2) Resolves the remote SHA if the file already exists.
     * 3) PUTs Base64 payload to the Contents API.
     * 4) Parses the response and returns an [UploadResult].
     */
    private suspend fun uploadBytes(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        contentBytes: ByteArray,
        message: String,
        onProgress: (Int) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {

        require(owner.isNotBlank()) { "GitHub owner cannot be blank." }
        require(repo.isNotBlank()) { "GitHub repo cannot be blank." }
        require(branch.isNotBlank()) { "GitHub branch cannot be blank." }
        require(path.isNotBlank()) { "GitHub path cannot be blank." }
        require(token.isNotBlank()) { "GitHub token cannot be blank." }

        if (contentBytes.size > MAX_CONTENTS_API_BYTES) {
            throw IOException(
                "Content too large for GitHub Contents API " +
                        "(size=${contentBytes.size} bytes, limit~$MAX_CONTENTS_API_BYTES). " +
                        "Use Git LFS or Releases for large binaries."
            )
        }

        val encodedPath = encodePath(path)

        Log.d(
            TAG,
            "uploadBytes: owner=$owner repo=$repo branch=$branch path=$path size=${contentBytes.size}"
        )

        // Phase 1 — Lookup existing SHA
        onProgress(0)
        val existingSha = getExistingSha(owner, repo, branch, encodedPath, token)
        onProgress(10)

        // Phase 2 — Prepare JSON payload
        val payload = JSONObject().apply {
            put("message", message.ifBlank { DEFAULT_MESSAGE })
            put("branch", branch)
            put("content", Base64.encodeToString(contentBytes, Base64.NO_WRAP))
            if (!existingSha.isNullOrBlank()) {
                put("sha", existingSha)
            }
        }.toString()

        val url = URL("$API_BASE/repos/$owner/$repo/contents/$encodedPath")
        val requestBytes = payload.toByteArray(Charsets.UTF_8)
        val total = requestBytes.size

        val writeBody: (HttpURLConnection) -> Unit = { conn ->
            conn.setFixedLengthStreamingMode(total)
            conn.outputStream.use { os: OutputStream ->
                val chunk = 8 * 1024
                var off = 0
                while (off < total) {
                    val len = min(chunk, total - off)
                    os.write(requestBytes, off, len)
                    off += len

                    val pct = 10 + ((off.toDouble() / total) * 80.0).toInt()
                    onProgress(min(90, pct))
                }
                os.flush()
            }
        }

        // Phase 3 — Execute request with retry/backoff
        val response = executeWithRetry(
            method = "PUT",
            url = url,
            token = token,
            writeBody = writeBody
        )
        onProgress(95)

        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            throw IOException("Malformed JSON from GitHub: ${e.message}", e)
        }
        onProgress(100)

        val fileUrl =
            json.optJSONObject("content")
                ?.optString("html_url")
                ?.takeIf { it.isNotBlank() }

        val commitSha =
            json.optJSONObject("commit")
                ?.optString("sha")
                ?.takeIf { it.isNotBlank() }

        Log.d(TAG, "uploadBytes: done url=$fileUrl sha=$commitSha")

        UploadResult(fileUrl, commitSha)
    }

    // ---------------------------------------------------------------------
    // Retry/HTTP Internals
    // ---------------------------------------------------------------------

    /**
     * Lightweight HTTP response container.
     */
    private data class HttpResponse(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>>
    )

    /**
     * Exception for retryable HTTP errors.
     */
    private class TransientHttpException(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?
    ) : IOException()

    /**
     * Exception for non-retryable HTTP errors.
     */
    private class HttpFailureException(val code: Int, val body: String) :
        IOException("GitHub request failed ($code): ${body.take(256)}")

    /**
     * Execute an HTTP request with retry/backoff.
     *
     * Retries:
     * - 429
     * - 5xx
     * - IOException network errors
     *
     * Honors Retry-After if present.
     */
    private suspend fun executeWithRetry(
        method: String,
        url: URL,
        token: String,
        writeBody: (HttpURLConnection) -> Unit,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 30_000,
        maxAttempts: Int = 3
    ): HttpResponse {
        var attempt = 0
        var lastError: IOException? = null

        while (attempt < maxAttempts) {
            attempt++

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doOutput = true
                doInput = true

                setRequestProperty("Authorization", "Bearer ${token.trim()}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                setRequestProperty("User-Agent", USER_AGENT)

                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }

            try {
                writeBody(conn)

                val code = conn.responseCode
                val headers = conn.headerFields.filterKeys { it != null }

                if (code in 200..299) {
                    val body = conn.inputStream.use(::readAll)
                    return HttpResponse(code, body, headers)
                }

                val errBody = conn.errorStream?.use(::readAll).orEmpty()

                if (code == 429 || code in 500..599) {
                    val retryAfter = parseRetryAfterSeconds(headers)
                    throw TransientHttpException(code, errBody, retryAfter)
                }

                throw HttpFailureException(code, errBody)

            } catch (e: TransientHttpException) {
                lastError = IOException("Transient HTTP ${e.code}: ${e.body.take(200)}", e)
                if (attempt >= maxAttempts) throw lastError

                val backoff =
                    e.retryAfterSeconds?.times(1000L)
                        ?: (500L shl (attempt - 1))

                delay(backoff)

            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxAttempts) throw e

                val backoff = 500L shl (attempt - 1)
                delay(backoff)

            } finally {
                conn.disconnect()
            }
        }

        throw lastError ?: IOException("HTTP failed after $maxAttempts attempts.")
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Encode a repo path safely for the Contents API URL.
     *
     * This encodes each path segment independently.
     */
    private fun encodePath(path: String): String =
        path.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }

    /**
     * Build a dated GitHub path:
     *   prefix / yyyy-MM-dd / relative
     *
     * Empty segments are skipped.
     */
    private fun buildPath(prefix: String, relative: String): String {
        val dateSegment = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val segments = listOf(
            prefix.trim('/'),
            dateSegment,
            relative.trim('/')
        ).filter { it.isNotBlank() }

        return segments.joinToString("/")
    }

    /**
     * Parse Retry-After seconds with case-insensitive header matching.
     */
    private fun parseRetryAfterSeconds(headers: Map<String, List<String>>): Long? {
        val key = headers.keys.firstOrNull { it.equals("Retry-After", ignoreCase = true) }
        return key?.let { headers[it]?.firstOrNull()?.toLongOrNull() }
    }

    /**
     * Read an entire input stream as UTF-8 text.
     */
    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)

    /**
     * Look up an existing file SHA if the path already exists on the target branch.
     *
     * Returns null when:
     * - File does not exist
     * - API returns non-200
     * - Parsing fails
     */
    private fun getExistingSha(
        owner: String,
        repo: String,
        branch: String,
        encodedPath: String,
        token: String
    ): String? {

        val refEncoded = URLEncoder.encode(branch.trim(), "UTF-8").replace("+", "%20")
        val url = URL("$API_BASE/repos/$owner/$repo/contents/$encodedPath?ref=$refEncoded")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            doInput = true

            setRequestProperty("Authorization", "Bearer ${token.trim()}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("User-Agent", USER_AGENT)

            connectTimeout = 15_000
            readTimeout = 20_000
        }

        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.use(::readAll)
                JSONObject(body).optString("sha").takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
