/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseUploadRescheduleReceiver.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
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

class SupabaseUploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isRelevantAction(action)) return

        val storageContext = when {
            action == ACTION_LOCKED_BOOT_COMPLETED -> context.createDeviceProtectedStorageContext()
            else -> context
        }

        val cfg = SupabaseUploader.SupabaseConfig(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            bucket = BuildConfig.SUPABASE_LOG_BUCKET,
            pathPrefix = "surveyapp",
            maxRawBytesHint = 20_000_000L
        )

        if (cfg.supabaseUrl.isBlank() || cfg.anonKey.isBlank() || cfg.bucket.isBlank()) {
            Log.d(TAG, "Skip reschedule: missing Supabase credentials.")
            return
        }

        val dir = File(storageContext.filesDir, PENDING_DIR)
        if (!dir.exists() || !dir.isDirectory) {
            Log.d(TAG, "No pending dir found for action=$action path=${dir.absolutePath}")
            return
        }

        val files = dir.walkTopDown()
            .filter { it.isFile && it.length() > 0L }
            .toList()

        if (files.isEmpty()) {
            Log.d(TAG, "No pending files for action=$action")
            return
        }

        Log.d(TAG, "Rescheduling ${files.size} pending uploads for action=$action (Supabase)")

        files.forEach { file ->
            runCatching {
                // Heuristic: route by parent folder name.
                val parentName = file.parentFile?.name.orEmpty().lowercase(Locale.US)
                val remoteDir = when {
                    parentName.contains("crash") -> "crash"
                    else -> "regular"
                }

                SupabaseUploadWorker.enqueueExistingPayload(
                    context = context,
                    cfg = cfg,
                    file = file,
                    remoteDir = remoteDir,
                    contentType = if (file.name.endsWith(".gz")) "application/gzip" else "application/octet-stream",
                    upsert = false
                )
            }.onFailure { t ->
                Log.w(TAG, "Failed to enqueue pending file=${file.name}: ${t.message}")
            }
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
        private const val TAG = "SupabaseUploadRcvr"

        /** Matches SupabaseCrashLogStore DIR_PENDING root. */
        private const val PENDING_DIR = "pending_uploads/supabase"

        private const val ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED"
    }
}
