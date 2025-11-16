package com.negi.survey.net

import android.util.Base64
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
 * Coroutine-based GitHub file uploader for JSON and text files.
 *
 * This object abstracts away the complexity of the GitHub Contents API,
 * handling retries, authentication, and progress reporting for you.
 *
 * Use this class in background or worker contexts (IO dispatcher).
 */
object GitHubUploader {

    // ---------------------------------------------------------------------
    // 📦 Configuration Models
    // ---------------------------------------------------------------------

    /**
     * Configuration container for GitHub upload operations.
     *
     * @property owner       Repository owner (user or organization).
     * @property repo        Repository name.
     * @property token       GitHub Personal Access Token (PAT) with `contents:write` scope.
     * @property branch      Target branch (default: `main`).
     * @property pathPrefix  Logical root folder inside the repo.
     *
     * English comment:
     * - When using the [uploadJson] overload that takes [GitHubConfig], the
     *   final path becomes:
     *
     *       pathPrefix / yyyy-MM-dd / relativePath
     *
     *   If [pathPrefix] is empty, the date folder becomes the root:
     *
     *       yyyy-MM-dd/relativePath
     */
    data class GitHubConfig(
        val owner: String,
        val repo: String,
        val token: String,
        val branch: String = "main",
        val pathPrefix: String = ""   // ← ここを空デフォルトに
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
    // 🚀 Public APIs
    // ---------------------------------------------------------------------

    /**
     * Uploads a JSON or text file to GitHub using the provided configuration.
     *
     * English comment:
     * - The final GitHub path has a date segment inserted automatically:
     *
     *       cfg.pathPrefix / yyyy-MM-dd / relativePath
     *
     *   Examples:
     *       cfg.pathPrefix = ""
     *       relativePath   = "survey_123.json"
     *       → "2025-11-15/survey_123.json"
     *
     *       cfg.pathPrefix = "exports"
     *       → "exports/2025-11-15/survey_123.json"
     */
    suspend fun uploadJson(
        cfg: GitHubConfig,
        relativePath: String,
        content: String,
        message: String = "Upload via SurveyNav",
        onProgressPercent: (Int) -> Unit = { _ -> }
    ): UploadResult = uploadJson(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, relativePath),
        token = cfg.token,
        content = content,
        message = message,
        onProgressPercent = onProgressPercent
    )

    /**
     * Core upload function — creates or updates a file using GitHub Contents API.
     *
     * English comment:
     * - [path] is treated as a fully-resolved path. Date folder insertion
     *   only happens in [buildPath] when using the [GitHubConfig] overload.
     */
    suspend fun uploadJson(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        content: String,
        message: String = "Upload via SurveyNav",
        onProgressPercent: (Int) -> Unit = { _ -> }
    ): UploadResult = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "GitHub token cannot be blank." }

        val encodedPath = encodePath(path)

        // Phase 1 — Lookup existing SHA
        onProgressPercent(0)
        val existingSha = getExistingSha(owner, repo, branch, encodedPath, token)
        onProgressPercent(10)

        // Phase 2 — Prepare JSON payload
        val payload = JSONObject().apply {
            put("message", message)
            put("branch", branch)
            put(
                "content",
                Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            if (existingSha != null) put("sha", existingSha)
        }.toString()

        // Setup PUT connection and streaming
        val url = URL("https://api.github.com/repos/$owner/$repo/contents/$encodedPath")
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
                    onProgressPercent(min(90, pct))
                }
                os.flush()
            }
        }

        // Phase 3 — Execute request with retry/backoff
        val response = executeWithRetry("PUT", url, token, writeBody)
        onProgressPercent(95)

        // Parse JSON result
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            throw IOException("Malformed JSON from GitHub: ${e.message}", e)
        }
        onProgressPercent(100)

        val fileUrl =
            json.optJSONObject("content")?.optString("html_url")?.takeIf { it.isNotBlank() }
        val commitSha = json.optJSONObject("commit")?.optString("sha")?.takeIf { it.isNotBlank() }

        UploadResult(fileUrl, commitSha)
    }

    // ---------------------------------------------------------------------
    // 🕰 Retry/HTTP internals
    // ---------------------------------------------------------------------

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>>
    )

    private class TransientHttpException(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?
    ) : IOException()

    private class HttpFailureException(val code: Int, val body: String) :
        IOException("GitHub request failed ($code): ${body.take(256)}")

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
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "AndroidSLM/1.0")
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
                } else {
                    val errBody = conn.errorStream?.use(::readAll).orEmpty()
                    if (code == 429 || code in 500..599) {
                        val retryAfter = parseRetryAfterSeconds(headers)
                        throw TransientHttpException(code, errBody, retryAfter)
                    } else {
                        throw HttpFailureException(code, errBody)
                    }
                }
            } catch (e: TransientHttpException) {
                lastError = IOException("Transient HTTP ${e.code}: ${e.body.take(200)}", e)
                if (attempt >= maxAttempts) throw lastError
                val backoff = e.retryAfterSeconds?.times(1000L) ?: (500L shl (attempt - 1))
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
    // 🔧 Helpers
    // ---------------------------------------------------------------------

    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    /**
     * English comment:
     * Build a dated GitHub path:
     *
     *   prefix / yyyy-MM-dd / relative
     *
     * - Empty segments are skipped, so:
     *     buildPath("", "file.json")
     *   becomes:
     *     yyyy-MM-dd/file.json
     */
    private fun buildPath(prefix: String, relative: String): String {
        val dateSegment = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Date())

        val segments = listOf(
            prefix.trim('/'),
            dateSegment,
            relative.trim('/')
        ).filter { it.isNotBlank() }

        return segments.joinToString("/")
    }

    private fun parseRetryAfterSeconds(headers: Map<String, List<String>>): Long? =
        headers["Retry-After"]?.firstOrNull()?.toLongOrNull()

    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)

    private fun getExistingSha(
        owner: String,
        repo: String,
        branch: String,
        encodedPath: String,
        token: String
    ): String? {
        val url = URL("https://api.github.com/repos/$owner/$repo/contents/$encodedPath?ref=$branch")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer ${token.trim()}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "AndroidSLM/1.0")
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
