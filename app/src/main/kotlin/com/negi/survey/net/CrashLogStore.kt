/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: CrashLogStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Captures crash reports (stacktrace + logcat tail) into local files.
 *  Intended flow:
 *   - On crash: write a gzipped crash bundle to internal storage.
 *   - On next launch: scan pending bundles and schedule WorkManager uploads.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import kotlin.system.exitProcess

private const val TAG = "CrashLogStore"

/**
 * Crash log capture + deferred upload scheduler.
 *
 * Design goals:
 * - Crash-time code must be fast and never throw.
 * - Upload happens later via WorkManager (GitHubUploadWorker).
 * - Stored artifacts are small (tail + gzip) to fit GitHub Contents API.
 */
object CrashLogStore {

    /** Directory under internal storage for pending crash bundles. */
    private const val DIR_PENDING_CRASH = "pending_uploads/crashlogs"

    /** GitHub destination under the configured prefix (pathPrefix). */
    private const val UPLOAD_SUBDIR = "diagnostics/crashlogs"

    /** Keep last N lines from logcat tail when using PID mode. */
    private const val LOGCAT_TAIL_LINES_PID = 2000

    /** Tail lines for fallback mode (no PID filter). */
    private const val LOGCAT_TAIL_LINES_FALLBACK = 3000

    /** Hard cap for uncompressed bytes written into the gzip stream. */
    private const val MAX_UNCOMPRESSED_BYTES = 850_000

    /** Hard cap for logcat bytes (pre-gzip). */
    private const val MAX_LOGCAT_BYTES = 700_000

    /** Max milliseconds we allow logcat capture to run. */
    private const val LOGCAT_MAX_MS = 700L

    /** Keep last N crash bundles locally. */
    private const val MAX_FILES_TO_KEEP = 80

    /** Max bundles to enqueue per startup. */
    private const val MAX_FILES_TO_ENQUEUE = 20

