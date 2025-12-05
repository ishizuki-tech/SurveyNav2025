/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: DoneScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Final survey screen that summarizes answers and follow-up questions,
 *  renders them in a simple vertical layout, and exposes export actions:
 *
 *   • Local JSON export (auto-save to device storage).
 *   • Immediate GitHub upload (online now, JSON + voice WAV).
 *   • Deferred GitHub upload via WorkManager (runs when online).
 *   • Deferred upload of recorded voice WAV files via WorkManager.
 *
 *  Storage model (JSON auto-save):
 *   • API 29+ : MediaStore Downloads/SurveyNav (visible in Files app).
 *   • API 28- : app-specific external Downloads/SurveyNav (no runtime perms).
 *
 *  Notes:
 *   • JSON is built manually with a small escaper to keep dependencies light.
 *   • Newlines are not preprocessed; escapeJson() handles them correctly.
 *   • JSON payload now includes:
 *       - "answers": question/answer pairs with optional "audio" array.
 *       - "voice_files": array with objects:
 *           {
 *             "file": "...wav",
 *             "survey_id": "...",
 *             "question_id": "...",
 *             "question": "...",
 *             "answer": "..."
 *           }
 * =====================================================================
 */

package com.negi.survey.screens

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.utils.ExportUtils
import com.negi.survey.vm.SurveyViewModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Final survey screen that summarizes all collected answers and follow-ups.
 *
 * Responsibilities:
 *  - Read the current questions, answers, and follow-ups from [SurveyViewModel].
 *  - Render a human-readable summary of the interview session.
 *  - Build a JSON export payload covering answers + follow-ups + voice files.
 *  - Provide optional GitHub upload and deferred upload via WorkManager.
 *  - Optionally auto-save the JSON to device storage on first composition.
 *  - Show and upload recorded voice WAV files exported via [ExportUtils].
 *
 * The screen is UI-only: it does not mutate the survey graph and only calls
 * back through [onRestart] when the user wants to restart the flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoneScreen(
    vm: SurveyViewModel,
    onRestart: () -> Unit,
    gitHubConfig: GitHubUploader.GitHubConfig? = null,
    autoSaveToDevice: Boolean = false
) {
    val questions by vm.questions.collectAsState(initial = emptyMap())
    val answers by vm.answers.collectAsState(initial = emptyMap())
    val followups by vm.followups.collectAsState(initial = emptyMap())

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uploading = remember { mutableStateOf(false) }
    val context = LocalContext.current

    /**
     * Snapshot of exported voice files and their metadata so the UI can show
     * them and refresh the list after uploads.
     */
    val voiceFilesState = remember { mutableStateOf<List<VoiceFileInfo>>(emptyList()) }

    // Scan voice files (and their meta JSON) once when the screen is first composed.
    LaunchedEffect(Unit) {
        val files = withContext(Dispatchers.IO) {
            scanVoiceFiles(context)
        }
        voiceFilesState.value = files
    }

    val voiceFiles = voiceFilesState.value

    /**
     * Build a compact JSON export representing:
     *  - "answers": per-node question/answer pairs (+ optional "audio" array).
     *  - "followups": per-node arrays of follow-up question/answer pairs.
     *  - "voice_files": objects containing file + survey/question mapping.
     */
    val jsonText = remember(questions, answers, followups, voiceFiles) {
        // Pre-compute mapping from questionId to a list of voice files.
        val voiceByQuestionId: Map<String, List<VoiceFileInfo>> =
            voiceFiles
                .mapNotNull { info ->
                    val qId = info.questionId ?: return@mapNotNull null
                    qId to info
                }
                .groupBy({ it.first }, { it.second })

        buildString {
            append("{\n")

            // answers with optional audio array
            append("  \"answers\": {\n")
            val qEntries = questions.entries.toList()
            qEntries.forEachIndexed { idx, (id, q) ->
                val a = answers[id].orEmpty()
                val audioList = voiceByQuestionId[id].orEmpty()

                append("    \"")
                    .append(escapeJson(id))
                    .append("\": {\n")
                append("      \"question\": \"")
                    .append(escapeJson(q))
                    .append("\",\n")
                append("      \"answer\": \"")
                    .append(escapeJson(a))
                    .append("\"")

                if (audioList.isNotEmpty()) {
                    append(",\n")
                    append("      \"audio\": [\n")
                    audioList.forEachIndexed { j, info ->
                        append("        { \"file\": \"")
                            .append(escapeJson(info.file.name))
                            .append("\" }")
                        if (j != audioList.lastIndex) append(",")
                        append("\n")
                    }
                    append("      ]\n")
                } else {
                    append("\n")
                }

                append("    }")
                if (idx != qEntries.lastIndex) append(",")
                append("\n")
            }
            append("  },\n")

            // followups
            append("  \"followups\": {\n")
            val fEntries = followups.entries.toList()
            fEntries.forEachIndexed { i, (ownerId, list) ->
                append("    \"").append(escapeJson(ownerId)).append("\": [\n")
                list.forEachIndexed { j, fu ->
                    val fq = fu.question
                    val fa = fu.answer.orEmpty()
                    append("      { ")
                        .append("\"question\": \"").append(escapeJson(fq)).append("\", ")
                        .append("\"answer\": \"").append(escapeJson(fa)).append("\" ")
                        .append("}")
                    if (j != list.lastIndex) append(",")
                    append("\n")
                }
                append("    ]")
                if (i != fEntries.lastIndex) append(",")
                append("\n")
            }
            append("  },\n")

            // voice_files with question/answer attached.
            append("  \"voice_files\": [\n")
            voiceFiles.forEachIndexed { idx, info ->
                val qId = info.questionId
                val questionText = qId?.let { questions[it] }.orEmpty()
                val answerText = qId?.let { answers[it] }.orEmpty()

                append("    {\n")
                append("      \"file\": \"")
                    .append(escapeJson(info.file.name))
                    .append("\",\n")

                info.surveyId
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append("      \"survey_id\": \"")
                            .append(escapeJson(it))
                            .append("\",\n")
                    }

                qId
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append("      \"question_id\": \"")
                            .append(escapeJson(it))
                            .append("\",\n")
                    }

                append("      \"question\": \"")
                    .append(escapeJson(questionText))
                    .append("\",\n")
                append("      \"answer\": \"")
                    .append(escapeJson(answerText))
                    .append("\"\n")
                append("    }")
                if (idx != voiceFiles.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")

            append("}\n")
        }
    }

    // Auto-save JSON once if requested.
    val autoSavedOnce = remember { mutableStateOf(false) }
    LaunchedEffect(autoSaveToDevice, jsonText) {
        if (autoSaveToDevice && !autoSavedOnce.value) {
            val fileName = buildSurveyFileName()
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    saveJsonAutomatically(
                        context = context,
                        fileName = fileName,
                        content = jsonText
                    )
                }
                autoSavedOnce.value = true
                snackbar.showOnce("Saved to device: ${result.location}")
            }.onFailure { e ->
                snackbar.showOnce("Auto-save failed: ${e.message}")
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { TopAppBar(title = { Text("Done") }) },
        snackbarHost = { SnackbarHost(hostState = snackbar) }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Thanks! Here is your response summary.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))

            // Answers section.
            Text("■ Answers", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (questions.isEmpty()) {
                Text("No answers yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                questions.forEach { (id, q) ->
                    val a = answers[id].orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text("Q: $q", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "A: ${if (a.isBlank()) "(empty)" else a}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Divider()
                }
            }

            Spacer(Modifier.height(20.dp))

            // Follow-ups section.
            Text("■ Follow-ups", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (followups.isEmpty()) {
                Text("No follow-ups.", style = MaterialTheme.typography.bodyMedium)
            } else {
                followups.forEach { (ownerId, list) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Owner node: $ownerId",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        list.forEachIndexed { idx, fu ->
                            Text(
                                text = "${idx + 1}. ${fu.question}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val ans = fu.answer
                            if (!ans.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "   ↳ $ans",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    Divider()
                }
            }

            Spacer(Modifier.height(20.dp))

            // Recorded voice section.
            Text("■ Recorded voice files", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (voiceFiles.isEmpty()) {
                Text(
                    text = "No voice recordings exported.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "${voiceFiles.size} file(s) in exports/voice (pending upload).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                voiceFiles.take(3).forEach { info ->
                    Text(
                        text = "• ${info.file.name}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (voiceFiles.size > 3) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "… and more",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Export / upload actions (JSON + voice).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Immediate GitHub upload (JSON + voice WAV).
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            if (uploading.value) return@Button
                            scope.launch {
                                uploading.value = true
                                try {
                                    val cfg = gitHubConfig

                                    val (jsonResult, uploadedVoices) =
                                        withContext(Dispatchers.IO) {
                                            val fileName = buildSurveyFileName()

                                            // 1) Upload JSON summary.
                                            val jsonRes = GitHubUploader.uploadJson(
                                                cfg = cfg,
                                                relativePath = fileName,
                                                content = jsonText,
                                                message = "Upload $fileName"
                                            )

                                            // 2) Scan voice files again to get a fresh list.
                                            val currentVoiceFiles = scanVoiceFiles(context)

                                            var voiceCount = 0
                                            currentVoiceFiles.forEach { info ->
                                                val file = info.file
                                                val bytes = file.readBytes()
                                                GitHubUploader.uploadFile(
                                                    cfg = cfg,
                                                    relativePath = file.name,
                                                    bytes = bytes,
                                                    message = "Upload ${file.name}"
                                                )
                                                runCatching { file.delete() }
                                                voiceCount++
                                            }

                                            jsonRes to voiceCount
                                        }

                                    val label =
                                        jsonResult.fileUrl ?: jsonResult.commitSha ?: "(no URL)"
                                    if (uploadedVoices > 0) {
                                        snackbar.showOnce(
                                            "Uploaded JSON + $uploadedVoices voice file(s): $label"
                                        )
                                    } else {
                                        snackbar.showOnce("Uploaded JSON: $label")
                                    }

                                    // Refresh voice file list after deletion.
                                    val remaining = withContext(Dispatchers.IO) {
                                        scanVoiceFiles(context)
                                    }
                                    voiceFilesState.value = remaining
                                } catch (e: Exception) {
                                    snackbar.showOnce("Upload failed: ${e.message}")
                                } finally {
                                    uploading.value = false
                                }
                            }
                        },
                        enabled = !uploading.value
                    ) {
                        Text(if (uploading.value) "Uploading..." else "Upload now")
                    }
                }

                // Deferred GitHub upload for JSON via WorkManager.
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            val fileName = buildSurveyFileName()
                            GitHubUploadWorker.enqueue(
                                context = context,
                                cfg = gitHubConfig,
                                fileName = fileName,
                                jsonContent = jsonText
                            )
                            scope.launch {
                                snackbar.showOnce(
                                    "Upload scheduled (JSON, will run when online)."
                                )
                            }
                            if (voiceFiles.isEmpty()) {
                                scope.launch {
                                    snackbar.showOnce("No voice recordings to upload.")
                                }
                            } else {
                                voiceFiles.forEach { info ->
                                    GitHubUploadWorker.enqueueExistingPayload(
                                        context = context,
                                        cfg = gitHubConfig,
                                        file = info.file
                                    )
                                }
                                scope.launch {
                                    snackbar.showOnce(
                                        "Upload scheduled (${voiceFiles.size} voice file(s))."
                                    )
                                }
                            }
                        }
                    ) {
                        Text("Upload later")
                    }
                }

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onRestart) {
                    Text("Restart")
                }
            }

            Spacer(Modifier.height(12.dp))

            LaunchedEffect(Unit) {
                snackbar.showOnce("Thank you for your responses")
            }
        }
    }
}

