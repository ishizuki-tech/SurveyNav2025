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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 *  - Typography is intentionally tightened (smaller font + line height) but
 *    still tied to [MaterialTheme.typography] for consistency.
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
    // Compact typography presets (tight but readable for dense lists).
    val baseCompact = MaterialTheme.typography.bodySmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
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

    // Memoized, sorted views for stable item ordering.
    val qaEntries = remember(allAnswers, allQuestions) {
        // Pair nodeId with question + answer, sorted by nodeId for predictability.
        allAnswers.entries
            .map { (id, ans) ->
                val q = allQuestions[id].orEmpty()
                Triple(id, q, ans)
            }
            .sortedBy { it.first }
    }

    val sortedFollowups = remember(allFollowups) {
        // Sort nodes by id; keep per-node follow-ups in insertion order.
        allFollowups.toSortedMap()
    }

    CompositionLocalProvider(LocalTextStyle provides baseCompact) {
        Scaffold(containerColor = Color.Transparent) { pad ->
            // LazyColumn provides smoother scrolling as questions grow.
            LazyColumn(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header.
                item {
                    Text("Review", style = titleTight)
                }

                // Q & A Card.
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "All Original Questions and Answers",
                                style = titleTight
                            )
                            Spacer(Modifier.height(6.dp))

                            if (qaEntries.isEmpty()) {
                                Text("No records yet.", style = bodyTight)
                            } else {
                                qaEntries.forEachIndexed { idx, (nodeId, question, answer) ->
                                    if (idx > 0) {
                                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                    }

                                    Column {
                                        // Node id label.
                                        Text(
                                            text = nodeId,
                                            style = labelTight,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(2.dp))

                                        // Question (show placeholder when missing).
                                        val qText =
                                            if (question.isBlank()) "– No Question."
                                            else "Q: $question"
                                        Text(qText, style = bodyTight)

                                        Spacer(Modifier.height(2.dp))

                                        // Answer (highlight missing answers with lower alpha).
                                        val aText =
                                            if (answer.isBlank()) "– No Answer."
                                            else answer
                                        Text(
                                            text = "A: $aText",
                                            maxLines = 6,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (answer.isBlank()) {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            style = bodyTight
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Follow-ups Card.
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Follow-up History", style = titleTight)
                            Spacer(Modifier.height(6.dp))

                            if (sortedFollowups.isEmpty()) {
                                Text("No follow-up questions.", style = bodyTight)
                            } else {
                                sortedFollowups.entries.forEachIndexed { idx, (nodeId, list) ->
                                    if (idx > 0) {
                                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                    }

                                    Text(
                                        text = "Node: $nodeId",
                                        style = labelTight,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    if (list.isEmpty()) {
                                        Text("– No follow-ups recorded.", style = bodyTight)
                                    } else {
                                        list.forEachIndexed { i, entry ->
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "${i + 1}. Q: ${entry.question}",
                                                style = bodyTight
                                            )
                                            Text(
                                                text = "   A: ${entry.answer ?: "– No Answer."}",
                                                style = bodyTight,
                                                color = if (entry.answer.isNullOrBlank()) {
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
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
                    ) {
                        Button(
                            onClick = onBack,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Back")
                        }
                        Button(
                            onClick = onNext,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Next")
                        }
                    }
                }
            }
        }
    }
}
