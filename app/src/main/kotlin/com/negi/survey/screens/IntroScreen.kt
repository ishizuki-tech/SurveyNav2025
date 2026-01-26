/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: IntroScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Monotone (black/gray/white) intro screen for the survey flow.
 *
 *  Update (2026-01):
 *   • Added a "Selected configuration details" panel.
 *   • Supports long details with expand/collapse.
 *   • Optional key/value meta rendering for configs.
 *
 *  Debug/UX fix (2026-01):
 *   • Fix: "Show more" appeared to do nothing when expanded content was clipped off-screen.
 *     - Card content is now height-clamped + vertically scrollable.
 *   • Fix: Show more button now appears only if it would actually expand something.
 *     - Uses onTextLayout overflow detection.
 *   • UX: When toggling "Show more", auto-scroll the details panel into view.
 *     - Uses BringIntoViewRequester so it feels responsive.
 *   • Added test tags for UI testing.
 *
 *  New (2026-01):
 *   • Show details of the CURRENT config by resolving details from selected id.
 *     - Pass onResolveConfigDetails(configId) to load YAML/config text asynchronously.
 * =====================================================================
 */

package com.negi.survey.screens

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "IntroScreen"

/**
 * UI-facing descriptor of a survey configuration option.
 *
 * NOTE:
 * - details/meta here are optional and can be empty.
 * - The actual "current config details" are resolved via onResolveConfigDetails(configId).
 */
data class ConfigOptionUi(
    val id: String,
    val label: String,
    val description: String,
)

/**
 * Resolved details for a selected config.
 *
 * @param title Display title (e.g., file name or config name)
 * @param summary Short description (1-2 lines) shown near the top
 * @param longText Long content (e.g., YAML excerpt / normalized config text)
 * @param meta Key/value important knobs (backend, model, maxTokens, etc.)
 */
data class ConfigDetails(
    val title: String,
    val summary: String,
    val longText: String,
    val meta: Map<String, String> = emptyMap()
)

/**
 * Intro screen rendered in a strict grayscale palette.
 *
 * @param onResolveConfigDetails Called when selected config id changes.
 *        Implement this in your ViewModel/Repository to load YAML or config and return a display-ready ConfigDetails.
 */
@Composable
fun IntroScreen(
    options: List<ConfigOptionUi>,
    defaultOptionId: String? = null,
    onStart: (ConfigOptionUi) -> Unit,
    restartEpoch: Long = 0L,
    showRestart: Boolean = false,
    isRestarting: Boolean = false,
    onRestart: (() -> Unit)? = null,
    onResolveConfigDetails: suspend (configId: String) -> ConfigDetails,
) {
    require(options.isNotEmpty()) { "IntroScreen requires at least one ConfigOptionUi." }

    val bgBrush = animatedMonotoneBackground()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .semantics { contentDescription = "Survey intro screen" }
            .testTag("IntroScreenRoot")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            IntroCardMono(
                title = "Survey Test App",
                subtitle = "A focused, privacy-friendly evaluation flow",
                options = options,
                defaultOptionId = defaultOptionId,
                onStart = onStart,
                restartEpoch = restartEpoch,
                showRestart = showRestart,
                isRestarting = isRestarting,
                onRestart = onRestart,
                onResolveConfigDetails = onResolveConfigDetails,
            )
        }
    }
}

/* ──────────────────────────── Card & Typography ─────────────────────────── */

