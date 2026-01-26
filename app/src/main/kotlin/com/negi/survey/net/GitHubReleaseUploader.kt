/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubReleaseUploader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  GitHub REST-based Release Assets uploader for large files.
 *  - Avoids Contents API base64 + JSON memory spikes.
 *  - Streams file content via OkHttp RequestBody.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub Release Assets uploader.
 *
 * Notes:
 * - Uses REST API endpoints under api.github.com and uploads.github.com.
 * - Requires token permissions to create releases and upload assets.
 */
object GitHubReleaseUploader {

    private const val TAG = "github_release_uploader"

    data class ReleaseInfo(
        val id: Long,
        val tagName: String,
        val name: String,
        val uploadUrlTemplate: String,
        val assets: List<AssetInfo>
    )

    data class AssetInfo(
        val id: Long,
        val name: String,
        val url: String,
        val browserDownloadUrl: String
    )

    data class UploadResult(
        val releaseId: Long,
        val assetId: Long,
        val assetName: String,
        val assetUrl: String,
        val browserDownloadUrl: String
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // no overall cap; WorkManager handles retries
            .build()
    }

    /**
     * Upload a file as a Release Asset (streaming).
     *
     * Behavior:
     * - Finds release by tag; creates it if missing (optional).
     * - If an asset with same name exists, deletes it first (optional).
     * - Uploads file as an asset via uploads.github.com endpoint.
     */
    @Throws(IOException::class)
    fun uploadAssetFromFile(
        cfg: GitHubUploader.GitHubConfig,
        tagName: String,
        releaseName: String = tagName,
        file: File,
        assetName: String = file.name,
        contentType: String = guessContentType(assetName),
        createIfMissing: Boolean = true,
        overwriteIfExists: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
    ): UploadResult {
        require(file.exists() && file.isFile) { "File missing: ${file.absolutePath}" }

        val release = findOrCreateRelease(
            cfg = cfg,
            tagName = tagName,
            releaseName = releaseName,
            createIfMissing = createIfMissing
        )

        if (overwriteIfExists) {
            val existing = release.assets.firstOrNull { it.name == assetName }
            if (existing != null) {
                runCatching {
                    deleteAsset(cfg, assetId = existing.id)
                }.onFailure {
                    Log.w(TAG, "Failed to delete existing asset: ${existing.name}", it)
                }
            }
        }

        val uploadUrl = buildUploadUrl(release.uploadUrlTemplate, assetName)
        val respJson = uploadToUploadHost(
            cfg = cfg,
            uploadUrl = uploadUrl,
            file = file,
            contentType = contentType,
            onProgress = onProgress
        )

        val assetId = respJson.optLong("id", -1L)
        val apiUrl = respJson.optString("url", "")
        val browserUrl = respJson.optString("browser_download_url", "")

        if (assetId <= 0L || apiUrl.isBlank()) {
            throw IOException("Upload succeeded but response is missing fields: id=$assetId url='$apiUrl'")
        }

        return UploadResult(
            releaseId = release.id,
            assetId = assetId,
            assetName = assetName,
            assetUrl = apiUrl,
            browserDownloadUrl = browserUrl
        )
    }

    private fun findOrCreateRelease(
        cfg: GitHubUploader.GitHubConfig,
        tagName: String,
        releaseName: String,
        createIfMissing: Boolean
    ): ReleaseInfo {
        val found = runCatching { getReleaseByTag(cfg, tagName) }.getOrNull()
        if (found != null) return found

        if (!createIfMissing) {
            throw IOException("Release not found for tag=$tagName and createIfMissing=false")
        }
        return createRelease(cfg, tagName = tagName, releaseName = releaseName)
    }

