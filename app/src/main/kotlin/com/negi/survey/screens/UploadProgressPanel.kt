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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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

    /**
     * Visibility should be derived directly from the latest collected list.
     *
     * Using derivedStateOf here would not provide value because [items] is a
     * plain List after delegation; the calculation is cheap and stable.
     */
    val visible = items.any {
        it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
    }

    /**
     * Keep a stable display order to reduce HUD jitter.
     */
    val displayItems = remember(items) {
        val rank = stateRankMap()
        items.sortedWith(
            compareBy<UploadItemUi>(
                { rank[it.state] ?: 99 },
                { it.fileName.lowercase() }
            )
        )
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
            // Outer rim for a subtle glass-like lift.
            val rimShape = RoundedCornerShape(20.dp)
            val primary = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            val tertiary = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)

            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .drawBehind {
                        val strokeWidth = 2.dp.toPx()
                        drawRoundRect(
                            brush = Brush.linearGradient(listOf(primary, tertiary)),
                            size = size,
                            cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                            style = Stroke(width = strokeWidth)
                        )
                    }
                    .padding(4.dp)
            ) {
                ElevatedCard(
                    shape = rimShape,
                    colors = CardDefaults.elevatedCardColors(
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
                            // Use stable keys whenever possible.
                            items(
                                items = displayItems,
                                key = { it.id.ifBlank { it.fileName } }
                            ) { item ->
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

    /**
     * Determine a normalized progress target.
     *
     * - When percent is available, we animate deterministically.
     * - When percent is missing and state is RUNNING, we fall back to
     *   indeterminate progress to avoid showing a misleading 0% bar.
     */
    val percent01 = u.percent
        ?.coerceIn(0, 100)
        ?.div(100f)

    val animProgress by animateFloatAsState(
        targetValue = percent01 ?: 0f,
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
                enabled = false,
                label = { Text(label) },
                leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = accent.copy(alpha = 0.15f),
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconContentColor = accent,
                    disabledContainerColor = accent.copy(alpha = 0.15f),
                    disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                    disabledLeadingIconContentColor = accent
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
                if (percent01 == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = accent
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent
                    )
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

/**
 * Provide a stable ordering rank for WorkManager states.
 *
 * Lower values appear earlier in the HUD.
 */
private fun stateRankMap(): Map<WorkInfo.State, Int> = mapOf(
    WorkInfo.State.RUNNING to 0,
    WorkInfo.State.ENQUEUED to 1,
    WorkInfo.State.BLOCKED to 2,
    WorkInfo.State.FAILED to 3,
    WorkInfo.State.CANCELLED to 4,
    WorkInfo.State.SUCCEEDED to 5
)
