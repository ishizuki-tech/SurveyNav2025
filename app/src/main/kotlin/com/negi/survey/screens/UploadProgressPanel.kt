/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadProgressPanel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Floating HUD-style overlay that visualizes background GitHub uploads
 *  driven by WorkManager. The overlay:
 *    • Listens to a queue-oriented ViewModel ([UploadQueueViewModel]).
 *    • Shows a compact card whenever there is at least one ENQUEUED,
 *      RUNNING, or BLOCKED work item.
 *    • Renders per-item progress, state labels, and optional deep link
 *      buttons for completed uploads.
 *
 *  Notes:
 *    • The overlay is layout-neutral and can be added at the root of any
 *      screen. It uses a Box that fills the window and anchors the card
 *      near the top center, below the status bar (HUD-style).
 *    • Color and typography are driven entirely by [MaterialTheme].
 * =====================================================================
 */

package com.negi.survey.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import com.negi.survey.vm.UploadItemUi
import com.negi.survey.vm.UploadQueueViewModel
import kotlin.math.roundToInt

/**
 * Floating HUD-style overlay that shows background upload progress
 * for the current app.
 *
 * This composable listens to [UploadQueueViewModel.itemsFlow] and renders:
 *  - Optionally a scrim behind the overlay to focus attention.
 *  - A top-anchored card listing all relevant upload items.
 *
 * Visibility is automatically controlled: the overlay only appears when at
 * least one item is in [WorkInfo.State.RUNNING], [WorkInfo.State.ENQUEUED],
 * or [WorkInfo.State.BLOCKED].
 *
 * @param vm ViewModel that exposes the current upload queue. A default
 *           instance is created using [viewModel] with an Application-based
 *           factory.
 * @param showScrim Whether to draw a translucent scrim over the entire
 *                  content while uploads are in progress.
 */
@Composable
fun UploadProgressOverlay(
    vm: UploadQueueViewModel = viewModel(
        factory = UploadQueueViewModel.factory(
            LocalContext.current.applicationContext as Application
        )
    ),
    showScrim: Boolean = false
) {
    val items by vm.itemsFlow.collectAsState(initial = emptyList())

    // Derived visibility based on the aggregated item states.
    val visible by remember(items) {
        derivedStateOf {
            items.any {
                it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Optional scrim to visually separate the overlay from the app content.
        AnimatedVisibility(
            visible = showScrim && visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            )
        }

        // Top-anchored HUD card.
        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            // Outer “rim” for a subtle neon-glass effect.
            val rimShape = RoundedCornerShape(20.dp)
            val primary = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            val tertiary = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)

            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .drawBehind {
                        val strokeWidth = 8f
                        drawRoundRect(
                            brush = Brush.linearGradient(listOf(primary, tertiary)),
                            size = size,
                            cornerRadius = CornerRadius(24f, 24f),
                            style = Stroke(width = strokeWidth)
                        )
                    }
                    .padding(4.dp)
            ) {
                ElevatedCard(
                    shape = rimShape,
                    colors = CardDefaults.elevatedCardColors(
                        // Slight tint to lift the card above background/scrim.
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Background uploads",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.semantics { heading() }
                        )
                        Spacer(Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Use stable keys; prefer work id, fall back to fileName.
                            items(items, key = { it.id }) { item ->
                                UploadRowFancy(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single row that represents one upload job.
 *
 * The row includes:
 *  - An icon and file name.
 *  - A status chip (Queued / Uploading / Done / Failed / Cancelled).
 *  - A linear progress indicator (indeterminate or determinate).
 *  - Optional percentage text for determinate progress.
 *  - Optional "Open on GitHub" deep link when the job has a [UploadItemUi.fileUrl].
 *
 * Visual style:
 *  - Soft vertical gradient using the state accent color.
 *  - Dense but readable layout tuned for small cards.
 *
 * @param u Presentation model for an upload work item.
 */
@Composable
private fun UploadRowFancy(u: UploadItemUi) {
    val ctx = LocalContext.current

    // Map WorkManager state to accent color, icon, and short label.
    val (accent, icon, label) = styleFor(u.state)

    // Target progress in [0f, 1f] where null means indeterminate.
    val target = u.percent
        ?.coerceIn(0, 100)
        ?.div(100f)
        ?: when (u.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> null
            WorkInfo.State.RUNNING -> 0f
            WorkInfo.State.SUCCEEDED -> 1f
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> 0f
        }

    val animProgress by animateFloatAsState(
        targetValue = target ?: 0f,
        label = "upload_progress"
    )

    // Background strip that uses a low-alpha gradient from accent → transparent.
    val rowGradient = Brush.verticalGradient(
        colors = listOf(
            accent.copy(alpha = 0.10f),
            Color.Transparent
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowGradient, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        // Header row: icon + file name + status chip.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = u.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            AssistChip(
                onClick = { /* decorative status only */ },
                label = { Text(label) },
                leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = accent.copy(alpha = 0.15f),
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconContentColor = accent
                )
            )
        }

        Spacer(Modifier.height(6.dp))

        // Optional message below the header (e.g., "retry scheduled", etc.).
        u.message
            ?.takeIf { it.isNotBlank() }
            ?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }

        // Progress area rendered per state; ensures consistent vertical layout.
        when (u.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
            }

            WorkInfo.State.RUNNING -> {
                LinearProgressIndicator(
                    progress = { animProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
                if (u.percent != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${(animProgress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
                if (!u.fileUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u.fileUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(intent) }
                        }
                    ) {
                        Text("Open on GitHub")
                    }
                }
            }

            WorkInfo.State.FAILED -> {
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Upload failed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                // If needed, retry or details actions can be wired here.
            }

            WorkInfo.State.CANCELLED -> {
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Upload cancelled.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Style helpers                                                             */
/* ------------------------------------------------------------------------- */

/**
 * Map [WorkInfo.State] to a triple of:
 *  - Accent [Color]
 *  - [ImageVector] icon
 *  - Short status label
 *
 * The returned accent color is always derived from [MaterialTheme.colorScheme]
 * to keep the overlay aligned with the app theme.
 */
@Composable
private fun styleFor(state: WorkInfo.State): Triple<Color, ImageVector, String> {
    val c = MaterialTheme.colorScheme
    return when (state) {
        WorkInfo.State.RUNNING ->
            Triple(c.primary, Icons.Outlined.CloudUpload, "Uploading")

        WorkInfo.State.ENQUEUED ->
            Triple(c.secondary, Icons.Outlined.CloudQueue, "Queued")

        WorkInfo.State.BLOCKED ->
            Triple(c.tertiary, Icons.Outlined.Block, "Blocked")

        WorkInfo.State.SUCCEEDED ->
            Triple(c.inversePrimary, Icons.Outlined.CloudDone, "Done")

        WorkInfo.State.FAILED ->
            Triple(c.error, Icons.Outlined.ErrorOutline, "Failed")

        WorkInfo.State.CANCELLED ->
            Triple(c.error, Icons.Outlined.ErrorOutline, "Cancelled")
    }
}