    private fun getReleaseByTag(cfg: GitHubUploader.GitHubConfig, tagName: String): ReleaseInfo {
        val url = "https://api.github.com/repos/${cfg.owner}/${cfg.repo}/releases/tags/$tagName"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${cfg.token}")
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                throw IOException("Release tag not found (404): $tagName")
            }
            ensure2xx(resp, "getReleaseByTag")
            val json = JSONObject(resp.body?.string().orEmpty())
            return parseRelease(json)
        }
    }

    private fun createRelease(cfg: GitHubUploader.GitHubConfig, tagName: String, releaseName: String): ReleaseInfo {
        val url = "https://api.github.com/repos/${cfg.owner}/${cfg.repo}/releases"

        val bodyJson = JSONObject()
            .put("tag_name", tagName)
            .put("name", releaseName)
            .put("draft", false)
            .put("prerelease", false)

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBodyJson())
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${cfg.token}")
            .build()

        client.newCall(req).execute().use { resp ->
            ensure2xx(resp, "createRelease")
            val json = JSONObject(resp.body?.string().orEmpty())
            return parseRelease(json)
        }
    }

    private fun deleteAsset(cfg: GitHubUploader.GitHubConfig, assetId: Long) {
        val url = "https://api.github.com/repos/${cfg.owner}/${cfg.repo}/releases/assets/$assetId"
        val req = Request.Builder()
            .url(url)
            .delete()
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${cfg.token}")
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return
            ensure2xx(resp, "deleteAsset")
        }
    }

    private fun buildUploadUrl(uploadUrlTemplate: String, assetName: String): String {
        // Template typically looks like: https://uploads.github.com/repos/{owner}/{repo}/releases/{id}/assets{?name,label}
        val base = uploadUrlTemplate.substringBefore("{")
        val encoded = assetName.urlEncode()
        return "$base?name=$encoded"
    }

    private fun uploadToUploadHost(
        cfg: GitHubUploader.GitHubConfig,
        uploadUrl: String,
        file: File,
        contentType: String,
        onProgress: ((Int) -> Unit)?
    ): JSONObject {
        val total = file.length().coerceAtLeast(1L)

        val requestBody = ProgressFileRequestBody(
            file = file,
            mediaType = contentType.toMediaTypeOrNull(),
        ) { written ->
            val pct = ((written * 100.0) / total.toDouble()).toInt().coerceIn(0, 100)
            onProgress?.invoke(pct)
        }

        val req = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${cfg.token}")
            .header("Content-Type", contentType)
            .build()

        client.newCall(req).execute().use { resp ->
            ensure2xx(resp, "uploadAsset")
            val jsonStr = resp.body?.string().orEmpty()
            return JSONObject(jsonStr)
        }
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val id = json.optLong("id", -1L)
        val tag = json.optString("tag_name", "")
        val name = json.optString("name", tag)
        val uploadUrl = json.optString("upload_url", "")

        val assetsJson = json.optJSONArray("assets") ?: JSONArray()
        val assets = buildList {
            for (i in 0 until assetsJson.length()) {
                val a = assetsJson.optJSONObject(i) ?: continue
                add(
                    AssetInfo(
                        id = a.optLong("id", -1L),
                        name = a.optString("name", ""),
                        url = a.optString("url", ""),
                        browserDownloadUrl = a.optString("browser_download_url", "")
                    )
                )
            }
        }

        if (id <= 0L || tag.isBlank() || uploadUrl.isBlank()) {
            throw IOException("Invalid release JSON: id=$id tag='$tag' uploadUrl='$uploadUrl'")
        }

        return ReleaseInfo(
            id = id,
            tagName = tag,
            name = name,
            uploadUrlTemplate = uploadUrl,
            assets = assets
        )
    }

    private fun ensure2xx(resp: Response, op: String) {
        if (resp.isSuccessful) return
        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        throw IOException("$op failed: http=${resp.code} msg=${resp.message} body=$body")
    }

    private fun String.toRequestBodyJson(): RequestBody {
        return RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), this)
    }

    private fun guessContentType(name: String): String {
        val n = name.lowercase(Locale.US)
        return when {
            n.endsWith(".wav") -> "audio/wav"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".m4a") -> "audio/mp4"
            n.endsWith(".gz") -> "application/gzip"
            n.endsWith(".zip") -> "application/zip"
            n.endsWith(".json") || n.endsWith(".jsonl") -> "application/json"
            n.endsWith(".txt") || n.endsWith(".csv") -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * Streaming RequestBody with progress callback.
     */
    private class ProgressFileRequestBody(
        private val file: File,
        private val mediaType: okhttp3.MediaType?,
        private val onWritten: (Long) -> Unit
    ) : RequestBody() {

        override fun contentType() = mediaType
        override fun contentLength() = file.length()

        override fun writeTo(sink: BufferedSink) {
            val buf = ByteArray(64 * 1024)
            var written = 0L

            FileInputStream(file).use { fis ->
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    written += n.toLong()
                    onWritten(written)
                }
            }
        }
    }

    private fun String.urlEncode(): String {
        // Minimal encoding for query param; avoids pulling additional deps.
        val sb = StringBuilder(length * 2)
        for (ch in this) {
            when (ch) {
                ' ' -> sb.append("%20")
                '#' -> sb.append("%23")
                '%' -> sb.append("%25")
                '&' -> sb.append("%26")
                '+' -> sb.append("%2B")
                '?' -> sb.append("%3F")
                '=' -> sb.append("%3D")
                '/' -> sb.append("%2F")
                '\\' -> sb.append("%5C")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