/* ============================================================
 * File name helper
 * ============================================================ */

/**
 * Build a human-friendly survey file name such as:
 * "survey_2025-11-15_14-32-08.json".
 */
private fun buildSurveyFileName(prefix: String = "survey"): String {
    val now = LocalDateTime.now()
    val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    return "${prefix}_${stamp}.json"
}

/* ============================================================
 * Voice scan helpers
 * ============================================================ */

/**
 * UI-facing voice file description used by DoneScreen.
 *
 * - [file] is the actual WAV file under exports/voice.
 * - [surveyId] and [questionId] come from the sidecar meta JSON if present.
 */
private data class VoiceFileInfo(
    val file: File,
    val surveyId: String?,
    val questionId: String?
)

/**
 * Internal representation of voice meta loaded from the sidecar JSON.
 */
private data class VoiceMeta(
    val surveyId: String?,
    val questionId: String?
)

/**
 * Scan exported voice WAV files under exports/voice and attach metadata
 * from their sidecar .meta.json files when available.
 */
private fun scanVoiceFiles(context: android.content.Context): List<VoiceFileInfo> {
    val voiceDir = ExportUtils.getVoiceExportDir(context)
    if (!voiceDir.exists() || !voiceDir.isDirectory) return emptyList()

    val wavFiles = voiceDir.listFiles { f ->
        f.isFile &&
                !f.name.startsWith(".") &&
                f.name.lowercase().endsWith(".wav")
    } ?: return emptyList()

    return wavFiles
        .map { wav ->
            val meta = loadVoiceMeta(voiceDir, wav)
            VoiceFileInfo(
                file = wav,
                surveyId = meta?.surveyId,
                questionId = meta?.questionId
            )
        }
        .sortedByDescending { it.file.lastModified() }
}