    private val installed = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)

    /**
     * Install an UncaughtExceptionHandler that writes a crash bundle to disk.
     *
     * IMPORTANT:
     * - This does NOT prevent the crash.
     * - It chains to the previous default handler after writing.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) {
            Log.d(TAG, "install() ignored (already installed).")
            return
        }

        val appContext = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Prevent re-entrancy storms (rare, but possible if handler itself crashes).
            if (!capturing.compareAndSet(false, true)) {
                runCatching { prev?.uncaughtException(thread, throwable) }
                    .onFailure { hardKill() }
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                runCatching { captureCrashToFile(appContext, thread, throwable) }
                    .onFailure { e -> Log.w(TAG, "Crash capture failed: ${e.message}", e) }
            } catch (t: Throwable) {
                Log.e(TAG, "Crash capture unexpected failure: ${t.message}", t)
            } finally {
                // Always delegate to preserve normal crash behavior / system reporting.
                try {
                    if (prev != null) {
                        prev.uncaughtException(thread, throwable)
                    } else {
                        hardKill()
                    }
                } catch (_: Throwable) {
                    hardKill()
                }
            }
        }

        Log.i(TAG, "CrashLogStore installed.")
    }

    /**
     * Schedule uploads for any pending crash bundles using the saved GitHub config.
     *
     * If no config is available (token not set), this is a no-op.
     */
    fun schedulePendingUploadsFromSavedConfig(context: Context) {
        val saved = GitHubDiagnosticsConfigStore.load(context) ?: run {
            Log.w(TAG, "No GitHub config saved; skip crashlog upload scheduling.")
            return
        }

        // Keep caller's base pathPrefix (if any) and append a stable subdir.
        val basePrefix = saved.pathPrefix.trim('/')
        val mergedPrefix = listOf(basePrefix, UPLOAD_SUBDIR)
            .filter { it.isNotBlank() }
            .joinToString("/")

        val uploadCfg = saved.copy(pathPrefix = mergedPrefix)
        schedulePendingUploads(context, uploadCfg)
    }

    /**
     * Schedule uploads for pending crash bundles using the provided config.
     *
     * This enqueues one WorkManager job per file, and the worker deletes the file on success.
     */
    fun schedulePendingUploads(context: Context, cfg: GitHubUploader.GitHubConfig) {
        if (!isValidGitHubConfig(cfg)) {
            Log.w(TAG, "GitHub config invalid; skip crashlog upload scheduling.")
            return
        }

        val dir = pendingDir(context).apply { mkdirs() }

        // Purge old files defensively.
        purgeOldCrashFiles(dir)

        val files = dir.listFiles { f ->
            f.isFile && f.length() > 0L && !f.name.startsWith(".")
        }?.toList().orEmpty()

        if (files.isEmpty()) {
            Log.d(TAG, "No pending crash bundles.")
            return
        }

        // Oldest first for stable behavior; cap count.
        files.sortedBy { it.lastModified() }
            .take(MAX_FILES_TO_ENQUEUE)
            .forEach { f ->
                GitHubUploadWorker.enqueueExistingPayload(
                    context = context.applicationContext,
                    cfg = cfg,
                    file = f
                )
            }

        Log.i(TAG, "Scheduled ${minOf(files.size, MAX_FILES_TO_ENQUEUE)} crash bundle upload(s).")
    }

    /**
     * Capture crash data into a gzip file under internal storage.
     *
     * The bundle contains:
     * - Device/app header
     * - Stacktrace
     * - logcat snapshot (best-effort, capped by time/bytes)
     */
    private fun captureCrashToFile(context: Context, thread: Thread, throwable: Throwable): File {
        val dir = pendingDir(context).apply { mkdirs() }
        purgeOldCrashFiles(dir)

        val pid = Process.myPid()
        val stampLocal = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "crash_${stampLocal}_pid${pid}.log.gz")

        FileOutputStream(outFile).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                var written = 0

                written = writeStringCapped(
                    out = gz,
                    s = "=== Crash Bundle ===\n\n",
                    written = written
                )

                written = writeStringCapped(
                    out = gz,
                    s = buildHeader(context = context, pid = pid, thread = thread),
                    written = written
                )

                written = writeStringCapped(
                    out = gz,
                    s = "\n=== Exception ===\n",
                    written = written
                )

                written = writeStringCapped(
                    out = gz,
                    s = Log.getStackTraceString(throwable) + "\n",
                    written = written
                )

                written = writeStringCapped(
                    out = gz,
                    s = "\n=== Logcat (best-effort) ===\n",
                    written = written
                )

                val logBytes = collectLogcatBytes(
                    pid = pid,
                    maxBytes = MAX_LOGCAT_BYTES,
                    maxMs = LOGCAT_MAX_MS
                )

                written = writeBytesCapped(
                    out = gz,
                    bytes = logBytes,
                    written = written
                )

                writeStringCapped(
                    out = gz,
                    s = "\n",
                    written = written
                )

                gz.flush()
            }
        }

        Log.e(TAG, "Crash bundle written: ${outFile.absolutePath} (${outFile.length()} bytes gz)")
        return outFile
    }

    /**
     * Build a short diagnostic header for correlation.
     */
    private fun buildHeader(context: Context, pid: Int, thread: Thread): String {
        val pkg = context.packageName
        val pm = context.packageManager

        val (versionName, versionCode) = runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }

            val vn = pi.versionName ?: "unknown"
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            vn to vc
        }.getOrElse { "unknown" to -1L }

        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        val abis = Build.SUPPORTED_ABIS?.joinToString(",") ?: "unknown"

        return buildString {
            appendLine("time_utc=$utc")
            appendLine("package=$pkg")
            appendLine("versionName=$versionName")
            appendLine("versionCode=$versionCode")
            appendLine("pid=$pid")
            appendLine("thread=${thread.name}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("abis=$abis")
        }
    }

    /**
     * Collect logcat output as bytes (best-effort).
     *
     * Strategy:
     * 1) Try PID-filtered snapshot across main/system/crash buffers.
     * 2) Fallback to a broader snapshot without PID filtering.
     *
     * Output is capped by time and bytes.
     */
    private fun collectLogcatBytes(pid: Int, maxBytes: Int, maxMs: Long): ByteArray {
        val primary = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "-b", "main",
            "-b", "system",
            "-b", "crash",
            "--pid=$pid",
            "-t", LOGCAT_TAIL_LINES_PID.toString()
        )

        val fallback = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "-b", "main",
            "-b", "system",
            "-b", "crash",
            "-t", LOGCAT_TAIL_LINES_FALLBACK.toString()
        )

        return runCatching { execAndReadCapped(primary, maxBytes, maxMs) }
            .recoverCatching { execAndReadCapped(fallback, maxBytes, maxMs) }
            .getOrElse { e ->
                ("(logcat capture failed: ${e.message})\n").toByteArray(Charsets.UTF_8)
            }
    }

    /**
     * Execute a command and read stdout up to [maxBytes] and [maxMs].
     *
     * redirectErrorStream(true) avoids deadlock if stderr fills up.
     */
    private fun execAndReadCapped(cmd: List<String>, maxBytes: Int, maxMs: Long): ByteArray {
        val start = SystemClock.elapsedRealtime()

        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        return try {
            proc.inputStream.use { input ->
                val out = ByteArrayOutputStream(minOf(maxBytes, 128 * 1024))
                val buf = ByteArray(16 * 1024)

                while (out.size() < maxBytes) {
                    if (SystemClock.elapsedRealtime() - start > maxMs) break

                    val remaining = maxBytes - out.size()
                    val n = input.read(buf, 0, minOf(buf.size, remaining))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }

                val bytes = out.toByteArray()
                if (bytes.isEmpty()) {
                    "(logcat empty or restricted)\n".toByteArray(Charsets.UTF_8)
                } else {
                    bytes
                }
            }
        } finally {
            // Best-effort cleanup. Do not block inside crash handler.
            runCatching { proc.destroy() }
        }
    }

    /**
     * Cap total uncompressed bytes written to gzip.
     */
    private fun writeStringCapped(out: GZIPOutputStream, s: String, written: Int): Int {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return writeBytesCapped(out, bytes, written)
    }

    /**
     * Cap total uncompressed bytes written to gzip.
     */
    private fun writeBytesCapped(out: GZIPOutputStream, bytes: ByteArray, written: Int): Int {
        if (written >= MAX_UNCOMPRESSED_BYTES) return written
        val remaining = MAX_UNCOMPRESSED_BYTES - written
        val toWrite = minOf(remaining, bytes.size)
        if (toWrite <= 0) return written
        out.write(bytes, 0, toWrite)
        return written + toWrite
    }

    /**
     * Keep storage from exploding.
     */
    private fun purgeOldCrashFiles(dir: File) {
        val all = dir.listFiles { f -> f.isFile && f.length() > 0L && !f.name.startsWith(".") }
            ?.toList()
            .orEmpty()

        if (all.size <= MAX_FILES_TO_KEEP) return

        val sorted = all.sortedBy { it.lastModified() }
        val toDelete = sorted.take(all.size - MAX_FILES_TO_KEEP)
        toDelete.forEach { f -> runCatching { f.delete() } }
    }

    /**
     * Get the internal pending directory.
     */
    private fun pendingDir(context: Context): File =
        File(context.filesDir, DIR_PENDING_CRASH)

    private fun isValidGitHubConfig(cfg: GitHubUploader.GitHubConfig): Boolean {
        if (cfg.token.isBlank()) return false
        if (cfg.owner.isBlank()) return false
        if (cfg.repo.isBlank()) return false
        return true
    }

    private fun hardKill() {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}
