/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseUploadRescheduleReceiver.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Reschedules pending Supabase uploads on:
 *   - BOOT_COMPLETED / LOCKED_BOOT_COMPLETED
 *   - MY_PACKAGE_REPLACED
 *
 *  Robustness upgrades:
 *   - Scan multiple pending roots (legacy + current).
 *   - Route remoteDir/contentType by file name/extension.
 *   - Avoid 20MB guard mismatch by passing maxBytesHint ~ file size.
 *   - Use BuildConfig pathPrefix consistently.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.negi.survey.BuildConfig
import java.io.File
import java.util.Locale
import kotlin.math.max

class SupabaseUploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isRelevantAction(action)) return

        val storageContext = when {
            action == ACTION_LOCKED_BOOT_COMPLETED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                context.createDeviceProtectedStorageContext()
            else -> context
        }

        val prefix = BuildConfig.SUPABASE_LOG_PATH_PREFIX
            .trim()
            .trim('/')
            .ifBlank { DEFAULT_PREFIX }

        val cfg = SupabaseUploader.SupabaseConfig(
            supabaseUrl = BuildConfig.SUPABASE_URL.trim(),
            anonKey = BuildConfig.SUPABASE_ANON_KEY.trim(),
            bucket = BuildConfig.SUPABASE_LOG_BUCKET.trim(),
            pathPrefix = prefix,
            maxRawBytesHint = DEFAULT_MAX_BYTES_HINT
        )

        if (cfg.supabaseUrl.isBlank() || cfg.anonKey.isBlank() || cfg.bucket.isBlank()) {
            Log.d(TAG, "Skip reschedule: missing Supabase credentials.")
            return
        }

        val roots = pendingRoots(storageContext)
        if (roots.isEmpty()) {
            Log.d(TAG, "No pending roots found for action=$action")
            return
        }

        val files = roots
            .flatMap { root ->
                if (!root.exists() || !root.isDirectory) emptyList()
                else root.walkTopDown().filter { it.isFile && it.length() > 0L }.toList()
            }
            .distinctBy { it.absolutePath }

        if (files.isEmpty()) {
            Log.d(TAG, "No pending files for action=$action roots=${roots.joinToString { it.name }}")
            return
        }

        Log.d(
            TAG,
            "Rescheduling ${files.size} pending upload(s) for action=$action " +
                    "roots=${roots.joinToString { it.absolutePath }}"
        )

        files.sortedBy { it.lastModified() }.forEach { file ->
            runCatching {
                val route = classify(file)

                // IMPORTANT: pass a maxBytesHint that can accommodate large WAV.
                val maxBytesHint = max(DEFAULT_MAX_BYTES_HINT, file.length() + 1024L)

                Log.i(
                    TAG,
                    "Enqueue pending: name=${file.name} bytes=${file.length()} " +
                            "remoteDir=${route.remoteDir} contentType=${route.contentType} addDate=${route.addDateSubdir} " +
                            "root=${file.parentFile?.absolutePath}"
                )

                SupabaseUploadWorker.enqueueExistingPayload(
                    context = storageContext.applicationContext,
                    cfg = cfg,
                    file = file,
                    remoteDir = route.remoteDir,
                    contentType = route.contentType,
                    upsert = false,
                    userJwt = null,
                    maxBytesHint = maxBytesHint,
                    addDateSubdir = route.addDateSubdir,
                    connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
                    readTimeoutMs = DEFAULT_READ_TIMEOUT_MS
                )
            }.onFailure { t ->
                Log.w(TAG, "Failed to enqueue pending file=${file.absolutePath}: ${t.message}", t)
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

    /**
     * Pending roots we support:
     * - Legacy DoneScreen staging: filesDir/pending_uploads_supabase
     * - Newer structured staging: filesDir/pending_uploads/supabase
     */
    private fun pendingRoots(context: Context): List<File> {
        val roots = ArrayList<File>(2)

        // Legacy (your DoneScreen currently uses this).
        roots.add(File(context.filesDir, LEGACY_PENDING_ROOT))

        // Structured (crash store already uses this style).
        roots.add(File(context.filesDir, STRUCTURED_PENDING_ROOT))

        return roots.filter { it.exists() && it.isDirectory }
    }

    /**
     * Classify pending file -> remoteDir + contentType + date policy.
     *
     * Rules:
     * - *.wav -> voice (audio/wav), no date subdir (matches immediate path style).
     * - *.json -> exports (application/json), no date subdir (matches immediate path style).
     * - logcat_*.gz or *.log.gz -> diagnostics/logcat (application/gzip), date subdir ON.
     * - crash_*.gz or parent contains crash -> crash (application/gzip), date subdir ON.
     * - fallback -> regular (octet-stream), date subdir ON.
     */
    private fun classify(file: File): Route {
        val name = file.name.lowercase(Locale.US)
        val parent = file.parentFile?.name.orEmpty().lowercase(Locale.US)

        if (name.endsWith(".wav")) {
            return Route(remoteDir = "voice", contentType = "audio/wav", addDateSubdir = false)
        }

        if (name.endsWith(".json")) {
            return Route(remoteDir = "exports", contentType = "application/json; charset=utf-8", addDateSubdir = false)
        }

        val isGz = name.endsWith(".gz")
        val looksLogcat = name.startsWith("logcat_") || name.endsWith(".log.gz") || parent.contains("logcat")
        if (isGz && looksLogcat) {
            return Route(remoteDir = "diagnostics/logcat", contentType = "application/gzip", addDateSubdir = true)
        }

        val looksCrash = name.startsWith("crash_") || parent.contains("crash") || parent.contains("crashlogs")
        if (isGz && looksCrash) {
            return Route(remoteDir = "crash", contentType = "application/gzip", addDateSubdir = true)
        }

        // Heuristic for generic gz logs
        if (isGz) {
            return Route(remoteDir = "diagnostics", contentType = "application/gzip", addDateSubdir = true)
        }

        return Route(remoteDir = "regular", contentType = "application/octet-stream", addDateSubdir = true)
    }

    private data class Route(
        val remoteDir: String,
        val contentType: String,
        val addDateSubdir: Boolean
    )

    private companion object {
        private const val TAG = "SupabaseUploadRcvr"

        private const val DEFAULT_PREFIX = "surveyapp"

        private const val DEFAULT_MAX_BYTES_HINT = 20_000_000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 20_000
        private const val DEFAULT_READ_TIMEOUT_MS = 120_000

        private const val LEGACY_PENDING_ROOT = "pending_uploads_supabase"
        private const val STRUCTURED_PENDING_ROOT = "pending_uploads/supabase"

        private const val ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED"
    }
}