/**
 * Load sidecar meta JSON for a WAV file, if present.
 *
 * Looks for:
 *   <baseName>.meta.json
 * in the same directory as the WAV file.
 */
private fun loadVoiceMeta(dir: File, wavFile: File): VoiceMeta? {
    val base = wavFile.name.substringBeforeLast('.', wavFile.name)
    val metaFile = File(dir, "$base.meta.json")
    if (!metaFile.exists()) return null

    return runCatching {
        val text = metaFile.readText(Charsets.UTF_8)
        val obj = JSONObject(text)
        val surveyId = obj.optString("survey_id", "").ifBlank { null }
        val questionId = obj.optString("question_id", "").ifBlank { null }
        VoiceMeta(surveyId = surveyId, questionId = questionId)
    }.getOrNull()
}

/* ============================================================
 * Auto-save helpers (no user interaction)
 * ============================================================ */

private data class SaveResult(
    val uri: Uri?,
    val file: File?,
    val location: String
)

private fun saveJsonAutomatically(
    context: android.content.Context,
    fileName: String,
    content: String
): SaveResult {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToDownloadsQPlus(context, fileName, content)
    } else {
        saveToAppExternalPreQ(context, fileName, content)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun saveToDownloadsQPlus(
    context: android.content.Context,
    fileName: String,
    content: String
): SaveResult {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
        put(
            MediaStore.Downloads.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/SurveyNav"
        )
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Failed to create download entry")
    try {
        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Failed to open output stream")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return SaveResult(
            uri = uri,
            file = null,
            location = "Downloads/SurveyNav/$fileName"
        )
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        throw t
    }
}

private fun saveToAppExternalPreQ(
    context: android.content.Context,
    fileName: String,
    content: String
): SaveResult {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: context.filesDir
    val dir = File(base, "SurveyNav").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content, Charsets.UTF_8)
    return SaveResult(
        uri = null,
        file = file,
        location = file.absolutePath
    )
}

/* ============================================================
 * Snackbar + JSON utilities
 * ============================================================ */

private suspend fun SnackbarHostState.showOnce(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

private fun escapeJson(s: String): String =
    buildString(s.length + 8) {
        s.forEach { ch ->
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
