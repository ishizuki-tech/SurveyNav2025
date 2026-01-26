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
 *  Crash-resistant final screen:
 *    - Builds a stable JSON export from ViewModel audio manifest.
 *    - Shows summary of answers + follow-ups + audio refs.
 *    - Supports:
 *        • Auto-save JSON to device storage (optional)
 *        • Upload now (best-effort, partial success allowed)
 *        • Upload later (WorkManager, per-file upload jobs)
 *        • Log-only upload now / later
 *
 *  Hardening goals:
 *    - Never OOM on large WAV files (no naive readBytes()).
 *    - Never hang on logcat collection (best-effort, capped).
 *    - Never fail the whole action because one file failed.
 *    - UI remains responsive; errors are surfaced via snackbar.
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
import com.negi.survey.utils.ExportUtils
import com.negi.survey.utils.buildSurveyFileName
import com.negi.survey.vm.Node
import com.negi.survey.vm.SurveyViewModel
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val LOG_TAG = "DoneScreen"

private const val REMOTE_VOICE_DIR = "voice"
private const val REMOTE_LOG_DIR = "diagnostics/logcat"

/** Cap logcat bytes to keep contents uploads stable. */
private const val MAX_LOGCAT_BYTES = 850_000

/**
 * Hard cap for in-memory file reads to avoid OOM.
 * If a file exceeds this, we refuse to read it and continue safely.
 */
private const val MAX_IN_MEMORY_READ_BYTES = 24 * 1024 * 1024 // 24MB

/**
 * Conservative “safe” payload size for APIs that behave best under ~1MB.
 * If your GitHubUploader uses the Contents API, keep small files here to reduce failures.
 * (We do not crash if exceeded; we just skip + notify.)
 */
private const val MAX_GITHUB_SAFE_BYTES = 950_000 // ~0.95MB

