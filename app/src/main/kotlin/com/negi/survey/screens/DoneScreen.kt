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
 *   • Immediate GitHub upload (online now, JSON + voice WAV + logcat snapshot).
 *   • Deferred GitHub upload via WorkManager (runs when online).
 *   • Optional Supabase upload (now / later) for the same payload set.
 *
 *  Key design rule:
 *   • JSON must be built from SurveyViewModel.recordedAudioRefs (logical manifest),
 *     not from file system scans, so repeated JSON exports remain stable even if
 *     WAV files were already uploaded and deleted.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.negi.survey.BuildConfig
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.SupabaseStorageUploader
import com.negi.survey.net.SupabaseUploadWorker
import com.negi.survey.utils.ExportUtils
import com.negi.survey.utils.buildSurveyFileName
import com.negi.survey.vm.Node
import com.negi.survey.vm.SurveyViewModel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REMOTE_EXPORT_DIR = "exports"
private const val REMOTE_VOICE_DIR = "voice"
private const val REMOTE_LOG_DIR = "diagnostics/logcat"

/** Cap logcat bytes to keep GitHub contents uploads safe-ish. */
private const val MAX_LOGCAT_BYTES = 850_000

private const val LOG_TAG = "DoneScreen"

private val LOGCAT_TAG_FILTERS = arrayOf(
    "WhisperEngine",
    "MainActivity",
    "CrashCapture",
    "GitHubUploadWorker",
    "GitHubUploader",
    "LiteRtLM",
    "LiteRtRepository",
    "SupabaseUploadWorker",
    "SupabaseStorageUp"
)

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
    val recordedAudioRefs by vm.recordedAudioRefs.collectAsState(initial = emptyMap())
    val surveyUuid by vm.surveyUuid.collectAsState()

    val exportedAtStamp = remember(surveyUuid) {
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        fmt.format(Date())
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val supabaseCfg = remember {
        SupabaseStorageUploader.configFromBuildConfig()
    }

    val audioRefsForRun = remember(recordedAudioRefs, surveyUuid) {
        vm.getAudioRefsForRun(surveyUuid)
    }

    val flatAudioRefsForRun = remember(recordedAudioRefs, surveyUuid) {
        vm.getAudioRefsForRunFlat(surveyUuid)
    }

    val expectedVoiceFileNames = remember(flatAudioRefsForRun) {
        flatAudioRefsForRun.map { it.fileName }.toSet()
    }

    val voiceFilesState = remember(surveyUuid) {
        mutableStateOf<List<File>>(emptyList())
    }

    LaunchedEffect(expectedVoiceFileNames, surveyUuid) {
        val files = withContext(Dispatchers.IO) {
            scanVoiceFilesByNames(context, expectedVoiceFileNames)
        }
        voiceFilesState.value = files
    }

    val voiceFilesForRun = voiceFilesState.value
    val nodesSnapshot: Map<String, Node> = remember(vm) { vm.nodes }

    val answerOwnerIds = remember(questions, answers, audioRefsForRun) {
        val ids = linkedSetOf<String>()
        ids.addAll(questions.keys)
        ids.addAll(answers.keys)
        ids.addAll(audioRefsForRun.keys)
        ids.toList().sorted()
    }

    val jsonText = remember(
        questions,
        answers,
        followups,
        audioRefsForRun,
        flatAudioRefsForRun,
        surveyUuid,
        exportedAtStamp,
        answerOwnerIds
    ) {
        val sortedFollowups = followups.toSortedMap()

        buildString {
            append("{\n")

            append("  \"survey_id\": \"")
                .append(escapeJson(surveyUuid))
                .append("\",\n")
            append("  \"exported_at\": \"")
                .append(escapeJson(exportedAtStamp))
                .append("\",\n")

            append("  \"answers\": {\n")
            answerOwnerIds.forEachIndexed { idx, id ->
                val q = questions[id] ?: nodesSnapshot[id]?.question ?: ""
                val a = answers[id].orEmpty()
                val audioList = audioRefsForRun[id].orEmpty()

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
                    audioList.forEachIndexed { j, ref ->
                        append("        { \"file\": \"")
                            .append(escapeJson(ref.fileName))
                            .append("\" }")
                        if (j != audioList.lastIndex) append(",")
                        append("\n")
                    }
                    append("      ]\n")
                } else {
                    append("\n")
                }

                append("    }")
                if (idx != answerOwnerIds.lastIndex) append(",")
                append("\n")
            }
            append("  },\n")

            append("  \"followups\": {\n")
            val fEntries = sortedFollowups.entries.toList()
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

            append("  \"voice_files\": [\n")
            flatAudioRefsForRun.forEachIndexed { idx, ref ->
                val qId = ref.questionId
                val questionText = questions[qId] ?: nodesSnapshot[qId]?.question ?: ""
                val answerText = answers[qId].orEmpty()

                append("    {\n")
                append("      \"file\": \"")
                    .append(escapeJson(ref.fileName))
                    .append("\",\n")
                append("      \"survey_id\": \"")
                    .append(escapeJson(surveyUuid))
                    .append("\",\n")
                append("      \"question_id\": \"")
                    .append(escapeJson(qId))
                    .append("\",\n")
                append("      \"question\": \"")
                    .append(escapeJson(questionText))
                    .append("\",\n")
                append("      \"answer\": \"")
                    .append(escapeJson(answerText))
                    .append("\"\n")
                append("    }")
                if (idx != flatAudioRefsForRun.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")

            append("}\n")
        }
    }

    val autoSavedOnce = remember(surveyUuid) { mutableStateOf(false) }

    LaunchedEffect(autoSaveToDevice, jsonText, surveyUuid) {
        if (autoSaveToDevice && !autoSavedOnce.value) {
            val fileName = buildSurveyFileName(
                surveyId = surveyUuid,
                prefix = "survey",
                stamp = exportedAtStamp
            )
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
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Survey ID: $surveyUuid",
                style = MaterialTheme.typography.labelLarge
            )

            Spacer(Modifier.height(16.dp))

            Text("■ Answers", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (answerOwnerIds.isEmpty()) {
                Text("No answers yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                answerOwnerIds.forEach { id ->
                    val q = questions[id] ?: nodesSnapshot[id]?.question ?: "(unknown question)"
                    val a = answers[id].orEmpty()
                    val audioCount = audioRefsForRun[id].orEmpty().size

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
                        if (audioCount > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Audio: $audioCount file(s)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("■ Follow-ups", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (followups.isEmpty()) {
                Text("No follow-ups.", style = MaterialTheme.typography.bodyMedium)
            } else {
                followups.toSortedMap().forEach { (ownerId, list) ->
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
                    HorizontalDivider()
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("■ Recorded voice files", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (flatAudioRefsForRun.isEmpty()) {
                Text(
                    text = "No voice recordings registered for this survey run.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "Manifest: ${flatAudioRefsForRun.size} reference(s).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "On device now: ${voiceFilesForRun.size} file(s).",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(6.dp))
                flatAudioRefsForRun.take(8).forEach { ref ->
                    Text(
                        text = "• ${ref.fileName}  (q=${ref.questionId})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (flatAudioRefsForRun.size > 8) {
                    Spacer(Modifier.height(2.dp))
                    Text("… and more", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ----------------------------------------------------------------
            // Export / upload actions
            // ----------------------------------------------------------------

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // -------------------- GitHub (immediate) --------------------
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            if (uploading) return@Button
                            scope.launch {
                                uploading = true
                                try {
                                    val cfg = gitHubConfig
                                    val fileName = buildSurveyFileName(
                                        surveyId = surveyUuid,
                                        prefix = "survey",
                                        stamp = exportedAtStamp
                                    )

                                    val (jsonRes, voicesUploaded, didUploadLog) =
                                        withContext(Dispatchers.IO) {
                                            val jsonRemote = "$REMOTE_EXPORT_DIR/$fileName"
                                            val jsonResult = GitHubUploader.uploadJson(
                                                cfg = cfg,
                                                relativePath = jsonRemote,
                                                content = jsonText,
                                                message = "Upload $fileName"
                                            )

                                            val currentVoiceFiles =
                                                scanVoiceFilesByNames(context, expectedVoiceFileNames)

                                            var uploadedVoices = 0
                                            currentVoiceFiles.forEach { file ->
                                                GitHubUploader.uploadFile(
                                                    cfg = cfg,
                                                    relativePath = "$REMOTE_VOICE_DIR/${file.name}",
                                                    bytes = file.readBytes(),
                                                    message = "Upload ${file.name}"
                                                )
                                                runCatching { deleteVoiceSidecars(file) }
                                                runCatching { file.delete() }
                                                uploadedVoices++
                                            }

                                            val logFile = captureSessionLogcatToPendingFile(
                                                context = context,
                                                surveyUuid = surveyUuid,
                                                exportedAtStamp = exportedAtStamp,
                                                maxBytes = MAX_LOGCAT_BYTES,
                                                pendingDirName = "pending_uploads"
                                            )

                                            val didUpload = runCatching {
                                                GitHubUploader.uploadFile(
                                                    cfg = cfg,
                                                    relativePath = "$REMOTE_LOG_DIR/${logFile.name}",
                                                    bytes = logFile.readBytes(),
                                                    message = "Upload ${logFile.name}"
                                                )
                                            }.isSuccess

                                            if (didUpload) runCatching { logFile.delete() }

                                            Triple(jsonResult, uploadedVoices, didUpload)
                                        }

                                    val resultLabel = jsonRes.fileUrl ?: jsonRes.commitSha ?: "(no URL)"
                                    val logMsg = if (didUploadLog) " + logs" else ""

                                    if (voicesUploaded > 0) {
                                        snackbar.showOnce("GitHub: Uploaded JSON + $voicesUploaded voice file(s)$logMsg: $resultLabel")
                                    } else {
                                        snackbar.showOnce("GitHub: Uploaded JSON$logMsg: $resultLabel")
                                    }

                                    val remaining = withContext(Dispatchers.IO) {
                                        scanVoiceFilesByNames(context, expectedVoiceFileNames)
                                    }
                                    voiceFilesState.value = remaining
                                } catch (e: Exception) {
                                    snackbar.showOnce("GitHub upload failed: ${e.message}")
                                } finally {
                                    uploading = false
                                }
                            }
                        },
                        enabled = !uploading
                    ) {
                        Text(if (uploading) "Uploading..." else "GitHub: Upload now")
                    }
                }

                // -------------------- Supabase (immediate) --------------------
                if (supabaseCfg != null) {
                    Button(
                        onClick = {
                            if (uploading) return@Button
                            scope.launch {
                                uploading = true
                                try {
                                    val cfg = supabaseCfg
                                    val fileName = buildSurveyFileName(
                                        surveyId = surveyUuid,
                                        prefix = "survey",
                                        stamp = exportedAtStamp
                                    )

                                    val (voicesUploaded, didUploadLog) = withContext(Dispatchers.IO) {
                                        val jsonRemote = "$REMOTE_EXPORT_DIR/$fileName"
                                        val jsonRes = SupabaseStorageUploader.uploadBytes(
                                            cfg = cfg,
                                            remotePath = jsonRemote,
                                            bytes = jsonText.toByteArray(Charsets.UTF_8),
                                            contentType = "application/json; charset=utf-8",
                                            upsert = false
                                        )
                                        if (jsonRes.isFailure) {
                                            throw (jsonRes.exceptionOrNull() ?: IllegalStateException("Supabase JSON upload failed"))
                                        }

                                        val currentVoiceFiles =
                                            scanVoiceFilesByNames(context, expectedVoiceFileNames)

                                        var uploadedVoices = 0
                                        currentVoiceFiles.forEach { file ->
                                            val r = SupabaseStorageUploader.uploadFile(
                                                cfg = cfg,
                                                remotePath = "$REMOTE_VOICE_DIR/${file.name}",
                                                file = file,
                                                contentType = "audio/wav",
                                                upsert = false
                                            )
                                            if (r.isSuccess) {
                                                runCatching { deleteVoiceSidecars(file) }
                                                runCatching { file.delete() }
                                                uploadedVoices++
                                            } else {
                                                throw (r.exceptionOrNull() ?: IllegalStateException("Supabase voice upload failed"))
                                            }
                                        }

                                        val logFile = captureSessionLogcatToPendingFile(
                                            context = context,
                                            surveyUuid = surveyUuid,
                                            exportedAtStamp = exportedAtStamp,
                                            maxBytes = MAX_LOGCAT_BYTES,
                                            pendingDirName = "pending_uploads_supabase"
                                        )

                                        val logRes = SupabaseStorageUploader.uploadFile(
                                            cfg = cfg,
                                            remotePath = "$REMOTE_LOG_DIR/${logFile.name}",
                                            file = logFile,
                                            contentType = "application/gzip",
                                            upsert = false
                                        )

                                        val didLog = logRes.isSuccess
                                        if (didLog) runCatching { logFile.delete() }

                                        Pair(uploadedVoices, didLog)
                                    }

                                    val logMsg = if (didUploadLog) " + logs" else ""
                                    if (voicesUploaded > 0) {
                                        snackbar.showOnce("Supabase: Uploaded JSON + $voicesUploaded voice file(s)$logMsg")
                                    } else {
                                        snackbar.showOnce("Supabase: Uploaded JSON$logMsg")
                                    }

                                    val remaining = withContext(Dispatchers.IO) {
                                        scanVoiceFilesByNames(context, expectedVoiceFileNames)
                                    }
                                    voiceFilesState.value = remaining
                                } catch (e: Exception) {
                                    snackbar.showOnce("Supabase upload failed: ${e.message}")
                                } finally {
                                    uploading = false
                                }
                            }
                        },
                        enabled = !uploading
                    ) {
                        Text("Supabase: Upload now")
                    }
                }

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // -------------------- GitHub (deferred) --------------------
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            val cfg = gitHubConfig
                            val fileName = buildSurveyFileName(
                                surveyId = surveyUuid,
                                prefix = "survey",
                                stamp = exportedAtStamp
                            )
                            val jsonRemote = "$REMOTE_EXPORT_DIR/$fileName"

                            runCatching {
                                val pendingJson = writePendingTextFile(
                                    context = context,
                                    fileName = fileName,
                                    content = jsonText,
                                    pendingDirName = "pending_uploads"
                                )
                                enqueueGitHubWorkerFileUpload(
                                    context = context,
                                    cfg = cfg,
                                    localFile = pendingJson,
                                    remoteRelativePath = jsonRemote
                                )
                                scope.launch {
                                    snackbar.showOnce("GitHub: Upload scheduled (JSON, will run when online).")
                                }
                            }.onFailure { e ->
                                scope.launch {
                                    snackbar.showOnce("GitHub: Failed to schedule JSON upload: ${e.message}")
                                }
                            }

                            val wavsToSchedule = voiceFilesForRun
                            if (wavsToSchedule.isNotEmpty()) {
                                wavsToSchedule.forEach { file ->
                                    runCatching {
                                        enqueueGitHubWorkerFileUpload(
                                            context = context,
                                            cfg = cfg,
                                            localFile = file,
                                            remoteRelativePath = "$REMOTE_VOICE_DIR/${file.name}"
                                        )
                                    }
                                }
                                scope.launch {
                                    snackbar.showOnce("GitHub: Upload scheduled (${wavsToSchedule.size} voice file(s)).")
                                }
                            }

                            runCatching {
                                val pendingLog = captureSessionLogcatToPendingFile(
                                    context = context,
                                    surveyUuid = surveyUuid,
                                    exportedAtStamp = exportedAtStamp,
                                    maxBytes = MAX_LOGCAT_BYTES,
                                    pendingDirName = "pending_uploads"
                                )
                                enqueueGitHubWorkerFileUpload(
                                    context = context,
                                    cfg = cfg,
                                    localFile = pendingLog,
                                    remoteRelativePath = "$REMOTE_LOG_DIR/${pendingLog.name}"
                                )
                                scope.launch {
                                    snackbar.showOnce("GitHub: Upload scheduled (logs, will run when online).")
                                }
                            }.onFailure { e ->
                                scope.launch {
                                    snackbar.showOnce("GitHub: Failed to schedule logs: ${e.message}")
                                }
                            }
                        },
                        enabled = !uploading
                    ) {
                        Text("GitHub: Upload later")
                    }
                }

                // -------------------- Supabase (deferred) --------------------
                if (supabaseCfg != null) {
                    Button(
                        onClick = {
                            val cfg = supabaseCfg
                            val fileName = buildSurveyFileName(
                                surveyId = surveyUuid,
                                prefix = "survey",
                                stamp = exportedAtStamp
                            )
                            val jsonRemote = "$REMOTE_EXPORT_DIR/$fileName"

                            runCatching {
                                val pendingJson = writePendingTextFile(
                                    context = context,
                                    fileName = fileName,
                                    content = jsonText,
                                    pendingDirName = "pending_uploads_supabase"
                                )
                                enqueueSupabaseWorkerFileUpload(
                                    context = context,
                                    cfg = cfg,
                                    localFile = pendingJson,
                                    remotePath = jsonRemote,
                                    contentType = "application/json; charset=utf-8",
                                    upsert = false
                                )
                                scope.launch {
                                    snackbar.showOnce("Supabase: Upload scheduled (JSON, will run when online).")
                                }
                            }.onFailure { e ->
                                scope.launch {
                                    snackbar.showOnce("Supabase: Failed to schedule JSON upload: ${e.message}")
                                }
                            }

                            val wavsToSchedule = voiceFilesForRun
                            if (wavsToSchedule.isNotEmpty()) {
                                wavsToSchedule.forEach { file ->
                                    runCatching {
                                        enqueueSupabaseWorkerFileUpload(
                                            context = context,
                                            cfg = cfg,
                                            localFile = file,
                                            remotePath = "$REMOTE_VOICE_DIR/${file.name}",
                                            contentType = "audio/wav",
                                            upsert = false
                                        )
                                    }
                                }
                                scope.launch {
                                    snackbar.showOnce("Supabase: Upload scheduled (${wavsToSchedule.size} voice file(s)).")
                                }
                            }

                            runCatching {
                                val pendingLog = captureSessionLogcatToPendingFile(
                                    context = context,
                                    surveyUuid = surveyUuid,
                                    exportedAtStamp = exportedAtStamp,
                                    maxBytes = MAX_LOGCAT_BYTES,
                                    pendingDirName = "pending_uploads_supabase"
                                )
                                enqueueSupabaseWorkerFileUpload(
                                    context = context,
                                    cfg = cfg,
                                    localFile = pendingLog,
                                    remotePath = "$REMOTE_LOG_DIR/${pendingLog.name}",
                                    contentType = "application/gzip",
                                    upsert = false
                                )
                                scope.launch {
                                    snackbar.showOnce("Supabase: Upload scheduled (logs, will run when online).")
                                }
                            }.onFailure { e ->
                                scope.launch {
                                    snackbar.showOnce("Supabase: Failed to schedule logs: ${e.message}")
                                }
                            }
                        },
                        enabled = !uploading
                    ) {
                        Text("Supabase: Upload later")
                    }
                }

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onRestart, enabled = !uploading) {
                    Text("Restart")
                }
            }
        }
    }

    LaunchedEffect(surveyUuid) {
        snackbar.showOnce("Thank you for your responses")
    }
}

/* ============================================================
 * WorkManager enqueue helpers
 * ============================================================ */

private fun enqueueGitHubWorkerFileUpload(
    context: Context,
    cfg: GitHubUploader.GitHubConfig,
    localFile: File,
    remoteRelativePath: String
) {
    val safeUnique = sanitizeWorkName(remoteRelativePath)
    val uniqueName = "gh_upload_$safeUnique"

    val req: OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<GitHubUploadWorker>()
            .setInputData(
                workDataOf(
                    GitHubUploadWorker.KEY_MODE to "file",
                    GitHubUploadWorker.KEY_OWNER to cfg.owner,
                    GitHubUploadWorker.KEY_REPO to cfg.repo,
                    GitHubUploadWorker.KEY_TOKEN to cfg.token,
                    GitHubUploadWorker.KEY_BRANCH to cfg.branch,
                    GitHubUploadWorker.KEY_PATH_PREFIX to cfg.pathPrefix,
                    GitHubUploadWorker.KEY_FILE_PATH to localFile.absolutePath,
                    GitHubUploadWorker.KEY_FILE_NAME to remoteRelativePath
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(GitHubUploadWorker.TAG)
            .addTag("${GitHubUploadWorker.TAG}:file:$safeUnique")
            .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
}

private fun enqueueSupabaseWorkerFileUpload(
    context: Context,
    cfg: SupabaseStorageUploader.SupabaseConfig,
    localFile: File,
    remotePath: String,
    contentType: String,
    upsert: Boolean = false
) {
    val safeUnique = sanitizeWorkName(remotePath)
    val uniqueName = "sb_upload_$safeUnique"

    val trimmed = remotePath.trim().trim('/')
    val remoteDir = trimmed.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "regular" }
    val remoteName = trimmed.substringAfterLast('/')

    val req: OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SupabaseUploadWorker>()
            .setInputData(
                workDataOf(
                    SupabaseUploadWorker.KEY_MODE to "file",
                    SupabaseUploadWorker.KEY_URL to cfg.supabaseUrl,
                    SupabaseUploadWorker.KEY_ANON_KEY to cfg.anonKey,
                    SupabaseUploadWorker.KEY_BUCKET to cfg.bucket,
                    SupabaseUploadWorker.KEY_PATH_PREFIX to cfg.pathPrefix,
                    SupabaseUploadWorker.KEY_FILE_PATH to localFile.absolutePath,
                    SupabaseUploadWorker.KEY_FILE_NAME to remoteName,
                    SupabaseUploadWorker.KEY_REMOTE_DIR to remoteDir,
                    SupabaseUploadWorker.KEY_CONTENT_TYPE to contentType,
                    SupabaseUploadWorker.KEY_UPSERT to upsert,
                    SupabaseUploadWorker.KEY_USER_JWT to ""
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(SupabaseUploadWorker.TAG)
            .addTag("${SupabaseUploadWorker.TAG}:file:$safeUnique")
            .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
}

private fun sanitizeWorkName(value: String): String {
    return value
        .trim()
        .replace(Regex("""[^\w\-.]+"""), "_")
        .take(120)
}

/* ============================================================
 * Pending file helpers
 * ============================================================ */

private fun writePendingTextFile(
    context: Context,
    fileName: String,
    content: String,
    pendingDirName: String
): File {
    require(fileName.isNotBlank()) { "fileName is blank." }

    val safeName = sanitizeFileName(fileName)
    val dir = File(context.filesDir, pendingDirName).apply { mkdirs() }
    val target = uniqueIfExists(File(dir, safeName))

    target.writeText(content, Charsets.UTF_8)
    return target
}

private fun sanitizeFileName(name: String): String {
    val flattened = name.replace("/", "_")
    return flattened.replace(Regex("""[^\w\-.]"""), "_")
}

private fun uniqueIfExists(file: File): File {
    if (!file.exists()) return file

    val base = file.nameWithoutExtension
    val ext = file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""

    var idx = 1
    while (true) {
        val candidate = File(file.parentFile, "${base}_$idx$ext")
        if (!candidate.exists()) return candidate
        idx++
    }
}

/* ============================================================
 * Voice scan helpers (physical files)
 * ============================================================ */

private fun scanVoiceFilesByNames(
    context: Context,
    expectedNames: Set<String>
): List<File> {
    if (expectedNames.isEmpty()) return emptyList()

    val voiceDir = ExportUtils.getVoiceExportDir(context)
    if (!voiceDir.exists() || !voiceDir.isDirectory) return emptyList()

    val wavFiles = voiceDir.listFiles { f ->
        f.isFile &&
                !f.name.startsWith(".") &&
                f.name.lowercase(Locale.US).endsWith(".wav") &&
                expectedNames.contains(f.name)
    } ?: return emptyList()

    return wavFiles.sortedByDescending { it.lastModified() }
}

private fun deleteVoiceSidecars(wavFile: File) {
    val dir = wavFile.parentFile ?: return
    val base = wavFile.name.substringBeforeLast('.', wavFile.name)
    val meta = File(dir, "$base.meta.json")
    if (meta.exists()) {
        runCatching { meta.delete() }
    }
}

/* ============================================================
 * Logcat capture helpers (diagnostics)
 * ============================================================ */

private fun captureSessionLogcatToPendingFile(
    context: Context,
    surveyUuid: String,
    exportedAtStamp: String,
    maxBytes: Int,
    pendingDirName: String
): File {
    val pid = Process.myPid()
    val shortId = surveyUuid.take(8).ifBlank { "unknown" }
    val baseName = "logcat_${exportedAtStamp}_pid${pid}_$shortId.log.gz"
    val safeName = sanitizeFileName(baseName)

    val dir = File(context.filesDir, pendingDirName).apply { mkdirs() }
    val outFile = uniqueIfExists(File(dir, safeName))

    val header = buildString {
        appendLine("=== Session Log Snapshot ===")
        appendLine("time_local=$exportedAtStamp")
        appendLine("survey_id=$surveyUuid")
        appendLine("pid=$pid")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("appId=${BuildConfig.APPLICATION_ID}")
        appendLine("versionName=${BuildConfig.VERSION_NAME}")
        appendLine("versionCode=${BuildConfig.VERSION_CODE}")
        appendLine("tags=${LOGCAT_TAG_FILTERS.joinToString(",")}")
        appendLine()
        appendLine("=== Logcat (best-effort) ===")
    }.toByteArray(Charsets.UTF_8)

    val logBytes = collectLogcatBytesBestEffort(
        pid = pid,
        maxBytes = maxBytes,
        tags = LOGCAT_TAG_FILTERS
    )

    FileOutputStream(outFile).use { fos ->
        GZIPOutputStream(fos).use { gz ->
            gz.write(header)
            gz.write(logBytes)
            gz.flush()
        }
    }

    Log.d(LOG_TAG, "Captured logcat snapshot: ${outFile.absolutePath} (${outFile.length()} bytes gz)")
    return outFile
}

private fun collectLogcatBytesBestEffort(
    pid: Int,
    maxBytes: Int,
    tags: Array<String>
): ByteArray {
    val cmd1 = arrayOf("logcat", "-d", "--pid=$pid", "-v", "threadtime", "-s", *tags)
    val cmd2 = arrayOf("logcat", "-d", "--pid=$pid", "-v", "threadtime")
    val cmd3 = arrayOf("logcat", "-d", "-v", "threadtime", "-s", *tags)
    val cmd4 = arrayOf("logcat", "-d", "-v", "threadtime")

    return runCatching { execAndReadCapped(cmd1, maxBytes) }
        .recoverCatching { execAndReadCapped(cmd2, maxBytes) }
        .recoverCatching { execAndReadCapped(cmd3, maxBytes) }
        .recoverCatching { execAndReadCapped(cmd4, maxBytes) }
        .getOrElse { e ->
            ("(logcat capture failed: ${e.message})\n").toByteArray(Charsets.UTF_8)
        }
}

private fun execAndReadCapped(cmd: Array<String>, maxBytes: Int): ByteArray {
    val proc = Runtime.getRuntime().exec(cmd)
    val input = BufferedInputStream(proc.inputStream)

    val out = ByteArray(maxBytes)
    var total = 0

    while (total < maxBytes) {
        val n = input.read(out, total, maxBytes - total)
        if (n <= 0) break
        total += n
    }

    runCatching { input.close() }
    runCatching { proc.destroy() }

    return if (total == out.size) out else out.copyOf(total)
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
    context: Context,
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
    context: Context,
    fileName: String,
    content: String
): SaveResult {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SurveyNav")
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
    context: Context,
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

/**
 * Minimal JSON string escaper for manual export.
 */
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
