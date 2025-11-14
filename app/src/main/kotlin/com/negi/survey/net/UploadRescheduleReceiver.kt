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
 *  BroadcastReceiver that automatically re-enqueues any pending GitHub
 *  uploads after system reboot or app update. Ensures reliability of
 *  background data delivery even after lifecycle disruptions.
 *
 *  Triggered by:
 *   • BOOT_COMPLETED — when the device finishes booting
 *   • LOCKED_BOOT_COMPLETED — for direct-boot aware apps (API 24+)
 *   • MY_PACKAGE_REPLACED — after app reinstall or update
 *
 *  For each JSON file in `/files/pending_uploads/`, the receiver enqueues
 *  a [GitHubUploadWorker] to handle upload with WorkManager.
 *
 *  Notes:
 *   • Worker deduplication is handled via `enqueueUniqueWork(..., KEEP)`.
 *   • This receiver should be registered in the manifest with:
 *       <receiver
 *           android:name=".net.UploadRescheduleReceiver"
 *           android:enabled="true"
 *           android:exported="true"
 *           android:directBootAware="true">
 *           <intent-filter>
 *               <action android:name="android.intent.action.BOOT_COMPLETED" />
 *               <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
 *               <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
 *           </intent-filter>
 *       </receiver>
 * =====================================================================
 */

package com.negi.survey.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.negi.survey.BuildConfig
import java.io.File

/**
 * Receives system-level broadcasts related to app restarts or device reboots,
 * and automatically reschedules all pending upload tasks.
 *
 * ### Responsibilities
 * - Detects BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, and MY_PACKAGE_REPLACED events.
 * - Loads persistent payloads from `/files/pending_uploads`.
 * - Re-enqueues each pending upload through [GitHubUploadWorker].
 *
 * ### Behavior
 * - Gracefully ignores invalid or missing GitHub credentials.
 * - Processes files independently to avoid single-point failures.
 * - Compatible with Direct Boot (Android 7.0+) when declared `directBootAware`.
 *
 * @see GitHubUploadWorker
 */
class UploadRescheduleReceiver : BroadcastReceiver() {

    /**
     * Called when the system sends a matching broadcast (boot, update, etc.).
     * Automatically triggers upload rescheduling for stored pending files.
     *
     * @param context Application context supplied by the system.
     * @param intent Intent describing the received system broadcast.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Accept only relevant broadcasts
        val handled = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> true
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> true
            else -> false
        }
        if (!handled) return

        // ------------------------------------------------------------------
        // 🔧 Build GitHub config from BuildConfig (populated via Gradle fields)
        // ------------------------------------------------------------------
        val cfg = GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            token = BuildConfig.GH_TOKEN,
            branch = BuildConfig.GH_BRANCH,
            pathPrefix = BuildConfig.GH_PATH_PREFIX
        )

        // Skip if credentials are invalid (safe no-op)
        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) return

        // ------------------------------------------------------------------
        // 📂 Scan for pending payloads in app-internal storage
        // ------------------------------------------------------------------
        val dir = File(context.filesDir, PENDING_DIR)
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) return

        // ------------------------------------------------------------------
        // 🚀 Enqueue each pending upload via WorkManager
        // ------------------------------------------------------------------
        files.forEach { file ->
            runCatching {
                GitHubUploadWorker.enqueueExistingPayload(context, cfg, file)
            }.onFailure {
                // Each file handled independently; no crash propagation.
                // (Optional) Integrate logging here if using Timber or internal logger.
            }
        }
    }

    private companion object {
        /** Directory under `/files/` containing pending upload JSON payloads. */
        const val PENDING_DIR = "pending_uploads"
    }
}
