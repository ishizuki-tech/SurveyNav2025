/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: ExportUtils.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max

/**
 * Helper for exporting survey JSON and recorded voice into a common
 * "exports" directory that GitHubUploadWorker (or similar) can upload.
 *
 * Layout:
 *   <external-or-internal>/exports/
 *     └─ voice/
 *          ├─ voice_<...>_YYYYMMDD_HHmmss_SSS.wav
 *          └─ voice_<...>_YYYYMMDD_HHmmss_SSS.meta.json
 *
 * Design goals:
 * - Prefer app-scoped external storage when available (no runtime permission).
 * - Keep file naming deterministic and safe across file systems.
 * - Avoid partially-written artifacts with best-effort atomic writes.
 * - Provide rich sidecar metadata for later debugging / verification / upload.
 */
object ExportUtils {

    private const val TAG = "ExportUtils"

    private const val EXPORT_DIR_NAME = "exports"
    private const val VOICE_SUBDIR_NAME = "voice"
    private const val META_SUFFIX = ".meta.json"

    /** Standard PCM WAV header is 44 bytes. */
    private const val WAV_HEADER_BYTES = 44L

    /** Maximum length for each ID segment used in file naming. */
    private const val MAX_SEGMENT_LEN = 48

    /**
     * Conservative cap for the full file name (including extension).
     * Many FS allow 255 bytes; keeping lower reduces cross-device surprises.
     */
    private const val MAX_FILE_NAME_LEN = 180

    /** Stream buffer size for file IO. */
    private const val IO_BUFFER_BYTES = 64 * 1024

    /**
     * Limit how much of the file we scan when parsing WAV chunks.
     * Prevents pathological scans. Recordings should be small anyway.
     */
    private const val WAV_PARSE_SCAN_LIMIT_BYTES = 2L * 1024L * 1024L // 2MB

    /** Delete stale temp files older than this (best-effort). */
    private const val STALE_TEMP_AGE_MS = 24L * 60L * 60L * 1000L // 24h

    /**
     * Debug logging switch.
     *
     * Recommended:
     *   ExportUtils.debugEnabled = BuildConfig.DEBUG
     */
    @Volatile
    var debugEnabled: Boolean = false

    /**
     * Session identifier (process lifetime). Useful for correlating multi-file exports.
     */
    private val sessionId: String = UUID.randomUUID().toString()

    private val rng: SecureRandom = SecureRandom()
    private val nameLock = Any()
    private val installIdLock = Any()

