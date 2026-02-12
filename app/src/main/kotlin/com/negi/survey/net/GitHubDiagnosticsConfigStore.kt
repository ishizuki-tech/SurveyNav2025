/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubDiagnosticsConfigStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Stores and loads GitHubUploader.GitHubConfig for deferred uploads.
 *  Uses EncryptedSharedPreferences when available; falls back to plain prefs.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "GitHubDiagCfgStore"

object GitHubDiagnosticsConfigStore {

    private const val PREF_NAME = "github_diag_cfg"
    private const val KEY_OWNER = "owner"
    private const val KEY_REPO = "repo"
    private const val KEY_TOKEN = "token"
    private const val KEY_BRANCH = "branch"
    private const val KEY_PREFIX = "prefix"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /**
     * Persist GitHub config used for deferred crash/log uploads.
     *
     * WARNING:
     * - Token is sensitive. Prefer EncryptedSharedPreferences.
     * - Use a fine-grained token restricted to one repo/path whenever possible.
     */
    fun save(context: Context, cfg: GitHubUploader.GitHubConfig) {
        val prefs = prefs(context)
        prefs.edit()
            .putString(KEY_OWNER, cfg.owner)
            .putString(KEY_REPO, cfg.repo)
            .putString(KEY_TOKEN, cfg.token)
            .putString(KEY_BRANCH, cfg.branch)
            .putString(KEY_PREFIX, cfg.pathPrefix)
            .apply()

        Log.i(TAG, "Saved GitHub diagnostics config (owner=${cfg.owner}, repo=${cfg.repo}).")
    }

    /**
     * Load config or null if missing/invalid.
     */
    fun load(context: Context): GitHubUploader.GitHubConfig? {
        val prefs = prefs(context)
        val owner = prefs.getString(KEY_OWNER, null).orEmpty()
        val repo = prefs.getString(KEY_REPO, null).orEmpty()
        val token = prefs.getString(KEY_TOKEN, null).orEmpty()
        val branch = prefs.getString(KEY_BRANCH, "main").orEmpty()
        val prefix = prefs.getString(KEY_PREFIX, "").orEmpty()

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) return null

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            token = token,
            branch = branch.ifBlank { "main" },
            pathPrefix = prefix
        )
    }

    /**
     * Build preferences, preferring encrypted storage.
     *
     * NOTE:
     * - We cache the SharedPreferences instance to avoid repeated MasterKey creation.
     * - We use applicationContext to avoid leaking short-lived contexts.
     */
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
