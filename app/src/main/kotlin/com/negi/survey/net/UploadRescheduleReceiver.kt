/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadRescheduleReceiver.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  BroadcastReceiver that automatically re-enqueues any pending uploads
 *  after system reboot or app update.
 *
 *  - GitHub pending dir: /files/pending_uploads/
 *  - Supabase pending dir: /files/pending_uploads_sb/
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.negi.survey.BuildConfig
import java.io.File
import java.util.Locale

class UploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isRelevantAction(action)) return

        val storageContext = when {
            action == ACTION_LOCKED_BOOT_COMPLETED -> context.createDeviceProtectedStorageContext()
            else -> context
        }

        rescheduleGitHub(storageContext, action)
        rescheduleSupabase(storageContext, action)
    }

    private fun rescheduleGitHub(context: Context, action: String) {
        val cfg = GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            token = BuildConfig.GH_TOKEN,
            branch = BuildConfig.GH_BRANCH,
            pathPrefix = BuildConfig.GH_PATH_PREFIX
        )

        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            Log.d(TAG, "Skip GitHub reschedule: missing credentials.")
            return
        }

        val dir = File(context.filesDir, PENDING_DIR_GH)
        if (!dir.exists() || !dir.isDirectory) {
            Log.d(TAG, "No GitHub pending dir for action=$action path=${dir.absolutePath}")
            return
        }

        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            Log.d(TAG, "No GitHub pending files for action=$action")
            return
        }

        Log.d(TAG, "Rescheduling ${files.size} GitHub pending uploads for action=$action")

        files.forEach { file ->
            runCatching {
                GitHubUploadWorker.enqueueExistingPayload(context, cfg, file)
            }.onFailure { t ->
                Log.w(TAG, "GitHub enqueue failed file=${file.name}: ${t.message}")
            }
        }
    }

    private fun rescheduleSupabase(context: Context, action: String) {
        val sbUrl = BuildConfig.SUPABASE_URL
        val sbAnon = BuildConfig.SUPABASE_ANON_KEY
        val sbBucket = BuildConfig.SUPABASE_LOG_BUCKET
        val sbPrefix = BuildConfig.SUPABASE_LOG_PATH_PREFIX

        val cfg = SupabaseUploader.SupabaseConfig(
            supabaseUrl = sbUrl,
            anonKey = sbAnon,
            bucket = sbBucket,
            pathPrefix = sbPrefix.ifBlank { "surveyapp" },
            maxRawBytesHint = 20_000_000L
        )

        if (cfg.supabaseUrl.isBlank() || cfg.anonKey.isBlank() || cfg.bucket.isBlank()) {
            Log.d(TAG, "Skip Supabase reschedule: missing configuration.")
            return
        }

        val dir = File(context.filesDir, PENDING_DIR_SB)
        if (!dir.exists() || !dir.isDirectory) {
            Log.d(TAG, "No Supabase pending dir for action=$action path=${dir.absolutePath}")
            return
        }

        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            Log.d(TAG, "No Supabase pending files for action=$action")
            return
        }

        Log.d(TAG, "Rescheduling ${files.size} Supabase pending uploads for action=$action")

        files.forEach { file ->
            val remoteDir = guessSupabaseRemoteDir(file)
            val contentType = guessContentType(file)

            runCatching {
                SupabaseUploadWorker.enqueueExistingPayload(
                    context = context,
                    cfg = cfg,
                    file = file,
                    remoteDir = remoteDir,
                    contentType = contentType,
                    upsert = false,
                    userJwt = null,
                    maxBytesHint = cfg.maxRawBytesHint
                )
            }.onFailure { t ->
                Log.w(TAG, "Supabase enqueue failed file=${file.name}: ${t.message}")
            }
        }
    }

    private fun guessSupabaseRemoteDir(file: File): String {
        val name = file.name.lowercase(Locale.US)
        return when {
            name.startsWith("logcat_") || name.endsWith(".log.gz") || name.endsWith(".gz") -> "diagnostics/logcat"
            name.endsWith(".wav") -> "voice"
            else -> "regular"
        }
    }

    private fun guessContentType(file: File): String {
        val name = file.name.lowercase(Locale.US)
        return when {
            name.endsWith(".json") -> "application/json"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".gz") -> "application/gzip"
            else -> "application/octet-stream"
        }
    }

    private fun isRelevantAction(action: String): Boolean =
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> true
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            ACTION_LOCKED_BOOT_COMPLETED -> true
            else -> false
        }

    private companion object {
        private const val TAG = "UploadRescheduleRcvr"

        /** Directory under `/files/` containing pending GitHub upload payloads. */
        private const val PENDING_DIR_GH = "pending_uploads"

        /** Directory under `/files/` containing pending Supabase upload payloads. */
        private const val PENDING_DIR_SB = "pending_uploads_sb"

        /** String constant for locked boot action to avoid API gated references. */
        private const val ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED"
    }
}
