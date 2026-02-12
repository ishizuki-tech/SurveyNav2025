/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseStorageUploader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Minimal, robust Supabase Storage uploader using HttpURLConnection.
 *  Designed for Android background uploads (WorkManager-friendly).
 *
 *  Notes:
 *  - Uses anon key by default (BuildConfig.SUPABASE_ANON_KEY).
 *  - Requires Storage RLS policies to allow INSERT/UPSERT for the target bucket/path.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.util.Log
import com.negi.survey.BuildConfig
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object SupabaseStorageUploader {
    private const val TAG = "SupabaseStorageUp"

    private const val DEFAULT_MAX_BYTES_GUARD = 20_000_000L

    data class SupabaseConfig(
        val supabaseUrl: String,
        val anonKey: String,
        val bucket: String,
        val pathPrefix: String
    )

    /**
     * Returns a config from BuildConfig if Supabase is configured, else null.
     */
    fun configFromBuildConfig(): SupabaseConfig? {
        val url = BuildConfig.SUPABASE_URL.trim()
        val key = BuildConfig.SUPABASE_ANON_KEY.trim()
        val bucket = BuildConfig.SUPABASE_LOG_BUCKET.trim().ifEmpty { "logs" }
        val prefix = BuildConfig.SUPABASE_LOG_PATH_PREFIX.trim().trim('/')

        if (url.isBlank() || key.isBlank()) return null
        return SupabaseConfig(
            supabaseUrl = url.trimEnd('/'),
            anonKey = key,
            bucket = bucket,
            pathPrefix = prefix
        )
    }

    /**
     * Upload raw bytes to Supabase Storage.
     *
     * @param cfg Supabase config.
     * @param remotePath Remote object path *under* the bucket (no leading slash).
     * @param bytes Content bytes.
     * @param contentType MIME type.
     * @param upsert If true, overwrite if exists (may require UPDATE RLS policy).
     */
    suspend fun uploadBytes(
        cfg: SupabaseConfig,
        remotePath: String,
        bytes: ByteArray,
        contentType: String,
        upsert: Boolean = false,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 90_000,
        maxRetries: Int = 3
    ): Result<Unit> = withContext(Dispatchers.IO) {

        val normalizedRemote = normalizeRemote(cfg, remotePath)
        val coreCfg = toCoreConfig(cfg, bytes.size.toLong(), connectTimeoutMs, readTimeoutMs)

        var attempt = 0
        var last: Throwable? = null

        while (attempt < maxRetries) {
            try {
                SupabaseUploader.uploadBytes(
                    cfg = coreCfg,
                    objectPath = normalizedRemote,
                    bytes = bytes,
                    contentType = contentType,
                    upsert = upsert,
                    tokenOverride = null
                )
                return@withContext Result.success(Unit)
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "Upload failed (attempt=${attempt + 1}/$maxRetries): ${t.message}")
                if (attempt < maxRetries - 1) {
                    val backoffMs = (500.0 * 2.0.pow(attempt.toDouble())).toLong()
                    delay(backoffMs)
                }
            } finally {
                attempt++
            }
        }

        Result.failure(last ?: IOException("Supabase upload failed"))
    }

    /**
     * Upload a file to Supabase Storage (streaming via SupabaseUploader).
     */
    suspend fun uploadFile(
        cfg: SupabaseConfig,
        remotePath: String,
        file: File,
        contentType: String,
        upsert: Boolean = false,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 90_000,
        maxRetries: Int = 3
    ): Result<Unit> = withContext(Dispatchers.IO) {

        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(IOException("File missing: ${file.absolutePath}"))
        }

        val normalizedRemote = normalizeRemote(cfg, remotePath)
        val coreCfg = toCoreConfig(cfg, file.length(), connectTimeoutMs, readTimeoutMs)

        var attempt = 0
        var last: Throwable? = null

        while (attempt < maxRetries) {
            try {
                SupabaseUploader.uploadFile(
                    cfg = coreCfg,
                    objectPath = normalizedRemote,
                    file = file,
                    contentType = contentType,
                    upsert = upsert,
                    tokenOverride = null
                )
                return@withContext Result.success(Unit)
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "Upload failed (attempt=${attempt + 1}/$maxRetries): ${t.message}")
                if (attempt < maxRetries - 1) {
                    val backoffMs = (500.0 * 2.0.pow(attempt.toDouble())).toLong()
                    delay(backoffMs)
                }
            } finally {
                attempt++
            }
        }

        Result.failure(last ?: IOException("Supabase upload failed"))
    }

    private fun normalizeRemote(cfg: SupabaseConfig, remotePath: String): String {
        val p = remotePath.trim().trimStart('/')
        val prefix = cfg.pathPrefix.trim().trim('/')
        return if (prefix.isBlank()) p else "$prefix/$p"
    }

    /**
     * Convert the lightweight config to the core uploader config.
     *
     * Note: maxRawBytesHint is used as a guard. For streaming we allow at least the content length.
     */
    private fun toCoreConfig(
        cfg: SupabaseConfig,
        contentLength: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): SupabaseUploader.SupabaseConfig {
        return SupabaseUploader.SupabaseConfig(
            supabaseUrl = cfg.supabaseUrl.trimEnd('/'),
            anonKey = cfg.anonKey.trim(),
            bucket = cfg.bucket.trim(),
            pathPrefix = cfg.pathPrefix.trim().trim('/'),
            maxRawBytesHint = max(DEFAULT_MAX_BYTES_GUARD, contentLength + 1024L),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
    }
}
