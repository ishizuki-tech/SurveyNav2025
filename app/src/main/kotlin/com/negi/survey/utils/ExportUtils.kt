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
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper for exporting survey JSON and recorded voice into a common
 * "exports" directory that GitHubUploadWorker (or similar) can upload
 * to the SurveyExports repo.
 *
 * Layout:
 *   <external-or-internal>/exports/
 *     └─ voice/
 *          ├─ voice_<...>_YYYYMMDD_HHmmss.wav
 *          └─ voice_<...>_YYYYMMDD_HHmmss.meta.json
 */
object ExportUtils {

    private const val TAG = "ExportUtils"
    private const val EXPORT_DIR_NAME = "exports"
    private const val VOICE_SUBDIR_NAME = "voice"
    private const val META_SUFFIX = ".meta.json"

    /**
     * Returns the base directory for export files.
     *
     * - Uses app-specific external files dir when available.
     * - On device, this lives under:
     *   Android/data/<package>/files/exports
     */
    fun getExportBaseDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val exportDir = File(base, EXPORT_DIR_NAME)
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            Log.w(TAG, "getExportBaseDir: failed to mkdirs() for ${exportDir.absolutePath}")
        }
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
        if (!voiceDir.exists() && !voiceDir.mkdirs()) {
            Log.w(TAG, "getVoiceExportDir: failed to mkdirs() for ${voiceDir.absolutePath}")
        }
        return voiceDir
    }

    /**
     * Copy the recorded voice file into the export voice directory and
     * return the new [File].
     *
     * Flow:
     * - Validate that [source] exists.
     * - Build a timestamped file name:
     *   voice_<surveyId>_<questionId>_YYYYMMDD_HHmmss.wav
     *   (segments are sanitized for file-system safety).
     * - Copy [source] into "exports/voice".
     * - Emit a sidecar JSON:
     *   voice_<...>_YYYYMMDD_HHmmss.meta.json
     *   with survey/question IDs for later lookup.
     */
    @Throws(IOException::class)
    fun exportRecordedVoice(
        context: Context,
        source: File,
        surveyId: String? = null,
        questionId: String? = null
    ): File {
        if (!source.exists()) {
            val msg = "Source audio file does not exist: ${source.absolutePath}"
            Log.e(TAG, msg)
            throw IOException(msg)
        }

        val voiceDir = getVoiceExportDir(context)

        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val rawPrefix = buildString {
            append("voice")
            if (!surveyId.isNullOrBlank()) append("_$surveyId")
            if (!questionId.isNullOrBlank()) append("_$questionId")
        }
        val safePrefix = rawPrefix.sanitizeForFileName().ifBlank { "voice" }

        // Always export as .wav for consistency with DoneScreen scan.
        val targetName = "${safePrefix}_$time.wav"
        val target = File(voiceDir, targetName)

        Log.d(
            TAG,
            "exportRecordedVoice: copying ${source.absolutePath} -> ${target.absolutePath}"
        )

        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "exportRecordedVoice: copy failed", e)
            throw e
        }

        Log.d(TAG, "exportRecordedVoice: done, size=${target.length()} bytes")

        // Write sidecar meta JSON for later lookup in DoneScreen.
        writeVoiceMeta(
            dir = voiceDir,
            wavName = target.name,
            surveyId = surveyId,
            questionId = questionId
        )

        return target
    }

    /**
     * Write a small sidecar JSON file for a voice recording:
     *
     *   voice_<...>.wav  ->  voice_<...>.meta.json
     *
     * Payload shape:
     * {
     *   "file_name": "voice_....wav",
     *   "survey_id": "...",
     *   "question_id": "...",
     *   "created_at": "2025-11-25T10:20:30Z"
     * }
     */
    private fun writeVoiceMeta(
        dir: File,
        wavName: String,
        surveyId: String?,
        questionId: String?
    ) {
        val base = wavName.substringBeforeLast('.', wavName)
        val metaFile = File(dir, base + META_SUFFIX)

        val createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .format(Date())

        val json = buildString {
            append("{\n")
            append("  \"file_name\": \"")
                .append(wavName.escapeJson())
                .append("\",\n")
            if (!surveyId.isNullOrBlank()) {
                append("  \"survey_id\": \"")
                    .append(surveyId.escapeJson())
                    .append("\",\n")
            }
            if (!questionId.isNullOrBlank()) {
                append("  \"question_id\": \"")
                    .append(questionId.escapeJson())
                    .append("\",\n")
            }
            append("  \"created_at\": \"")
                .append(createdAt.escapeJson())
                .append("\"\n")
            append("}\n")
        }

        runCatching {
            metaFile.writeText(json, Charsets.UTF_8)
            Log.d(TAG, "writeVoiceMeta: wrote ${metaFile.absolutePath}")
        }.onFailure { e ->
            Log.w(TAG, "writeVoiceMeta: failed, ignoring", e)
        }
    }

    /**
     * Simple file-name sanitizer that replaces non [A-Za-z0-9_-] chars with '_'.
     */
    private fun String.sanitizeForFileName(): String {
        return replace(Regex("""[^\w\-]+"""), "_").trim('_')
    }

    /**
     * Minimal JSON string escaper for meta payload.
     */
    private fun String.escapeJson(): String =
        buildString(length + 8) {
            for (ch in this@escapeJson) {
                when (ch) {
                    '\"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
}
