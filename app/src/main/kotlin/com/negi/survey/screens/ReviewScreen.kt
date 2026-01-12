/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: ReviewScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Compact review screen that lists:
 *    • All original questions and answers.
 *    • Per-node follow-up questions and their answers.
 *
 *  Layout model:
 *    • Single LazyColumn inside a Scaffold, tuned for dense but readable typography.
 *    • Two cards: one for Q/A, one for follow-up history.
 *    • Bottom row of navigation buttons (“Back” / “Next”).
 *
 *  Performance notes:
 *    • Avoids "forEach inside item" anti-pattern that defeats LazyColumn virtualization.
 *    • Uses items() and stickyHeader for stable, scalable rendering.
 * =====================================================================
 */

package com.negi.survey.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.survey.vm.SurveyViewModel

@Composable
fun ReviewScreen(
    vm: SurveyViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
    debug: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme

    // Compact typography presets (tight but readable for dense lists).
    val titleTight = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 14.sp)
    val labelTight = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
    val bodyTight = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)

    val allQuestions by vm.questions.collectAsState(initial = emptyMap())
    val allAnswers by vm.answers.collectAsState(initial = emptyMap())
    val allFollowups by vm.followups.collectAsState(initial = emptyMap())

    // Stable union of node ids for Q/A.
    val qaEntries by remember(allQuestions, allAnswers) {
        derivedStateOf {
            val ids = (allQuestions.keys + allAnswers.keys).toSet().toList().sorted()
            ids.map { id ->
                QaEntry(
                    nodeId = id,
                    question = allQuestions[id].orEmpty(),
                    answer = allAnswers[id].orEmpty()
                )
            }
        }
    }

    // Followups flattened into Lazy-friendly rows (header rows + entry rows).
    val followupRows by remember(allFollowups) {
        derivedStateOf {
            val rows = ArrayList<FollowupRow>(allFollowups.size * 2)
            val sorted = allFollowups.toSortedMap()

            for ((nodeId, list) in sorted) {
                rows.add(FollowupRow.Header(nodeId))
                if (list.isNullOrEmpty()) {
                    rows.add(FollowupRow.Empty(nodeId))
                } else {
                    list.forEachIndexed { index, entry ->
                        rows.add(
                            FollowupRow.Item(
                                nodeId = nodeId,
                                index1 = index + 1,
                                question = entry.question,
                                answer = entry.answer
                            )
                        )
                    }
                }
            }
            rows
        }
    }

    // Lightweight debug summary (optional).
    remember(qaEntries.size, followupRows.size) {
        debug?.invoke("ReviewScreen: qaEntries=${qaEntries.size}, followupRows=${followupRows.size}")
        true
    }

    Scaffold(containerColor = Color.Transparent) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("ReviewScreenList"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Review",
                    style = titleTight,
                    color = cs.onSurface,
                    modifier = Modifier.testTag("ReviewHeader")
                )
            }

            // Q/A Card: true-lazy rows via items()
            item {
                ElevatedCard(Modifier.fillMaxWidth().testTag("QaCard")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "All Original Questions and Answers",
                            style = titleTight,
                            color = cs.onSurface
                        )
                        Spacer(Modifier.height(6.dp))

                        if (qaEntries.isEmpty()) {
                            Text(
                                text = "No records yet.",
                                style = bodyTight,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (qaEntries.isNotEmpty()) {
                // Make the per-entry dividers virtualized too.
                items(
                    items = qaEntries,
                    key = { "qa:${it.nodeId}" }
                ) { entry ->
                    ElevatedCard(Modifier.fillMaxWidth().testTag("QaRowCard_${safeTag(entry.nodeId)}")) {
                        Column(Modifier.padding(12.dp)) {
                            QaRow(
                                entry = entry,
                                labelStyle = labelTight,
                                bodyStyle = bodyTight
                            )
                        }
                    }
                }
            }

            // Followups section (card container + virtualized rows)
            item {
                ElevatedCard(Modifier.fillMaxWidth().testTag("FollowupsCard")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Follow-up History",
                            style = titleTight,
                            color = cs.onSurface
                        )
                        Spacer(Modifier.height(6.dp))

                        if (followupRows.isEmpty()) {
                            Text(
                                text = "No follow-up questions.",
                                style = bodyTight,
                                color = cs.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "Showing follow-ups grouped by node.",
                                style = labelTight,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (followupRows.isNotEmpty()) {
                stickyHeader {
                    // Simple visual separator between sections, stable when scrolling.
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        color = cs.outlineVariant.copy(alpha = 0.25f)
                    )
                }

                items(
                    items = followupRows,
                    key = { row -> row.key() }
                ) { row ->
                    when (row) {
                        is FollowupRow.Header -> {
                            FollowupHeaderRow(
                                nodeId = row.nodeId,
                                labelStyle = labelTight
                            )
                        }

                        is FollowupRow.Empty -> {
                            FollowupEmptyRow(
                                bodyStyle = bodyTight
                            )
                        }

                        is FollowupRow.Item -> {
                            FollowupItemRow(
                                index1 = row.index1,
                                question = row.question,
                                answer = row.answer,
                                bodyStyle = bodyTight
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp)
                        .testTag("ReviewNavRow")
                ) {
                    Button(
                        onClick = onBack,
                        modifier = Modifier.weight(1f).testTag("ReviewBackButton")
                    ) {
                        Text("Back")
                    }
                    Button(
                        onClick = onNext,
                        modifier = Modifier.weight(1f).testTag("ReviewNextButton")
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

/* ============================================================
 * Models
 * ============================================================ */

private data class QaEntry(
    val nodeId: String,
    val question: String,
    val answer: String
)

private sealed class FollowupRow {
    data class Header(val nodeId: String) : FollowupRow()
    data class Empty(val nodeId: String) : FollowupRow()
    data class Item(
        val nodeId: String,
        val index1: Int,
        val question: String,
        val answer: String?
    ) : FollowupRow()

    fun key(): String = when (this) {
        is Header -> "fu:hdr:$nodeId"
        is Empty -> "fu:empty:$nodeId"
        is Item -> "fu:item:$nodeId:$index1"
    }
}

/* ============================================================
 * UI rows
 * ============================================================ */

@Composable
private fun QaRow(
    entry: QaEntry,
    labelStyle: TextStyle,
    bodyStyle: TextStyle
) {
    val cs = MaterialTheme.colorScheme

    Column {
        Text(
            text = entry.nodeId,
            style = labelStyle,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))

        val q = entry.question.normalizeForUi()
        val qText = if (q.isBlank()) "– No Question." else "Q: $q"
        Text(
            text = qText,
            style = bodyStyle,
            color = if (q.isBlank()) cs.onSurface.copy(alpha = 0.6f) else cs.onSurface,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(2.dp))

        val a = entry.answer.normalizeForUi()
        val aText = if (a.isBlank()) "– No Answer." else a
        Text(
            text = "A: $aText",
            style = bodyStyle,
            color = if (a.isBlank()) cs.onSurface.copy(alpha = 0.6f) else cs.onSurface,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FollowupHeaderRow(
    nodeId: String,
    labelStyle: TextStyle
) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = "Node: $nodeId",
        style = labelStyle,
        color = cs.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .testTag("FollowupHeader_${safeTag(nodeId)}")
    )
}

@Composable
private fun FollowupEmptyRow(
    bodyStyle: TextStyle
) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = "– No follow-ups recorded.",
        style = bodyStyle,
        color = cs.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, bottom = 6.dp)
            .testTag("FollowupEmptyRow")
    )
}

@Composable
private fun FollowupItemRow(
    index1: Int,
    question: String,
    answer: String?,
    bodyStyle: TextStyle
) {
    val cs = MaterialTheme.colorScheme
    val q = question.normalizeForUi()
    val a = (answer ?: "").normalizeForUi()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, bottom = 6.dp)
            .testTag("FollowupItemRow_$index1")
    ) {
        Text(
            text = "$index1. Q: ${if (q.isBlank()) "–" else q}",
            style = bodyStyle,
            color = cs.onSurface,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "   A: ${if (a.isBlank()) "– No Answer." else a}",
            style = bodyStyle,
            color = if (a.isBlank()) cs.onSurface.copy(alpha = 0.6f) else cs.onSurface,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ============================================================
 * Helpers
 * ============================================================ */

private fun String.normalizeForUi(): String {
    // Keep it deterministic and compact:
    // - normalize CRLF to LF
    // - trim outer whitespace
    // - collapse excessive internal blank lines a bit (without destroying content)
    val s = replace("\r\n", "\n").trim()
    if (s.isEmpty()) return ""
    // Reduce 3+ newlines to 2 newlines to prevent huge vertical expansion in dense UI.
    return s.replace(Regex("\n{3,}"), "\n\n")
}

private fun safeTag(raw: String): String {
    return raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