@Composable
private fun IntroCardMono(
    title: String,
    subtitle: String,
    options: List<ConfigOptionUi>,
    defaultOptionId: String?,
    onStart: (ConfigOptionUi) -> Unit,
    restartEpoch: Long,
    showRestart: Boolean,
    isRestarting: Boolean,
    onRestart: (() -> Unit)?,
    onResolveConfigDetails: suspend (configId: String) -> ConfigDetails,
) {
    val cs = MaterialTheme.colorScheme
    val corner = 20.dp

    val textPrimary = Color(0xFFF2F2F2)
    val textSecondary = Color(0xFFD2D2D2)
    val textMuted = Color(0xFFB0B0B0)
    val textHint = Color(0xFF9A9A9A)

    val cardBg = Color(0xFF101010).copy(alpha = 0.86f)

    val optionIds = remember(options) { options.map { it.id } }

    var selectedId by remember(optionIds, defaultOptionId, restartEpoch) {
        mutableStateOf(
            defaultOptionId
                ?.takeIf { it.isNotBlank() && optionIds.contains(it) }
                ?: options.first().id
        )
    }

    LaunchedEffect(optionIds, restartEpoch) {
        if (!optionIds.contains(selectedId)) {
            selectedId = options.first().id
        }
    }

    val selectedOption = options.firstOrNull { it.id == selectedId } ?: options.first()

    var detailsState by remember(restartEpoch, selectedId) {
        mutableStateOf<ResolvedDetailsState>(ResolvedDetailsState.Loading)
    }

    LaunchedEffect(restartEpoch, selectedId) {
        detailsState = ResolvedDetailsState.Loading
        try {
            val d = onResolveConfigDetails(selectedId)
            detailsState = ResolvedDetailsState.Ready(d)
        } catch (e: CancellationException) {
            // Important: do not treat cancellation as an error.
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to resolve config details for id=$selectedId", t)
            detailsState = ResolvedDetailsState.Error(t)
        }
    }

    ElevatedCard(
        shape = RoundedCornerShape(corner),
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardBg,
            contentColor = textPrimary
        ),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .wrapContentHeight()
            .wrapContentWidth()
            .drawBehind {
                val dark = Color(0xFF2A2A2A).copy(alpha = 0.65f)
                val mid = Color(0xFF7A7A7A).copy(alpha = 0.35f)
                val light = Color(0xFFF0F0F0).copy(alpha = 0.18f)
                val sweep = Brush.sweepGradient(
                    0f to dark,
                    0.25f to mid,
                    0.5f to light,
                    0.75f to mid,
                    1f to dark
                )
                drawRoundRect(
                    brush = sweep,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(corner.toPx(), corner.toPx())
                )
            }
    ) {
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val scroll = rememberScrollState()

        Column(
            modifier = Modifier
                .heightIn(max = screenH * 0.82f)
                .verticalScroll(scroll)
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GradientHeadlineMono(
                text = title,
                colorTop = Color(0xFFF5F5F5),
                colorBottom = Color(0xFFCFCFCF)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                color = textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Select survey configuration",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.2.sp),
                color = textMuted,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ConfigSelectorColumn"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                options.forEach { option ->
                    MonoConfigOptionChip(
                        option = option,
                        selected = option.id == selectedId,
                        onClick = { selectedId = option.id },
                        textPrimary = textPrimary,
                        textSecondary = textHint
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            SelectedConfigDetailsMono(
                configId = selectedId,
                optionLabel = selectedOption.label,
                optionDescription = selectedOption.description,
                state = detailsState,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                textMuted = textMuted,
            )

            Spacer(Modifier.height(12.dp))

            HorizontalDivider(
                thickness = 1.dp,
                color = cs.outlineVariant.copy(alpha = 0.22f)
            )

            Spacer(Modifier.height(16.dp))

            CtaRowMono(
                onStart = { onStart(selectedOption) },
                showRestart = showRestart,
                isRestarting = isRestarting,
                onRestart = onRestart
            )
        }
    }
}

private sealed class ResolvedDetailsState {
    data object Loading : ResolvedDetailsState()
    data class Ready(val details: ConfigDetails) : ResolvedDetailsState()
    data class Error(val error: Throwable) : ResolvedDetailsState()
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SelectedConfigDetailsMono(
    configId: String,
    optionLabel: String,
    optionDescription: String,
    state: ResolvedDetailsState,
    textPrimary: Color,
    textSecondary: Color,
    textMuted: Color,
) {
    val bg = Color(0xFF0E0E0E).copy(alpha = 0.85f)
    val border = Color(0xFF7A7A7A).copy(alpha = 0.35f)

    var expanded by remember(configId) { mutableStateOf(false) }

    var descOverflow by remember(configId) { mutableStateOf(false) }
    var summaryOverflow by remember(configId) { mutableStateOf(false) }
    var longOverflow by remember(configId) { mutableStateOf(false) }

    val bringIntoViewRequester = remember(configId) { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .animateContentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("SelectedConfigDetails"),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Selected configuration",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.2.sp),
            color = textMuted
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = optionLabel,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                lineHeight = 22.sp
            ),
            color = textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = optionDescription,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { r ->
                if (!expanded) descOverflow = r.hasVisualOverflow
            },
            modifier = Modifier.testTag("SelectedConfigDesc")
        )

        Spacer(Modifier.height(10.dp))

        when (state) {
            is ResolvedDetailsState.Loading -> {
                Text(
                    text = "Loading config: $configId …",
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = Color(0xFFBDBDBD),
                    modifier = Modifier.testTag("ConfigDetailsLoading")
                )
            }

            is ResolvedDetailsState.Error -> {
                Text(
                    text = "Failed to load config: $configId",
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = Color(0xFFFFC8C8),
                    modifier = Modifier.testTag("ConfigDetailsErrorTitle")
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = (state.error.message ?: state.error::class.java.simpleName).take(240),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = Color(0xFFE0A0A0),
                    modifier = Modifier.testTag("ConfigDetailsErrorMessage")
                )
            }

            is ResolvedDetailsState.Ready -> {
                val d = state.details

                Text(
                    text = d.title,
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
                    color = Color(0xFFE6E6E6),
                    modifier = Modifier.testTag("ConfigDetailsTitle")
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = d.summary,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = Color(0xFFCFCFCF),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { r ->
                        if (!expanded) summaryOverflow = r.hasVisualOverflow
                    },
                    modifier = Modifier.testTag("ConfigDetailsSummary")
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = d.longText,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = Color(0xFFBDBDBD),
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { r ->
                        if (!expanded) longOverflow = r.hasVisualOverflow
                    },
                    modifier = Modifier.testTag("ConfigDetailsLongText")
                )

                if (d.meta.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("SelectedConfigMetaRow"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        d.meta.forEach { (k, v) ->
                            MetaChipMono(label = k, value = v)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        val canExpandReadyContent = summaryOverflow || longOverflow
        val canExpandOptionDesc = descOverflow

        val showToggle = (!expanded) && (canExpandReadyContent || canExpandOptionDesc)
        val showCollapse = expanded

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetaChipMono(label = "id", value = configId)

            if (showToggle || showCollapse) {
                TextButton(
                    onClick = {
                        val next = !expanded
                        expanded = next
                        Log.d(TAG, "ShowMore toggle: optionId=$configId expanded=$expanded")
                        if (next) {
                            scope.launch { bringIntoViewRequester.bringIntoView() }
                        }
                    },
                    modifier = Modifier.testTag("ShowMoreButton"),
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text(
                        text = if (expanded) "Show less" else "Show more",
                        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp),
                        color = Color(0xFFE6E6E6)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaChipMono(label: String, value: String) {
    val safeLabel = remember(label) { label.safeTestTagToken(24) }
    val safeValue = remember(value) { value.safeTestTagToken(24) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF151515))
            .border(
                BorderStroke(1.dp, Color(0xFF8A8A8A).copy(alpha = 0.35f)),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("MetaChip_${safeLabel}_${safeValue}")
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
            color = Color(0xFFBDBDBD)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = Color(0xFFF0F0F0),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CtaRowMono(
    onStart: () -> Unit,
    showRestart: Boolean,
    isRestarting: Boolean,
    onRestart: (() -> Unit)?,
) {
    var confirmRestart by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onStart,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color(0xFFF2F2F2)
            ),
            modifier = Modifier.testTag("StartButton")
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowForward,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Start",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                )
            )
        }

        if (showRestart && onRestart != null) {
            Spacer(Modifier.width(12.dp))

            OutlinedButton(
                enabled = !isRestarting,
                onClick = { confirmRestart = true },
                border = BorderStroke(1.dp, Color(0xFF8A8A8A).copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFE0E0E0)
                ),
                modifier = Modifier.testTag("RestartButton")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRestarting) "Restarting..." else "Restart",
                    style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.2.sp)
                )
            }
        }
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart engine?") },
            text = { Text("This cancels current work and recreates the engine/session.") },
            confirmButton = {
                Button(
                    enabled = !isRestarting,
                    onClick = {
                        confirmRestart = false
                        onRestart?.invoke()
                    }
                ) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun GradientHeadlineMono(
    text: String,
    colorTop: Color,
    colorBottom: Color
) {
    val brush = Brush.verticalGradient(
        0f to colorTop,
        1f to colorBottom
    )
    val label = buildAnnotatedString {
        withStyle(SpanStyle(brush = brush)) { append(text) }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            lineHeight = 30.sp,
            letterSpacing = 0.2.sp
        ),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun MonoConfigOptionChip(
    option: ConfigOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
    textPrimary: Color,
    textSecondary: Color
) {
    val background = if (selected) Color(0xFF1A1A1A) else Color(0xFF141414)
    val borderColor = if (selected) {
        Color(0xFFE6E6E6).copy(alpha = 0.90f)
    } else {
        Color(0xFF8A8A8A).copy(alpha = 0.55f)
    }
    val labelColor = if (selected) textPrimary else Color(0xFFE8E8E8)
    val descColor = if (selected) Color(0xFFC8C8C8) else textSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(14.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .testTag("ConfigOption_${option.id.safeTestTagToken(32)}")
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    lineHeight = 22.sp
                ),
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = descColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        val strokeWidth = 1.6.dp.toPx()
                        drawCircle(
                            color = Color.White.copy(alpha = 0.88f),
                            style = Stroke(width = strokeWidth)
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.88f),
                            radius = size.minDimension / 2.5f
                        )
                    }
            )
        }
    }
}

@Composable
private fun animatedMonotoneBackground(): Brush {
    val t = rememberInfiniteTransition(label = "mono-bg")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mono-bg-p"
    )

    val c0 = Color(0xFF080808)
    val c1 = Color(0xFF101010)
    val c2 = Color(0xFF181818)
    val c3 = Color(0xFF222222)

    val endX = 900f + 280f * p
    val endY = 720f - 220f * p

    return Brush.linearGradient(
        colors = listOf(c0, c1, c2, c3),
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}

private fun String.safeTestTagToken(maxLen: Int): String {
    val cleaned = buildString(length) {
        for (ch in this@safeTestTagToken) {
            val ok = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.'
            append(if (ok) ch else '_')
        }
    }
    return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen)
}
