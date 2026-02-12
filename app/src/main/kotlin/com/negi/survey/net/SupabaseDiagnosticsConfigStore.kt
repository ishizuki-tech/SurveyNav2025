/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseDiagnosticsConfigStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

private const val TAG = "SupabaseDiagCfgStore"

/**
 * Stores and loads SupabaseUploader.SupabaseConfig for deferred uploads.
 *
 * NOTE:
 * - anonKey is sensitive-ish (still a client key). Prefer encrypted prefs.
 * - Do NOT store service_role in the app.
 */
object SupabaseDiagnosticsConfigStore {

    private const val PREF_NAME = "supabase_diag_cfg"

    private const val KEY_URL = "url"
    private const val KEY_ANON = "anonKey"
    private const val KEY_BUCKET = "bucket"
    private const val KEY_PREFIX = "prefix"
    private const val KEY_MAX_RAW = "maxRawBytesHint"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    fun save(context: Context, cfg: SupabaseUploader.SupabaseConfig) {
        val prefs = prefs(context)
        prefs.edit {
            putString(KEY_URL, cfg.supabaseUrl)
                .putString(KEY_ANON, cfg.anonKey)
                .putString(KEY_BUCKET, cfg.bucket)
                .putString(KEY_PREFIX, cfg.pathPrefix)
                .putLong(KEY_MAX_RAW, cfg.maxRawBytesHint)
        }

        Log.i(TAG, "Saved Supabase diagnostics config (bucket=${cfg.bucket}, prefix=${cfg.pathPrefix}).")
    }

    fun load(context: Context): SupabaseUploader.SupabaseConfig? {
        val prefs = prefs(context)

        val url = prefs.getString(KEY_URL, null).orEmpty()
        val anon = prefs.getString(KEY_ANON, null).orEmpty()
        val bucket = prefs.getString(KEY_BUCKET, null).orEmpty()
        val prefix = prefs.getString(KEY_PREFIX, "surveyapp").orEmpty()
        val maxRaw = prefs.getLong(KEY_MAX_RAW, 20_000_000L)

        if (url.isBlank() || anon.isBlank() || bucket.isBlank()) return null

        return SupabaseUploader.SupabaseConfig(
            supabaseUrl = url,
            anonKey = anon,
            bucket = bucket,
            pathPrefix = prefix.ifBlank { "surveyapp" },
            maxRawBytesHint = maxRaw.coerceAtLeast(1L)
        )
    }

    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }

        synchronized(this) {
            cachedPrefs?.let { return it }

            val appContext = context.applicationContext
            val created = runCatching {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    appContext,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse { e ->
                Log.w(TAG, "Encrypted prefs unavailable; falling back to plain prefs: ${e.message}")
                appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            }

            cachedPrefs = created
            return created
        }
    }
}