    /**
     * Time format for file naming (UTC). Milliseconds reduce collision risk.
     */
    private val FILE_TS_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Time format for meta JSON (UTC).
     */
    private val META_TS_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the base directory for export files.
     *
     * Storage resolution:
     * - Uses app-specific external files dir when available.
     * - Falls back to internal files dir otherwise.
     */
    fun getExportBaseDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val exportDir = File(base, EXPORT_DIR_NAME)
        ensureDirOrThrow(exportDir, "getExportBaseDir")
        return exportDir
    }

    /**
     * Returns the directory for exported voice files:
     *
     *   getExportBaseDir(context)/voice
     */
    fun getVoiceExportDir(context: Context): File {
        val base = getExportBaseDir(context)
        val voiceDir = File(base, VOICE_SUBDIR_NAME)
        ensureDirOrThrow(voiceDir, "getVoiceExportDir")
        return voiceDir
    }

    /**
     * Copy the recorded voice file into the export voice directory and return the new [File].
     *
     * Validation:
     * - [source] must exist and be a file.
     * - [source] must be larger than a minimal WAV header size.
     * - [source] must parse as WAV and contain a valid "fmt " + "data" chunk.
     *
     * Naming:
     * - voice_<surveyId>_<questionId>_YYYYMMDD_HHmmss_SSS.wav
     * - Each optional ID segment is sanitized and length-capped.
     * - Collision gets a suffix "-2", "-3", ...
     *
     * Atomicity:
     * - Copy into a random-named ".part" first in the same directory.
     * - Rename to final target when possible.
     * - If rename fails, copy ".part" into target, then delete ".part".
     *
     * Sidecar:
     * - Best-effort meta JSON next to the WAV (schema_version + sha256 + duration, etc).
     *
     * @throws IOException When validation fails or the copy cannot be completed.
     */
    @Throws(IOException::class)
    fun exportRecordedVoice(
        context: Context,
        source: File,
        surveyId: String? = null,
        questionId: String? = null
    ): File {
        validateSourceFileOrThrow(source)

        // Parse source WAV early to fail fast on corrupt/invalid files.
        val sourceWav = parseWavOrThrow(source)

        val voiceDir = getVoiceExportDir(context)

        // Best-effort cleanup: old .tmp/.part files (safe to ignore failures).
        runCatching { cleanupStaleTempFilesInternal(voiceDir, STALE_TEMP_AGE_MS) }

        val time = FILE_TS_FORMAT.format(Date())

        val safeSurvey = surveyId?.takeIf { it.isNotBlank() }?.sanitizeSegment()
        val safeQuestion = questionId?.takeIf { it.isNotBlank() }?.sanitizeSegment()

        val rawPrefix = buildString {
            append("voice")
            if (safeSurvey != null) append("_").append(safeSurvey)
            if (safeQuestion != null) append("_").append(safeQuestion)
        }

        val safePrefix = rawPrefix.sanitizeForFileName().ifBlank { "voice" }
        val baseName = "${safePrefix}_$time"

        val target: File = synchronized(nameLock) {
            makeUniqueFile(voiceDir, baseName, "wav")
        }

        val traceId = randomTokenHex(8)

        d("exportRecordedVoice[$traceId]: source=${source.name} size=${source.length()}")
        d("exportRecordedVoice[$traceId]: target=${target.name}")
        d("exportRecordedVoice[$traceId]: wav(source)=${sourceWav.shortDebug()}")

        try {
            val copyResult = copyFileAtomicAndHashSha256(source, target)

            // Sanity: size should match (unless filesystem does something weird).
            if (target.length() != source.length()) {
                throw IOException(
                    "exportRecordedVoice[$traceId]: size mismatch after export: " +
                            "source=${source.length()} target=${target.length()}"
                )
            }

            // Re-parse exported WAV to ensure it is valid post-copy.
            val outWav = parseWavOrThrow(target)

            if (target.length() <= WAV_HEADER_BYTES || outWav.dataBytes <= 0) {
                throw IOException("exportRecordedVoice[$traceId]: exported WAV failed sanity checks: ${target.absolutePath}")
            }

            val appInfo = getAppInfo(context)
            val deviceInfo = getDeviceInfo(context)

            // Best-effort sidecar meta JSON.
            writeVoiceMeta(
                dir = voiceDir,
                wavName = target.name,
                wavByteSize = target.length(),
                sha256Hex = copyResult.sha256Hex,
                traceId = traceId,
                sessionId = sessionId,
                installId = getOrCreateInstallId(context),
                appInfo = appInfo,
                deviceInfo = deviceInfo,
                sourceWavInfo = sourceWav,
                exportedWavInfo = outWav,
                surveyId = surveyId,
                questionId = questionId
            )

            d("exportRecordedVoice[$traceId]: done sha256=${copyResult.sha256Hex}")
            return target
        } catch (e: Exception) {
            // Prevent broken artifacts from confusing later upload steps.
            runCatching { if (target.exists()) target.delete() }
            throw e
        }
    }

    /**
     * Delete stale temp files (.tmp / .part) under voice export dir.
     * Returns how many files were deleted.
     *
     * This is safe to call any time. Failures are best-effort.
     */
    fun cleanupStaleTempFiles(context: Context, olderThanMs: Long = STALE_TEMP_AGE_MS): Int {
        val dir = getVoiceExportDir(context)
        return cleanupStaleTempFilesInternal(dir, olderThanMs)
    }

    /**
     * Debug helper: Return a short summary of the voice export directory.
     * (No file contents are read; only names + sizes + modified time.)
     */
    fun debugVoiceExportDirSummary(context: Context, limit: Int = 50): String {
        val dir = getVoiceExportDir(context)
        val files = (dir.listFiles()?.toList() ?: emptyList())
            .sortedByDescending { it.lastModified() }
            .take(limit.coerceAtLeast(1))

        return buildString {
            appendLine("VoiceExportDir: ${dir.absolutePath}")
            appendLine("session_id=$sessionId")
            appendLine("files=${files.size} (showing up to $limit)")
            for (f in files) {
                appendLine("- ${f.name}  size=${f.length()}  modified=${f.lastModified()}")
            }
        }
    }

    /**
     * Debug helper: Parse and validate a specific WAV file.
     * Returns a human-readable report.
     */
    fun debugValidateWav(file: File): String {
        return runCatching {
            val info = parseWavOrThrow(file)
            "OK: ${file.name} | ${info.shortDebug()}"
        }.getOrElse { e ->
            "FAIL: ${file.name} | ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // =========================================================================
    // Validation / WAV parsing
    // =========================================================================

    /**
     * Validate minimal file existence and size constraints.
     */
    @Throws(IOException::class)
    private fun validateSourceFileOrThrow(source: File) {
        if (!source.exists() || !source.isFile) {
            throw IOException("Source audio file does not exist: ${source.absolutePath}")
        }
        val len = source.length()
        if (len <= WAV_HEADER_BYTES) {
            throw IOException("Source audio file is too small or empty: ${source.absolutePath} (size=$len)")
        }
    }

    /**
     * Parsed WAV information (safe/useful fields only).
     */
    private data class WavInfo(
        val riffType: String,          // RIFF / RIFX / RF64
        val audioFormat: Int,          // 1=PCM, 3=float, 0xFFFE=extensible, etc.
        val numChannels: Int,
        val sampleRate: Int,
        val byteRate: Int,
        val bitsPerSample: Int,
        val dataBytes: Long,
        val durationMs: Long?,         // null when cannot be computed
        val isBigEndian: Boolean
    ) {
        fun shortDebug(): String {
            return "riff=$riffType be=$isBigEndian fmt=$audioFormat ch=$numChannels " +
                    "sr=$sampleRate br=$byteRate bps=$bitsPerSample data=$dataBytes durMs=${durationMs ?: -1}"
        }
    }

    /**
     * Parse WAV chunks and return [WavInfo]. Throws on invalid or unsupported structure.
     *
     * Requirements:
     * - Must contain "fmt " chunk with sane values.
     * - Must contain "data" chunk with positive size.
     *
     * Note:
     * - RF64 is partially supported via "ds64" (data size lookup when "data" chunk size is 0xFFFFFFFF).
     */
    @Throws(IOException::class)
    private fun parseWavOrThrow(file: File): WavInfo {
        FileInputStream(file).use { fis ->
            BufferedInputStream(fis, IO_BUFFER_BYTES).use { input ->
                val riffId = input.readAscii4OrThrow("riffId")
                val bigEndian = when (riffId) {
                    "RIFF" -> false
                    "RIFX" -> true
                    "RF64" -> false
                    else -> throw IOException("Invalid WAV: riffId=$riffId file=${file.name}")
                }

                // riff size (ignored)
                input.readU32OrThrow(bigEndian, "riffSize")

                val waveId = input.readAscii4OrThrow("waveId")
                if (waveId != "WAVE") {
                    throw IOException("Invalid WAV: missing WAVE header, got=$waveId file=${file.name}")
                }

                var fmtFound = false
                var dataFound = false

                var audioFormat = -1
                var numChannels = -1
                var sampleRate = -1
                var byteRate = -1
                var bitsPerSample = -1
                var dataBytes: Long = -1L

                // RF64 ds64 support (optional).
                var rf64DataSize64: Long? = null

                // Scan chunks, but cap the scan to avoid pathological files.
                val scanLimit = max(WAV_HEADER_BYTES, minOf(file.length(), WAV_PARSE_SCAN_LIMIT_BYTES))
                var scannedBytes = 12L

                while (scannedBytes < scanLimit) {
                    val chunkId = runCatching { input.readAscii4OrThrow("chunkId") }.getOrNull() ?: break
                    val chunkSizeU32 = runCatching { input.readU32OrThrow(bigEndian, "chunkSize") }.getOrNull() ?: break

                    scannedBytes += 8L

                    val chunkSize = chunkSizeU32

                    when (chunkId) {
                        "ds64" -> {
                            // RF64: ds64 holds 64-bit sizes. Minimum payload is 28 bytes:
                            // riffSize64(8), dataSize64(8), sampleCount64(8), tableLength(4)
                            if (chunkSize < 28L) {
                                throw IOException("Invalid RF64: ds64 chunk too small: $chunkSize file=${file.name}")
                            }

                            val riffSize64 = input.readU64OrThrow(bigEndian, "riffSize64")
                            val dataSize64 = input.readU64OrThrow(bigEndian, "dataSize64")
                            input.readU64OrThrow(bigEndian, "sampleCount64")
                            val tableLen = input.readU32OrThrow(bigEndian, "tableLength")

                            // We only need dataSize64.
                            if (riffId == "RF64" && dataSize64 > 0) {
                                rf64DataSize64 = dataSize64
                            }

                            // Skip rest of ds64 payload (including table entries if any).
                            val consumed = 8L + 8L + 8L + 4L
                            val remaining = chunkSize - consumed
                            if (remaining > 0) input.skipFullyOrThrow(remaining, "ds64Remainder")

                            d("parseWav: ds64 riffSize64=$riffSize64 dataSize64=$dataSize64 tableLen=$tableLen")
                        }

                        "fmt " -> {
                            if (chunkSize < 16L) {
                                throw IOException("Invalid WAV: fmt chunk too small: $chunkSize file=${file.name}")
                            }

                            audioFormat = input.readU16OrThrow(bigEndian, "audioFormat")
                            numChannels = input.readU16OrThrow(bigEndian, "numChannels")

                            val sr = input.readU32OrThrow(bigEndian, "sampleRate")
                            val br = input.readU32OrThrow(bigEndian, "byteRate")

                            // blockAlign
                            input.readU16OrThrow(bigEndian, "blockAlign")
                            bitsPerSample = input.readU16OrThrow(bigEndian, "bitsPerSample")

                            // Skip any remaining fmt payload.
                            val remaining = chunkSize - 16L
                            if (remaining > 0) input.skipFullyOrThrow(remaining, "fmtRemainder")

                            // Convert sr/br to Int with sanity.
                            if (sr <= 0L || sr > 384_000L) {
                                throw IOException("Invalid WAV: sampleRate=$sr file=${file.name}")
                            }
                            if (br <= 0L || br > Int.MAX_VALUE.toLong()) {
                                throw IOException("Invalid WAV: byteRate=$br file=${file.name}")
                            }
                            sampleRate = sr.toInt()
                            byteRate = br.toInt()

                            fmtFound = true

                            // Sanity checks
                            if (numChannels <= 0 || numChannels > 16) {
                                throw IOException("Invalid WAV: numChannels=$numChannels file=${file.name}")
                            }
                            if (bitsPerSample <= 0 || bitsPerSample > 64) {
                                throw IOException("Invalid WAV: bitsPerSample=$bitsPerSample file=${file.name}")
                            }

                            // Accept common formats; warn (debug) for unusual ones.
                            if (audioFormat != 1 && audioFormat != 3 && audioFormat != 0xFFFE) {
                                d("parseWav: unusual audioFormat=$audioFormat file=${file.name}")
                            }
                        }

                        "data" -> {
                            // RF64 can store 0xFFFFFFFF as placeholder and real size in ds64.
                            dataBytes = if (riffId == "RF64" && chunkSize == 0xFFFF_FFFFL) {
                                rf64DataSize64 ?: throw IOException("Invalid RF64: data size placeholder but ds64 missing file=${file.name}")
                            } else {
                                chunkSize
                            }

                            // Skip data without reading it.
                            if (chunkSize > 0) input.skipFullyOrThrow(chunkSize, "dataChunk")
                            dataFound = true
                        }

                        else -> {
                            // Skip unknown chunk payload.
                            if (chunkSize > 0) input.skipFullyOrThrow(chunkSize, "chunk($chunkId)")
                        }
                    }

                    scannedBytes += chunkSize

                    // RIFF chunks are word-aligned: if size is odd, there is a pad byte.
                    if (chunkSize % 2L == 1L) {
                        input.skipFullyOrThrow(1L, "padByte")
                        scannedBytes += 1L
                    }

                    if (fmtFound && dataFound) break
                }

                if (!fmtFound) throw IOException("Invalid WAV: missing fmt chunk file=${file.name}")
                if (!dataFound) throw IOException("Invalid WAV: missing data chunk file=${file.name}")
                if (dataBytes <= 0) throw IOException("Invalid WAV: dataBytes=$dataBytes file=${file.name}")

                val durationMs: Long? = if (byteRate > 0) {
                    (dataBytes * 1000L) / byteRate.toLong()
                } else {
                    null
                }

                return WavInfo(
                    riffType = riffId,
                    audioFormat = audioFormat,
                    numChannels = numChannels,
                    sampleRate = sampleRate,
                    byteRate = byteRate,
                    bitsPerSample = bitsPerSample,
                    dataBytes = dataBytes,
                    durationMs = durationMs,
                    isBigEndian = bigEndian
                )
            }
        }
    }

    // =========================================================================
    // App / Device metadata
    // =========================================================================

    private data class AppInfo(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long?
    )

    private data class DeviceInfo(
        val manufacturer: String?,
        val model: String?,
        val sdkInt: Int,
        val supportedAbis: List<String>,
        val androidIdHash: String?,     // optional
        val installId: String           // stable, non-PII
    )

    /**
     * Best-effort: read app version info.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun getAppInfo(context: Context): AppInfo {
        val pkg = context.packageName
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getPackageInfo(pkg, 0)
            }
            AppInfo(
                packageName = pkg,
                versionName = pi.versionName,
                versionCode = pi.longVersionCode
            )
        }.getOrElse {
            AppInfo(
                packageName = pkg,
                versionName = null,
                versionCode = null
            )
        }
    }

    /**
     * Best-effort: compute device identifiers with privacy in mind.
     * - installId: stable random per app install (stored under exports dir)
     * - androidIdHash: sha256(ANDROID_ID) if available (optional)
     */
    private fun getDeviceInfo(context: Context): DeviceInfo {
        val installId = getOrCreateInstallId(context)
        val androidIdHash = getAndroidIdHash(context)

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = (Build.SUPPORTED_ABIS?.toList() ?: emptyList()),
            androidIdHash = androidIdHash,
            installId = installId
        )
    }

    /**
     * Stable non-PII install id:
     * - Stored in exports/.install_id (hex string)
     */
    private fun getOrCreateInstallId(context: Context): String {
        synchronized(installIdLock) {
            val base = getExportBaseDir(context)
            val f = File(base, ".install_id")

            // Read existing
            if (f.exists() && f.isFile) {
                val s = runCatching { f.readText(Charsets.UTF_8).trim() }.getOrNull()
                if (!s.isNullOrBlank() && s.length >= 16) return s
            }

            // Create new
            val id = randomTokenHex(16) // 16 bytes -> 32 hex chars
            runCatching {
                writeTextAtomic(f, id + "\n")
            }.onFailure {
                // Even if writing fails, we can still use runtime id.
                d("getOrCreateInstallId: failed to persist install id: ${it.message}")
            }
            return id
        }
    }

    /**
     * Optional: hash ANDROID_ID with SHA-256. Returns null if unavailable.
     */
    private fun getAndroidIdHash(context: Context): String? {
        return runCatching {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: return null
            if (raw.isBlank()) return null
            sha256Hex(raw.toByteArray(Charsets.UTF_8))
        }.getOrNull()
    }

    // =========================================================================
    // Metadata writing
    // =========================================================================

    private data class CopyResult(
        val sha256Hex: String
    )

    /**
     * Write a sidecar JSON file for a voice recording:
     *   voice_<...>.wav  ->  voice_<...>.meta.json
     *
     * Failure policy:
     * - Best-effort. Failures are logged and ignored.
     */
    private fun writeVoiceMeta(
        dir: File,
        wavName: String,
        wavByteSize: Long,
        sha256Hex: String,
        traceId: String,
        sessionId: String,
        installId: String,
        appInfo: AppInfo,
        deviceInfo: DeviceInfo,
        sourceWavInfo: WavInfo,
        exportedWavInfo: WavInfo,
        surveyId: String?,
        questionId: String?
    ) {
        val base = wavName.substringBeforeLast('.', wavName)
        val metaFile = File(dir, base + META_SUFFIX)
        val createdAt = META_TS_FORMAT.format(Date())

        // Build JSON without trailing commas.
        val top = mutableListOf<String>()
        top += "\"schema_version\": 3"
        top += "\"trace_id\": \"${traceId.escapeJson()}\""
        top += "\"session_id\": \"${sessionId.escapeJson()}\""
        top += "\"install_id\": \"${installId.escapeJson()}\""
        top += "\"file_name\": \"${wavName.escapeJson()}\""
        top += "\"byte_size\": $wavByteSize"
        top += "\"sha256\": \"${sha256Hex.escapeJson()}\""

        if (!surveyId.isNullOrBlank()) top += "\"survey_id\": \"${surveyId.escapeJson()}\""
        if (!questionId.isNullOrBlank()) top += "\"question_id\": \"${questionId.escapeJson()}\""

        val appObj = buildString {
            append("{")
            val kv = mutableListOf<String>()
            kv += "\"package_name\": \"${appInfo.packageName.escapeJson()}\""
            if (!appInfo.versionName.isNullOrBlank()) kv += "\"version_name\": \"${appInfo.versionName.escapeJson()}\""
            if (appInfo.versionCode != null) kv += "\"version_code\": ${appInfo.versionCode}"
            append(kv.joinToString(", "))
            append("}")
        }
        top += "\"app\": $appObj"

        val devObj = buildString {
            append("{")
            val kv = mutableListOf<String>()
            if (!deviceInfo.manufacturer.isNullOrBlank()) kv += "\"manufacturer\": \"${deviceInfo.manufacturer.escapeJson()}\""
            if (!deviceInfo.model.isNullOrBlank()) kv += "\"model\": \"${deviceInfo.model.escapeJson()}\""
            kv += "\"sdk_int\": ${deviceInfo.sdkInt}"
            kv += "\"supported_abis\": [${deviceInfo.supportedAbis.joinToString(", ") { "\"${it.escapeJson()}\"" }}]"
            if (!deviceInfo.androidIdHash.isNullOrBlank()) kv += "\"android_id_hash\": \"${deviceInfo.androidIdHash.escapeJson()}\""
            append(kv.joinToString(", "))
            append("}")
        }
        top += "\"device\": $devObj"

        val wavObj = buildString {
            append("{")
            val kv = mutableListOf<String>()
            kv += "\"exported\": ${exportedWavInfo.toJsonObjectString()}"
            kv += "\"source\": ${sourceWavInfo.toJsonObjectString()}"
            append(kv.joinToString(", "))
            append("}")
        }
        top += "\"wav\": $wavObj"

        top += "\"created_at\": \"${createdAt.escapeJson()}\""

        val json = buildString {
            append("{\n")
            top.forEachIndexed { i, line ->
                append("  ").append(line)
                if (i != top.lastIndex) append(",")
                append("\n")
            }
            append("}\n")
        }

        runCatching {
            writeTextAtomic(metaFile, json)
            d("writeVoiceMeta[$traceId]: wrote ${metaFile.name}")
        }.onFailure { e ->
            Log.w(TAG, "writeVoiceMeta: failed, ignoring", e)
        }
    }

    private fun WavInfo.toJsonObjectString(): String {
        val kv = mutableListOf<String>()
        kv += "\"riff_type\": \"${riffType.escapeJson()}\""
        kv += "\"big_endian\": $isBigEndian"
        kv += "\"audio_format\": $audioFormat"
        kv += "\"channels\": $numChannels"
        kv += "\"sample_rate\": $sampleRate"
        kv += "\"byte_rate\": $byteRate"
        kv += "\"bits_per_sample\": $bitsPerSample"
        kv += "\"data_bytes\": $dataBytes"
        kv += "\"duration_ms\": ${durationMs ?: -1L}"
        return "{${kv.joinToString(", ")}}"
    }

    // =========================================================================
    // File operations (atomic-ish + hash)
    // =========================================================================

    /**
     * Ensure directory exists, or throw if it cannot be created or is not a directory.
     */
    @Throws(IOException::class)
    private fun ensureDirOrThrow(dir: File, caller: String) {
        if (dir.exists()) {
            if (!dir.isDirectory) {
                throw IOException("$caller: path exists but is not a directory: ${dir.absolutePath}")
            }
            return
        }
        if (!dir.mkdirs()) {
            throw IOException("$caller: failed to mkdirs() for ${dir.absolutePath}")
        }
    }

    /**
     * Create a unique file under [dir] using [baseName] and [ext].
     * Name is clamped to [MAX_FILE_NAME_LEN] to reduce filesystem surprises.
     */
    private fun makeUniqueFile(dir: File, baseName: String, ext: String): File {
        ensureDirOrThrow(dir, "makeUniqueFile")

        val cleanBase = baseName.sanitizeForFileName().ifBlank { "file" }

        // Reserve space for ".ext"
        val maxBaseLen = (MAX_FILE_NAME_LEN - (ext.length + 1)).coerceAtLeast(8)

        var index = 1
        while (true) {
            val suffix = if (index == 1) "" else "-$index"
            val baseMaxLenForThis = (maxBaseLen - suffix.length).coerceAtLeast(1)

            val trimmedBase = if (cleanBase.length <= baseMaxLenForThis) {
                cleanBase
            } else {
                cleanBase.take(baseMaxLenForThis)
            }

            val candidate = File(dir, "$trimmedBase$suffix.$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /**
     * Copy using a best-effort atomic strategy and compute SHA-256 for the exported bytes.
     *
     * Strategy:
     * - Write into a random ".part" sibling file.
     * - Try rename to final target.
     * - If rename fails, copy ".part" into target, then delete ".part".
     */
    @Throws(IOException::class)
    private fun copyFileAtomicAndHashSha256(source: File, target: File): CopyResult {
        val parent = target.parentFile
            ?: throw IOException("copyFileAtomicAndHashSha256: target parent is null: ${target.absolutePath}")
        ensureDirOrThrow(parent, "copyFileAtomicAndHashSha256")

        val part = createSiblingTempFile(parent, target.name, ".part")
        runCatching { if (part.exists()) part.delete() }

        val digest = MessageDigest.getInstance("SHA-256")

        // Write source -> part + hash
        try {
            FileInputStream(source).use { fis ->
                BufferedInputStream(fis, IO_BUFFER_BYTES).use { input ->
                    FileOutputStream(part, false).use { fos ->
                        BufferedOutputStream(fos, IO_BUFFER_BYTES).use { output ->
                            val buf = ByteArray(IO_BUFFER_BYTES)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                digest.update(buf, 0, n)
                                output.write(buf, 0, n)
                            }
                            output.flush()
                            runCatching { fos.fd.sync() }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "copyFileAtomicAndHashSha256: copy-to-part failed", e)
            runCatching { part.delete() }
            throw e
        }

        if (target.exists() && !target.delete()) {
            Log.w(TAG, "copyFileAtomicAndHashSha256: failed to delete existing ${target.absolutePath}")
        }

        // Rename fast path.
        if (part.renameTo(target)) {
            return CopyResult(sha256Hex = digest.digest().toHexLower())
        }

        Log.w(TAG, "copyFileAtomicAndHashSha256: rename failed, falling back to part->target copy")

        // Fallback: copy part -> target (hash already represents the bytes in part).
        try {
            FileInputStream(part).use { fis ->
                BufferedInputStream(fis, IO_BUFFER_BYTES).use { input ->
                    FileOutputStream(target, false).use { fos ->
                        BufferedOutputStream(fos, IO_BUFFER_BYTES).use { output ->
                            input.copyTo(output, IO_BUFFER_BYTES)
                            output.flush()
                            runCatching { fos.fd.sync() }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "copyFileAtomicAndHashSha256: fallback copy failed", e)
            throw e
        } finally {
            runCatching { part.delete() }
        }

        return CopyResult(sha256Hex = digest.digest().toHexLower())
    }

    /**
     * Write text atomically using a random sibling temp file to avoid concurrency collisions.
     */
    private fun writeTextAtomic(target: File, text: String) {
        val parent = target.parentFile
            ?: throw IllegalArgumentException("writeTextAtomic: target parent is null: ${target.absolutePath}")
        ensureDirOrThrow(parent, "writeTextAtomic")

        val tmp = createSiblingTempFile(parent, target.name, ".tmp")
        runCatching { if (tmp.exists()) tmp.delete() }

        try {
            FileOutputStream(tmp, false).use { fos ->
                BufferedOutputStream(fos, IO_BUFFER_BYTES).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                    runCatching { fos.fd.sync() }
                }
            }
        } catch (e: IOException) {
            runCatching { tmp.delete() }
            throw e
        }

        if (target.exists() && !target.delete()) {
            Log.w(TAG, "writeTextAtomic: failed to delete existing ${target.absolutePath}")
        }

        if (tmp.renameTo(target)) return

        Log.w(TAG, "writeTextAtomic: rename failed, falling back to direct write")
        // Fallback: direct write (non-atomic)
        target.writeText(text, Charsets.UTF_8)
        runCatching { tmp.delete() }
    }

    /**
     * Create a unique sibling temp file under [dir] based on [baseName].
     */
    private fun createSiblingTempFile(dir: File, baseName: String, suffix: String): File {
        val token = randomTokenHex(6)
        val safe = baseName.sanitizeForFileName().ifBlank { "tmp" }
        val trimmed = if (safe.length <= 64) safe else safe.take(64)
        return File(dir, "$trimmed.$token$suffix")
    }

    /**
     * Internal stale temp cleanup.
     */
    private fun cleanupStaleTempFilesInternal(dir: File, olderThanMs: Long): Int {
        val now = System.currentTimeMillis()
        val files = dir.listFiles() ?: return 0

        var deleted = 0
        for (f in files) {
            val n = f.name
            val isTemp = n.endsWith(".tmp") || n.endsWith(".part")
            if (!isTemp) continue

            val age = now - f.lastModified()
            if (age >= olderThanMs) {
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    deleted++
                }
            }
        }

        if (deleted > 0) d("cleanupStaleTempFiles: deleted=$deleted dir=${dir.name}")
        return deleted
    }

    // =========================================================================
    // Hash helpers
    // =========================================================================

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().toHexLower()
    }

    private fun randomTokenHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.toHexLower()
    }

    private fun ByteArray.toHexLower(): String {
        val out = CharArray(size * 2)
        val hex = "0123456789abcdef"
        var i = 0
        for (b in this) {
            val v = b.toInt() and 0xFF
            out[i++] = hex[v ushr 4]
            out[i++] = hex[v and 0x0F]
        }
        return String(out)
    }

    // =========================================================================
    // String helpers
    // =========================================================================

    /**
     * Convert an ID segment into a file-name-safe representation with a length cap.
     */
    private fun String.sanitizeSegment(): String {
        val safe = sanitizeForFileName()
        return if (safe.length <= MAX_SEGMENT_LEN) safe else safe.take(MAX_SEGMENT_LEN)
    }

    /**
     * File-name sanitizer that keeps ASCII [A-Za-z0-9_-] and replaces others with '_'.
     * Avoid using '\w' to reduce Unicode/locale-dependent surprises across file systems.
     */
    private fun String.sanitizeForFileName(): String {
        val s = replace(Regex("""[^A-Za-z0-9_-]+"""), "_")
        return s.trim('_')
    }

    /**
     * Minimal but safer JSON string escaper for meta payload.
     */
    private fun String.escapeJson(): String =
        buildString(this.length + 16) {
            for (ch in this@escapeJson) {
                when (ch) {
                    '\"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) {
                            append(String.format(Locale.US, "\\u%04x", ch.code))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
        }

    // =========================================================================
    // Binary readers (WAV parsing)
    // =========================================================================

    @Throws(IOException::class)
    private fun BufferedInputStream.readAscii4OrThrow(label: String): String {
        val b = ByteArray(4)
        val n = read(b)
        if (n != 4) throw IOException("EOF reading $label")
        return String(b, Charsets.US_ASCII)
    }

    @Throws(IOException::class)
    private fun BufferedInputStream.readU16OrThrow(bigEndian: Boolean, label: String): Int {
        val b0 = read()
        val b1 = read()
        if (b0 < 0 || b1 < 0) throw IOException("EOF reading $label")
        return if (!bigEndian) {
            (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
        } else {
            ((b0 and 0xFF) shl 8) or (b1 and 0xFF)
        }
    }

    /**
     * Read unsigned 32-bit as Long in range [0, 4294967295].
     */
    @Throws(IOException::class)
    private fun BufferedInputStream.readU32OrThrow(bigEndian: Boolean, label: String): Long {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw IOException("EOF reading $label")

        val v = if (!bigEndian) {
            (b0.toLong() and 0xFF) or
                    ((b1.toLong() and 0xFF) shl 8) or
                    ((b2.toLong() and 0xFF) shl 16) or
                    ((b3.toLong() and 0xFF) shl 24)
        } else {
            ((b0.toLong() and 0xFF) shl 24) or
                    ((b1.toLong() and 0xFF) shl 16) or
                    ((b2.toLong() and 0xFF) shl 8) or
                    (b3.toLong() and 0xFF)
        }

        return v and 0xFFFF_FFFFL
    }

    /**
     * Read unsigned 64-bit as Long (best-effort). For RF64 ds64 only.
     */
    @Throws(IOException::class)
    private fun BufferedInputStream.readU64OrThrow(bigEndian: Boolean, label: String): Long {
        val b = ByteArray(8)
        val n = read(b)
        if (n != 8) throw IOException("EOF reading $label")

        var v = 0L
        if (!bigEndian) {
            for (i in 7 downTo 0) {
                v = (v shl 8) or (b[i].toLong() and 0xFF)
            }
        } else {
            for (i in 0..7) {
                v = (v shl 8) or (b[i].toLong() and 0xFF)
            }
        }
        return v
    }

    @Throws(IOException::class)
    private fun BufferedInputStream.skipFullyOrThrow(bytes: Long, label: String) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                val one = read()
                if (one < 0) throw IOException("EOF skipping $label (remaining=$remaining)")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    // =========================================================================
    // Logging
    // =========================================================================

    private fun d(msg: String) {
        if (debugEnabled) Log.d(TAG, msg)
    }
}