private val LOGCAT_TAG_FILTERS = arrayOf(
    "WhisperEngine",
    "MainActivity",
    "CrashCapture",
    "GitHubUploadWorker",
    "GitHubUploader",
    "LiteRtLM",
    "LiteRtRepository"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoneScreen(
    vm: SurveyViewModel,
    onRestart: () -> Unit,
    gitHubConfig: GitHubUploader.GitHubConfig? = null,
    autoSaveToDevice: Boolean = false
) {
    val questions by vm.questions.collectAsState()
    val answers by vm.answers.collectAsState()
    val followups by vm.followups.collectAsState()
    val recordedAudioRefs by vm.recordedAudioRefs.collectAsState()
    val surveyUuid by vm.surveyUuid.collectAsState()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val workManager = remember(context) { WorkManager.getInstance(context) }

    /** Prevent multi-click re-entrancy even under recomposition races. */
    val actionGate = remember { AtomicBoolean(false) }
    var busy by remember { mutableStateOf(false) }

    val exportedAtStamp = remember(surveyUuid) {
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    }

    /** Logical audio refs for this run (source of truth). */
    val audioRefsForRun: Map<String, List<SurveyViewModel.AudioRef>> =
        remember(recordedAudioRefs, surveyUuid) {
            vm.getAudioRefsForRun(surveyUuid)
        }

    val flatAudioRefsForRun: List<SurveyViewModel.AudioRef> =
        remember(recordedAudioRefs, surveyUuid) {
            vm.getAudioRefsForRunFlat(surveyUuid)
        }

    val expectedVoiceFileNames: Set<String> =
        remember(flatAudioRefsForRun) {
            flatAudioRefsForRun
                .asSequence()
                .map { it.fileName.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }

    /** Physical WAV files currently present for this run (best-effort). */
    val voiceFilesState = remember(surveyUuid) { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(expectedVoiceFileNames, surveyUuid) {
        val files = withContext(Dispatchers.IO) {
            runCatching { scanVoiceFilesByNames(context, expectedVoiceFileNames) }.getOrElse { emptyList() }
        }
        voiceFilesState.value = files
    }
    val voiceFilesForRun = voiceFilesState.value

    /** Snapshot nodes for question fallback. */
    val nodesSnapshot: Map<String, Node> = remember(vm) { vm.nodes }

    /** Union keys to avoid dropping audio-only nodes. */
    val answerOwnerIds: List<String> = remember(questions, answers, audioRefsForRun, followups) {
        val ids = linkedSetOf<String>()
        ids.addAll(questions.keys)
        ids.addAll(answers.keys)
        ids.addAll(audioRefsForRun.keys)
        ids.addAll(followups.keys)
        ids.toList().sorted()
    }

    /** Build JSON (manual, stable ordering). */
    val jsonText: String = remember(
        questions,
        answers,
        followups,
        audioRefsForRun,
        flatAudioRefsForRun,
        surveyUuid,
        exportedAtStamp,
        answerOwnerIds
    ) {
        val sortedFollowups: Map<String, List<SurveyViewModel.FollowupEntry>> = followups.toSortedMap()

        buildString {
            append("{\n")
            append("  \"survey_id\": \"").append(escapeJson(surveyUuid)).append("\",\n")
            append("  \"exported_at\": \"").append(escapeJson(exportedAtStamp)).append("\",\n")

            append("  \"answers\": {\n")
            answerOwnerIds.forEachIndexed { idx: Int, id: String ->
                val q = questions[id] ?: nodesSnapshot[id]?.question ?: ""
                val a = answers[id].orEmpty()
                val audioList: List<SurveyViewModel.AudioRef> = audioRefsForRun[id].orEmpty()

                append("    \"").append(escapeJson(id)).append("\": {\n")
                append("      \"question\": \"").append(escapeJson(q)).append("\",\n")
                append("      \"answer\": \"").append(escapeJson(a)).append("\"")

                if (audioList.isNotEmpty()) {
                    append(",\n")
                    append("      \"audio\": [\n")
                    audioList.forEachIndexed { j: Int, ref: SurveyViewModel.AudioRef ->
                        append("        { \"file\": \"").append(escapeJson(ref.fileName)).append("\" }")
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
            fEntries.forEachIndexed { i: Int, e: Map.Entry<String, List<SurveyViewModel.FollowupEntry>> ->
                val ownerId = e.key
                val list = e.value
                append("    \"").append(escapeJson(ownerId)).append("\": [\n")
                list.forEachIndexed { j: Int, fu: SurveyViewModel.FollowupEntry ->
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
            flatAudioRefsForRun.forEachIndexed { idx: Int, ref: SurveyViewModel.AudioRef ->
                val qId = ref.questionId
                val questionText = questions[qId] ?: nodesSnapshot[qId]?.question ?: ""
                val answerText = answers[qId].orEmpty()

                append("    {\n")
                append("      \"file\": \"").append(escapeJson(ref.fileName)).append("\",\n")
                append("      \"survey_id\": \"").append(escapeJson(surveyUuid)).append("\",\n")
                append("      \"question_id\": \"").append(escapeJson(qId)).append("\",\n")
                append("      \"question\": \"").append(escapeJson(questionText)).append("\",\n")
                append("      \"answer\": \"").append(escapeJson(answerText)).append("\"\n")
                append("    }")
                if (idx != flatAudioRefsForRun.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}\n")
        }
    }

    /** Auto-save once per survey UUID. */
    val autoSavedOnce = remember(surveyUuid) { mutableStateOf(false) }
    LaunchedEffect(autoSaveToDevice, jsonText, surveyUuid) {
        if (autoSaveToDevice && !autoSavedOnce.value) {
            runCatching {
                val fileName = buildSurveyFileName(
                    surveyId = surveyUuid,
                    prefix = "survey",
                    stamp = exportedAtStamp
                )
                val result = withContext(Dispatchers.IO) {
                    saveJsonAutomatically(context, fileName, jsonText)
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
            Text("Thanks! Here is your response summary.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text("Survey ID: $surveyUuid", style = MaterialTheme.typography.labelLarge)

            Spacer(Modifier.height(16.dp))

            Text("■ Answers", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (answerOwnerIds.isEmpty()) {
                Text("No answers yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                answerOwnerIds.forEach { id: String ->
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
                            Text("Audio: $audioCount file(s)", style = MaterialTheme.typography.bodySmall)
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
                followups.toSortedMap().forEach { (ownerId: String, list: List<SurveyViewModel.FollowupEntry>) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text("Owner node: $ownerId", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        list.forEachIndexed { idx: Int, fu: SurveyViewModel.FollowupEntry ->
                            Text("${idx + 1}. ${fu.question}", style = MaterialTheme.typography.bodyMedium)
                            val ans = fu.answer
                            if (!ans.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text("   ↳ $ans", style = MaterialTheme.typography.bodyLarge)
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
                    "No voice recordings registered for this survey run.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text("Manifest: ${flatAudioRefsForRun.size} reference(s).", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text("On device now: ${voiceFilesForRun.size} file(s).", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(6.dp))
                flatAudioRefsForRun.take(8).forEach { ref: SurveyViewModel.AudioRef ->
                    Text("• ${ref.fileName}  (q=${ref.questionId})", style = MaterialTheme.typography.bodySmall)
                }
                if (flatAudioRefsForRun.size > 8) {
                    Spacer(Modifier.height(2.dp))
                    Text("… and more", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (gitHubConfig != null) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            if (!actionGate.compareAndSet(false, true)) return@Button
                            scope.launch {
                                busy = true
                                try {
                                    prunePendingUploadsDirBestEffort(context)
                                    val cfg = gitHubConfig

                                    val jsonRemoteName = buildSurveyFileName(
                                        surveyId = surveyUuid,
                                        prefix = "survey",
                                        stamp = exportedAtStamp
                                    )

                                    // Stage JSON + logs to pending dir for consistent behavior.
                                    val stagedJson = withContext(Dispatchers.IO) {
                                        writePendingTextFile(context, jsonRemoteName, jsonText)
                                    }
                                    val stagedLog = withContext(Dispatchers.IO) {
                                        captureSessionLogcatToPendingFile(
                                            context = context,
                                            surveyUuid = surveyUuid,
                                            exportedAtStamp = exportedAtStamp,
                                            maxBytes = MAX_LOGCAT_BYTES
                                        )
                                    }

                                    // Refresh voice list right before upload (best-effort).
                                    val currentVoiceFiles = withContext(Dispatchers.IO) {
                                        runCatching { scanVoiceFilesByNames(context, expectedVoiceFileNames) }
                                            .getOrElse { emptyList() }
                                    }

                                    val result = withContext(Dispatchers.IO) {
                                        uploadNowBestEffort(
                                            cfg = cfg,
                                            jsonFile = stagedJson,
                                            jsonRemotePath = jsonRemoteName,
                                            voiceFiles = currentVoiceFiles,
                                            logFile = stagedLog,
                                            logRemotePath = "$REMOTE_LOG_DIR/${stagedLog.name}"
                                        )
                                    }

                                    snackbar.showOnce(result.userMessage)

                                    // Refresh physical list after deletion.
                                    val remaining = withContext(Dispatchers.IO) {
                                        runCatching { scanVoiceFilesByNames(context, expectedVoiceFileNames) }
                                            .getOrElse { emptyList() }
                                    }
                                    voiceFilesState.value = remaining
                                } catch (t: Throwable) {
                                    snackbar.showOnce("Upload failed: ${t.message}")
                                } finally {
                                    busy = false
                                    actionGate.set(false)
                                }
                            }
                        }
                    ) {
                        Text(if (busy) "Uploading..." else "Upload now")
                    }

                    Button(
                        enabled = !busy,
                        onClick = {
                            if (!actionGate.compareAndSet(false, true)) return@Button
                            scope.launch {
                                busy = true
                                try {
                                    prunePendingUploadsDirBestEffort(context)
                                    val cfg = gitHubConfig

                                    val jsonRemoteName = buildSurveyFileName(
                                        surveyId = surveyUuid,
                                        prefix = "survey",
                                        stamp = exportedAtStamp
                                    )

                                    // 1) Stage JSON and schedule.
                                    val pendingJson = withContext(Dispatchers.IO) {
                                        writePendingTextFile(context, jsonRemoteName, jsonText)
                                    }
                                    enqueueWorkerFileUpload(
                                        workManager = workManager,
                                        context = context,
                                        cfg = cfg,
                                        localFile = pendingJson,
                                        remoteRelativePath = jsonRemoteName
                                    )

                                    // 2) Schedule WAV uploads that physically exist and are referenced by manifest.
                                    val wavsToSchedule: List<File> = voiceFilesForRun
                                    wavsToSchedule.forEach { file: File ->
                                        val remote = "$REMOTE_VOICE_DIR/${file.name}"
                                        enqueueWorkerFileUpload(
                                            workManager = workManager,
                                            context = context,
                                            cfg = cfg,
                                            localFile = file,
                                            remoteRelativePath = remote
                                        )
                                    }

                                    // 3) Stage logcat snapshot and schedule.
                                    val pendingLog = withContext(Dispatchers.IO) {
                                        captureSessionLogcatToPendingFile(
                                            context = context,
                                            surveyUuid = surveyUuid,
                                            exportedAtStamp = exportedAtStamp,
                                            maxBytes = MAX_LOGCAT_BYTES
                                        )
                                    }
                                    enqueueWorkerFileUpload(
                                        workManager = workManager,
                                        context = context,
                                        cfg = cfg,
                                        localFile = pendingLog,
                                        remoteRelativePath = "$REMOTE_LOG_DIR/${pendingLog.name}"
                                    )

                                    val voiceMsg = if (wavsToSchedule.isEmpty()) "" else " + ${wavsToSchedule.size} voice file(s)"
                                    snackbar.showOnce("Upload scheduled (JSON$voiceMsg + logs).")
                                } catch (t: Throwable) {
                                    snackbar.showOnce("Upload scheduling failed: ${t.message}")
                                } finally {
                                    busy = false
                                    actionGate.set(false)
                                }
                            }
                        }
                    ) { Text("Upload later") }
                }

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            if (gitHubConfig != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            if (!actionGate.compareAndSet(false, true)) return@Button
                            scope.launch {
                                busy = true
                                try {
                                    prunePendingUploadsDirBestEffort(context)
                                    val cfg = gitHubConfig

                                    val stagedLog = withContext(Dispatchers.IO) {
                                        captureSessionLogcatToPendingFile(
                                            context = context,
                                            surveyUuid = surveyUuid,
                                            exportedAtStamp = exportedAtStamp,
                                            maxBytes = MAX_LOGCAT_BYTES
                                        )
                                    }

                                    val ok = withContext(Dispatchers.IO) {
                                        uploadSingleFileBestEffort(
                                            cfg = cfg,
                                            localFile = stagedLog,
                                            remotePath = "$REMOTE_LOG_DIR/${stagedLog.name}",
                                            allowOver1Mb = true,
                                            deleteOnSuccess = true
                                        )
                                    }

                                    snackbar.showOnce(if (ok) "Uploaded logs." else "Log upload failed (but app is OK).")
                                } catch (t: Throwable) {
                                    snackbar.showOnce("Log upload failed: ${t.message}")
                                } finally {
                                    busy = false
                                    actionGate.set(false)
                                }
                            }
                        }
                    ) { Text(if (busy) "Working..." else "Upload logs") }

                    Button(
                        enabled = !busy,
                        onClick = {
                            if (!actionGate.compareAndSet(false, true)) return@Button
                            scope.launch {
                                busy = true
                                try {
                                    prunePendingUploadsDirBestEffort(context)
                                    val cfg = gitHubConfig

                                    val pendingLog = withContext(Dispatchers.IO) {
                                        captureSessionLogcatToPendingFile(
                                            context = context,
                                            surveyUuid = surveyUuid,
                                            exportedAtStamp = exportedAtStamp,
                                            maxBytes = MAX_LOGCAT_BYTES
                                        )
                                    }

                                    enqueueWorkerFileUpload(
                                        workManager = workManager,
                                        context = context,
                                        cfg = cfg,
                                        localFile = pendingLog,
                                        remoteRelativePath = "$REMOTE_LOG_DIR/${pendingLog.name}"
                                    )

                                    snackbar.showOnce("Logs scheduled (will run when online).")
                                } catch (t: Throwable) {
                                    snackbar.showOnce("Failed to schedule logs: ${t.message}")
                                } finally {
                                    busy = false
                                    actionGate.set(false)
                                }
                            }
                        }
                    ) { Text("Upload logs later") }

                    Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onRestart, enabled = !busy) { Text("Restart") }
            }
        }
    }

    LaunchedEffect(surveyUuid) {
        snackbar.showOnce("Thank you for your responses")
    }
}

/* ============================================================
 * SurveyViewModel compatibility helpers
 * ============================================================ */

/**
 * Returns audio refs for a specific run (surveyUuid), grouped by questionId.
 * This is implemented as an extension to avoid changing SurveyViewModel public API.
 */
private fun SurveyViewModel.getAudioRefsForRun(surveyUuid: String): Map<String, List<SurveyViewModel.AudioRef>> {
    val snap: Map<String, List<SurveyViewModel.AudioRef>> = recordedAudioRefs.value
    if (snap.isEmpty()) return emptyMap()

    val out = LinkedHashMap<String, List<SurveyViewModel.AudioRef>>()
    for ((qid, list) in snap) {
        val filtered = list.filter { it.surveyId == surveyUuid }
        if (filtered.isNotEmpty()) out[qid] = filtered
    }
    return out
}

/**
 * Returns audio refs for a specific run (surveyUuid), flattened.
 */
private fun SurveyViewModel.getAudioRefsForRunFlat(surveyUuid: String): List<SurveyViewModel.AudioRef> {
    val snap: Map<String, List<SurveyViewModel.AudioRef>> = recordedAudioRefs.value
    if (snap.isEmpty()) return emptyList()

    return snap.values
        .asSequence()
        .flatten()
        .filter { it.surveyId == surveyUuid }
        .toList()
}

/* ============================================================
 * Upload now (best-effort, partial success OK)
 * ============================================================ */

private data class UploadNowResult(
    val userMessage: String
)

private suspend fun uploadNowBestEffort(
    cfg: GitHubUploader.GitHubConfig,
    jsonFile: File,
    jsonRemotePath: String,
    voiceFiles: List<File>,
    logFile: File,
    logRemotePath: String
): UploadNowResult {
    var jsonOk = false
    var voicesOk = 0
    var voicesSkippedLarge = 0
    var voicesFailed = 0
    var logOk = false

    // 1) JSON
    jsonOk = uploadSingleFileBestEffort(
        cfg = cfg,
        localFile = jsonFile,
        remotePath = jsonRemotePath,
        allowOver1Mb = true,
        deleteOnSuccess = false // keep the staged JSON for safety; caller can prune later
    )

    // 2) Voice files (each isolated)
    for (file in voiceFiles) {
        val tooLargeForSafe = file.length() > MAX_IN_MEMORY_READ_BYTES
        if (tooLargeForSafe) {
            voicesSkippedLarge++
            continue
        }

        val ok = uploadSingleFileBestEffort(
            cfg = cfg,
            localFile = file,
            remotePath = "$REMOTE_VOICE_DIR/${file.name}",
            allowOver1Mb = false, // conservative; avoid hard failures on Contents-like APIs
            deleteOnSuccess = true
        )

        if (ok) {
            // Delete sidecars only after successful upload.
            runCatching { deleteVoiceSidecars(file) }
            voicesOk++
        } else {
            // Keep file so it can be retried later.
            voicesFailed++
        }
    }

    // 3) Logs
    logOk = uploadSingleFileBestEffort(
        cfg = cfg,
        localFile = logFile,
        remotePath = logRemotePath,
        allowOver1Mb = true,
        deleteOnSuccess = true
    )

    val parts = ArrayList<String>()
    parts += if (jsonOk) "JSON:OK" else "JSON:FAIL"
    parts += "VOICE:OK=$voicesOk"
    if (voicesFailed > 0) parts += "FAIL=$voicesFailed"
    if (voicesSkippedLarge > 0) parts += "SKIP_LARGE=$voicesSkippedLarge"
    parts += if (logOk) "LOG:OK" else "LOG:FAIL"

    val msg = "Upload done (best-effort): " + parts.joinToString("  ")
    return UploadNowResult(userMessage = msg)
}

private suspend fun uploadSingleFileBestEffort(
    cfg: GitHubUploader.GitHubConfig,
    localFile: File,
    remotePath: String,
    allowOver1Mb: Boolean,
    deleteOnSuccess: Boolean
): Boolean {
    if (!localFile.exists() || !localFile.isFile) return false

    val size = localFile.length()
    if (size <= 0L) return false

    // Avoid OOM no matter what.
    if (size > MAX_IN_MEMORY_READ_BYTES) {
        Log.w(LOG_TAG, "Skip upload (too large to read safely): ${localFile.name} size=$size")
        return false
    }

    // Conservative guard for APIs that behave best under ~1MB.
    if (!allowOver1Mb && size > MAX_GITHUB_SAFE_BYTES) {
        Log.w(LOG_TAG, "Skip upload (over conservative safe size): ${localFile.name} size=$size")
        return false
    }

    val bytes = runCatching {
        readFileBytesCapped(localFile, MAX_IN_MEMORY_READ_BYTES)
    }.getOrElse {
        Log.e(LOG_TAG, "Read failed: ${localFile.name}", it)
        return false
    }

    val ok = runCatching {
        withTimeout(90_000L) {
            GitHubUploader.uploadFile(
                cfg = cfg,
                relativePath = remotePath,
                bytes = bytes,
                message = "Upload ${localFile.name}"
            )
        }
        true
    }.getOrElse { e ->
        Log.e(LOG_TAG, "Upload failed: remote=$remotePath local=${localFile.name}", e)
        false
    }

    if (ok && deleteOnSuccess) {
        runCatching { localFile.delete() }
    }
    return ok
}

/* ============================================================
 * WorkManager enqueue helpers
 * ============================================================ */

private fun enqueueWorkerFileUpload(
    workManager: WorkManager,
    context: Context,
    cfg: GitHubUploader.GitHubConfig,
    localFile: File,
    remoteRelativePath: String
) {
    val safeUnique = sanitizeWorkName(remoteRelativePath)
    val uniqueName = "upload_$safeUnique"

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

    workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
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
    content: String
): File {
    require(fileName.isNotBlank()) { "fileName is blank." }

    val safeName = sanitizeFileName(fileName)
    val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
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

/**
 * Best-effort pruning to prevent pending_uploads from growing forever.
 * This should NEVER throw.
 */
private fun prunePendingUploadsDirBestEffort(context: Context) {
    runCatching {
        val dir = File(context.filesDir, "pending_uploads")
        if (!dir.exists() || !dir.isDirectory) return

        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        val maxFiles = 250
        val maxBytes = 200L * 1024L * 1024L // 200MB

        var total = 0L
        files.forEachIndexed { idx, f ->
            total += f.length().coerceAtLeast(0L)
            val overCount = idx >= maxFiles
            val overBytes = total > maxBytes
            if (overCount || overBytes) {
                runCatching { f.delete() }
            }
        }
    }.onFailure {
        Log.w(LOG_TAG, "prunePendingUploadsDirBestEffort failed: ${it.message}")
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
    if (meta.exists()) runCatching { meta.delete() }
}

/* ============================================================
 * Logcat capture helpers (diagnostics)
 * ============================================================ */

private fun captureSessionLogcatToPendingFile(
    context: Context,
    surveyUuid: String,
    exportedAtStamp: String,
    maxBytes: Int
): File {
    val pid = Process.myPid()
    val shortId = surveyUuid.take(8).ifBlank { "unknown" }
    val baseName = "logcat_${exportedAtStamp}_pid${pid}_$shortId.log.gz"
    val safeName = sanitizeFileName(baseName)

    val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
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
    val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
    val proc = pb.start()

    val input = BufferedInputStream(proc.inputStream)
    val out = ByteArray(maxBytes)
    var total = 0

    while (total < maxBytes) {
        val n = input.read(out, total, maxBytes - total)
        if (n <= 0) break
        total += n
    }

    runCatching { input.close() }

    // Avoid hanging forever.
    runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
    runCatching { proc.destroy() }

    return if (total == out.size) out else out.copyOf(total)
}

/* ============================================================
 * Auto-save helpers
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
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
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
 * Snackbar + JSON + File read utilities
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

/**
 * Read a file into memory safely with a hard cap.
 *
 * @throws IllegalStateException if file exceeds cap
 */
private fun readFileBytesCapped(file: File, capBytes: Int): ByteArray {
    val len = file.length()
    if (len <= 0L) return ByteArray(0)
    if (len > capBytes.toLong()) {
        throw IllegalStateException("File too large to read safely: ${file.name} size=$len cap=$capBytes")
    }

    FileInputStream(file).use { fis ->
        val baos = ByteArrayOutputStream(len.toInt().coerceAtMost(capBytes))
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = fis.read(buf)
            if (n <= 0) break
            total += n
            if (total > capBytes) {
                throw IllegalStateException("Read exceeds cap: ${file.name} total=$total cap=$capBytes")
            }
            baos.write(buf, 0, n)
        }
        return baos.toByteArray()
    }
}
