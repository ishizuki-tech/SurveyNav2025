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
 *   • Immediate GitHub upload (online now).
 *   • Deferred GitHub upload via WorkManager (runs when online).
 *
 *  Storage model:
 *   • API 29+ : MediaStore Downloads/SurveyNav (visible in Files app).
 *   • API 28- : app-specific external Downloads/SurveyNav (no runtime perms).
 *
 *  Notes:
 *   • JSON is built manually with a small escaper to keep dependencies light.
 *   • Newlines are not preprocessed; escapeJson() handles them correctly.
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
import com.negi.survey.vm.SurveyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Final survey screen that summarizes all collected answers and follow-ups.
 *
 * Responsibilities:
 *  - Read the current questions, answers, and follow-ups from [SurveyViewModel].
 *  - Render a human-readable summary of the interview session.
 *  - Build a JSON export payload covering answers + follow-ups.
 *  - Provide optional GitHub upload and deferred upload via WorkManager.
 *  - Optionally auto-save the JSON to device storage on first composition.
 *
 * The screen is UI-only: it does not mutate the survey graph and only calls
 * back through [onRestart] when the user wants to restart the flow.
 *
 * @param vm Survey-level ViewModel providing questions, answers, and follow-ups.
 * @param onRestart Callback invoked when the user presses the "Restart" button.
 * @param gitHubConfig Optional GitHub configuration. When non-null, the
 * upload buttons are enabled and will target the configured repository/branch.
 * @param autoSaveToDevice When true, the screen will auto-save the JSON export
 * exactly once on first composition (no picker interaction required).
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
     * Build a compact JSON export representing:
     *  - "answers": per-node question/answer pairs.
     *  - "followups": per-node arrays of follow-up question/answer pairs.
     *
     * The payload is deterministic for a given [questions]/[answers]/[followups]
     * snapshot; if required, entries can be sorted before writing.
     */
    val jsonText = remember(questions, answers, followups) {
        buildString {
            append("{\n")
            append("  \"answers\": {\n")
            val qEntries = questions.entries.toList()
            qEntries.forEachIndexed { idx, (id, q) ->
                val a = answers[id].orEmpty()
                append("    \"").append(escapeJson(id)).append("\": {\n")
                append("      \"question\": \"").append(escapeJson(q)).append("\",\n")
                append("      \"answer\": \"").append(escapeJson(a)).append("\"\n")
                append("    }")
                if (idx != qEntries.lastIndex) append(",")
                append("\n")
            }
            append("  },\n")
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
            append("  }\n")
            append("}\n")
        }
    }

    // Run once to auto-save export JSON (no picker, no blocking UI).
    val autoSavedOnce = remember { mutableStateOf(false) }
    LaunchedEffect(autoSaveToDevice, jsonText) {
        if (autoSaveToDevice && !autoSavedOnce.value) {
            // English comment:
            // Use a human-friendly, timestamped file name for device storage as well.
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

            Spacer(Modifier.height(24.dp))

            // Export / upload actions.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Immediate GitHub upload (requires network now).
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            if (uploading.value) return@Button
                            scope.launch {
                                uploading.value = true
                                try {
                                    // English comment:
                                    // Use a human-readable, timestamped file name.
                                    val fileName = buildSurveyFileName()

                                    // English comment:
                                    // Use the GitHubConfig-based overload so that
                                    // the date-based path logic inside GitHubUploader
                                    // (e.g., yyyy-MM-dd/fileName) is consistently applied.
                                    val result = withContext(Dispatchers.IO) {
                                        GitHubUploader.uploadJson(
                                            cfg = gitHubConfig,
                                            relativePath = fileName,
                                            content = jsonText,
                                            message = "Upload $fileName"
                                        )
                                    }

                                    snackbar.showOnce(
                                        "Uploaded: ${result.fileUrl ?: result.commitSha}"
                                    )
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

                // Deferred GitHub upload via WorkManager.
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            // English comment:
                            // Use the same naming scheme so on-device file names
                            // and GitHub paths stay aligned.
                            val fileName = buildSurveyFileName()
                            GitHubUploadWorker.enqueue(
                                context = context,
                                cfg = gitHubConfig,
                                fileName = fileName,
                                jsonContent = jsonText
                            )
                            scope.launch {
                                snackbar.showOnce(
                                    "Upload scheduled (will run when online)."
                                )
                            }
                        }
                    ) {
                        Text("Upload when Online")
                    }
                }

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(1.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onRestart) {
                    Text("Restart")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Show a friendly completion message once.
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
 *
 * English comment:
 * - Uses local device time for readability.
 * - Keeps a stable "survey_" prefix so that files
 *   group naturally in file explorers and GitHub.
 */
private fun buildSurveyFileName(prefix: String = "survey"): String {
    val now = LocalDateTime.now()
    val stamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    return "${prefix}_${stamp}.json"
}

/* ============================================================
 * Auto-save helpers (no user interaction)
 * ============================================================ */

/**
 * Result of a JSON save operation.
 *
 * Exactly one of [uri] or [file] is non-null depending on the storage
 * mechanism that was used.
 *
 * @property uri Content URI for the file in MediaStore (API 29+), or null.
 * @property file [File] handle for app-specific external storage (pre-Q), or null.
 * @property location Human-readable location hint for user-facing messaging.
 */
private data class SaveResult(
    val uri: Uri?,
    val file: File?,
    val location: String
)

/**
 * Save JSON content to device storage without user interaction.
 *
 * Behavior:
 *  - API 29+ : Writes into MediaStore Downloads/SurveyNav using RELATIVE_PATH.
 *  - API 28- : Writes into app-specific external Downloads/SurveyNav directory.
 *
 * @param context Android [android.content.Context].
 * @param fileName Target file name, e.g. `"survey_2025-11-15_14-32-08.json"`.
 * @param content JSON payload to write.
 * @return [SaveResult] describing where the file ended up.
 */
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

/**
 * API 29+ implementation that writes JSON into the system Downloads collection.
 *
 * Files are placed under:
 *  Downloads/SurveyNav/[fileName]
 *
 * The resulting file is visible to the system Files app and other consumers
 * with access to the Downloads collection.
 */
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

/**
 * Pre-API 29 implementation that writes JSON into app-specific external
 * storage under Downloads/SurveyNav.
 *
 * This does not require runtime storage permissions because it uses
 * the app-owned external files directory.
 */
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

/**
 * Show a single snackbar message, dismissing any currently visible snackbar
 * first to avoid stacking multiple messages.
 */
private suspend fun SnackbarHostState.showOnce(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

/**
 * Minimal JSON string escaper.
 *
 * Escapes:
 *  - Double quotes (`"`) → `\"`
 *  - Backslashes (`\`)  → `\\`
 *  - Newlines           → `\n`
 *  - Carriage returns   → `\r`
 *  - Tabs               → `\t`
 *
 * The implementation intentionally does not handle every possible Unicode
 * control character, but is sufficient for typical survey text content.
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
