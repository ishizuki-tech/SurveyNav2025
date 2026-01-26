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
 *    • Single LazyColumn inside a Scaffold, tuned for dense but readable
 *      typography (reduced font size + line height).
 *    • Two ElevatedCards: one for Q/A, one for follow-up history.
 *    • Bottom row of navigation buttons (“Back” / “Next”).
 *
 *  Notes:
 *    • Ordering is stabilized by sorting on nodeId, so repeated visits to
 *      this screen show a predictable layout.
 *    • Q/A section is built from the union of question + answer keys to avoid
 *      dropping unanswered or question-only nodes.
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

/**
 * Review screen that renders a compact, read-only summary of the session.
 *
 * Responsibilities:
 *  - Read questions, answers, and follow-up entries from [SurveyViewModel].
 *  - Provide a dense but legible overview of:
 *      • All original questions and their current answers.
 *      • Follow-up questions grouped by owner node and their answers.
 *  - Expose simple navigation callbacks for returning to the flow or moving
 *    on to the final “Done” screen.
 *
 * Implementation details:
 *  - Uses [LazyColumn] for scalable performance on large interviews.
 *    NOTE: The two-card layout still renders card contents eagerly within each card.
 *    For extremely large sessions, consider adding a collapse/expand or paging strategy.
 *
 * @param vm Backing ViewModel providing the survey state.
 * @param onNext Invoked when the user presses the “Next” button.
 * @param onBack Invoked when the user presses the “Back” button.
 */
@Composable
fun ReviewScreen(
    vm: SurveyViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    // Compact typography presets (tight but readable for dense lists).
    val titleTight = MaterialTheme.typography.titleSmall.copy(
        fontSize = 12.sp,
        lineHeight = 14.sp
    )
    val labelTight = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 12.sp
    )
    val bodyTight = MaterialTheme.typography.bodySmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp
    )

    // Collect VM state with explicit empty defaults for safety.
    val allQuestions by vm.questions.collectAsState(initial = emptyMap())
    val allAnswers by vm.answers.collectAsState(initial = emptyMap())
    val allFollowups by vm.followups.collectAsState(initial = emptyMap())

    // Sorting strategy:
    // - Default: lexical (stable, simplest).
    // - Swap in a "natural order" comparator if needed (e.g., Q2 < Q10).
    val nodeIdComparator = remember {
        Comparator<String> { a, b -> a.compareTo(b) }
    }

    /**
     * Build a stable union of node IDs that own either a question or an answer.
     *
     * This prevents the review screen from dropping:
     *  - unanswered nodes,
     *  - question-only nodes,
     *  - legacy call sites that set answers before questions.
     */
    val qaOwnerIds by remember(allQuestions, allAnswers) {
        derivedStateOf {
            (allQuestions.keys + allAnswers.keys)
                .toSet()
                .toList()
                .sortedWith(nodeIdComparator)
        }
    }

    /**
     * Stable Q/A entries derived from the union list above.
     */
    val qaEntries by remember(qaOwnerIds, allQuestions, allAnswers) {
        derivedStateOf {
            qaOwnerIds.map { id ->
                QaEntry(
                    nodeId = id,
                    question = allQuestions[id].orEmpty(),
                    answer = allAnswers[id].orEmpty()
                )
            }
        }
    }

    /**
     * Follow-ups sorted by node ID for predictable layout.
     *
     * The per-node list order is preserved as-is to respect insertion/creation order.
     */
    val sortedFollowups by remember(allFollowups) {
        derivedStateOf {
            allFollowups.entries
                .sortedWith(compareBy(nodeIdComparator) { it.key })
        }
    }

    Scaffold(
        containerColor = Color.Transparent
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("ReviewScreenRoot"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header.
            item {
                Text(
                    text = "Review",
                    style = titleTight,
                    color = cs.onSurface,
                    modifier = Modifier.testTag("ReviewHeader")
                )
            }

            // Q & A Card.
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ReviewQaCard")
                ) {
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
                        } else {
                            qaEntries.forEachIndexed { idx, entry ->
                                if (idx > 0) {
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                }

                                QaRow(
                                    entry = entry,
                                    labelStyle = labelTight,
                                    bodyStyle = bodyTight
                                )
                            }
                        }
                    }
                }
            }

            // Follow-ups Card.
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ReviewFollowupCard")
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Follow-up History",
                            style = titleTight,
                            color = cs.onSurface
                        )
                        Spacer(Modifier.height(6.dp))

                        if (sortedFollowups.isEmpty()) {
                            Text(
                                text = "No follow-up questions.",
                                style = bodyTight,
                                color = cs.onSurfaceVariant
                            )
                        } else {
                            sortedFollowups.forEachIndexed { idx, (nodeId, list) ->
                                if (idx > 0) {
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                }

                                Text(
                                    text = "Node: $nodeId",
                                    style = labelTight,
                                    color = cs.onSurfaceVariant
                                )

                                if (list.isEmpty()) {
                                    Text(
                                        text = "– No follow-ups recorded.",
                                        style = bodyTight,
                                        color = cs.onSurfaceVariant
                                    )
                                } else {
                                    list.forEachIndexed { i, entry ->
                                        Spacer(Modifier.height(4.dp))

                                        val q = entry.question.takeIf { it.isNotBlank() } ?: "– No Question."
                                        val a = entry.answer?.takeIf { it.isNotBlank() } ?: "– No Answer."

                                        Text(
                                            text = "${i + 1}. Q: $q",
                                            style = bodyTight,
                                            color = if (entry.question.isBlank()) {
                                                cs.onSurface.copy(alpha = 0.6f)
                                            } else {
                                                cs.onSurface
                                            }
                                        )
                                        Text(
                                            text = "   A: $a",
                                            style = bodyTight,
                                            color = if (entry.answer.isNullOrBlank()) {
                                                cs.onSurface.copy(alpha = 0.6f)
                                            } else {
                                                cs.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom buttons.
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
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ReviewBackButton")
                    ) {
                        Text("Back")
                    }
                    Button(
                        onClick = onNext,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ReviewNextButton")
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

/**
 * Immutable view model for a single Q/A row in the review card.
 */
private data class QaEntry(
    val nodeId: String,
    val question: String,
    val answer: String
)

/**
 * Render a compact Q/A row with consistent missing-value styling.
 */
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

        val q = entry.question.takeIf { it.isNotBlank() } ?: "– No Question."
        Text(
            text = if (entry.question.isBlank()) q else "Q: $q",
            style = bodyStyle,
            color = if (entry.question.isBlank()) {
                cs.onSurface.copy(alpha = 0.6f)
            } else {
                cs.onSurface
            }
        )

        Spacer(Modifier.height(2.dp))

        val a = entry.answer.takeIf { it.isNotBlank() } ?: "– No Answer."
        Text(
            text = "A: $a",
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            style = bodyStyle,
            color = if (entry.answer.isBlank()) {
                cs.onSurface.copy(alpha = 0.6f)
            } else {
                cs.onSurface
            }
        )
    }
}
