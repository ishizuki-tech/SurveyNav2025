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
 *  BroadcastReceiver that re-enqueues pending GitHub uploads after reboot
 *  or app update. Designed to be safe across Direct Boot timing.
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isRelevantAction(action)) return

        // Use goAsync() to reduce ANR risk if there are many files.
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        EXECUTOR.execute {
            try {
                handleReschedule(appContext, action)
            } catch (t: Throwable) {
                Log.w(TAG, "handleReschedule crashed: ${t.message}", t)
            } finally {
                // Always finish to avoid leaking the broadcast.
                runCatching { pendingResult.finish() }
            }
        }
    }

    private fun handleReschedule(appContext: Context, action: String) {
        val isLockedBoot =
            action == ACTION_LOCKED_BOOT_COMPLETED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

        // If locked boot: credential-protected storage may be unavailable.
        // We can still run without crashing, but we likely won't see pending files
        // unless they were written into device-protected storage.
        val storageContext = if (isLockedBoot) {
            appContext.createDeviceProtectedStorageContext()
        } else {
            appContext
        }

        val cfg = GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            token = BuildConfig.GH_TOKEN,
            branch = BuildConfig.GH_BRANCH,
            pathPrefix = BuildConfig.GH_PATH_PREFIX
        )

        if (!cfg.isUsable()) {
            Log.d(TAG, "Skip reschedule: missing GitHub credentials (action=$action).")
            return
        }

        val dir = File(storageContext.filesDir, PENDING_DIR)

        if (!dir.exists() || !dir.isDirectory) {
            Log.d(TAG, "No pending dir (action=$action) path=${dir.absolutePath}")
            if (isLockedBoot) {
                Log.d(
                    TAG,
                    "Locked-boot context used. If you need early reschedule, write pending payloads to device-protected storage."
                )
            }
            return
        }

        val files = dir.listFiles()?.asSequence()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.toList()
            ?: emptyList()

        if (files.isEmpty()) {
            Log.d(TAG, "No pending files (action=$action)")
            return
        }

        // Basic throttling log: helps diagnose pathological loops.
        val newest = files.maxOfOrNull { it.lastModified() } ?: 0L
        Log.d(
            TAG,
            "Rescheduling ${files.size} pending uploads (action=$action, newest=${newest})"
        )

        var ok = 0
        var fail = 0

        for (file in files) {
            val name = file.name
            runCatching {
                // IMPORTANT: pass appContext to WorkManager enqueuing.
                GitHubUploadWorker.enqueueExistingPayload(appContext, cfg, file)
                ok++
            }.onFailure { t ->
                fail++
                Log.w(TAG, "Failed to enqueue pending file=$name: ${t.message}", t)
            }
        }

        Log.d(TAG, "Reschedule done (ok=$ok, fail=$fail, action=$action)")
    }

    private fun GitHubUploader.GitHubConfig.isUsable(): Boolean {
        // Keep this strict: if token is blank, no scheduling.
        return owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank()
    }

    private fun isRelevantAction(action: String): Boolean = when (action) {
        Intent.ACTION_BOOT_COMPLETED -> true
        Intent.ACTION_MY_PACKAGE_REPLACED -> true
        ACTION_LOCKED_BOOT_COMPLETED -> true
        else -> false
    }

    private companion object {
        private const val TAG = "UploadRescheduleRcvr"
        private const val PENDING_DIR = "pending_uploads"

        private const val ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED"

        private val EXECUTOR = Executors.newSingleThreadExecutor { r ->
            Thread(r, "UploadRescheduleReceiver").apply { isDaemon = true }
        }.also {
            // Best-effort: avoid executor thread hanging forever on some OEM weirdness.
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching {
                    it.shutdown()
                    it.awaitTermination(250, TimeUnit.MILLISECONDS)
                }
            })
        }
    }
}
