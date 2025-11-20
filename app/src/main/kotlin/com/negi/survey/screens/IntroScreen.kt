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
 *  Layout model:
 *   • Full-screen Box with an animated dark monotone gradient background.
 *   • Centered glass-like card that introduces the app and exposes a single
 *     “Start” CTA.
 *   • Inside the card, a small monotone selector lets the user choose which
 *     survey configuration to use (e.g., which YAML file to load).
 * =====================================================================
 */

package com.negi.survey.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * UI-facing descriptor of a survey configuration option.
 *
 * @param id Stable identifier such as an asset file name or config key.
 * @param label Short label rendered as the primary text of the option chip.
 * @param description Supporting line describing what the option represents.
 */
data class ConfigOptionUi(
    val id: String,
    val label: String,
    val description: String
)

/**
 * Intro screen rendered in a strict grayscale palette.
 *
 * Responsibilities:
 *  - Paint an animated dark monotone background for the content region.
 *  - Present a centered intro card with title, subtitle, a configuration
 *    selector, and a single “Start” button.
 */
@Composable
fun IntroScreen(
    options: List<ConfigOptionUi>,
    defaultOptionId: String? = null,
    onStart: (ConfigOptionUi) -> Unit,
) {
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
                onStart = onStart
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
    onStart: (ConfigOptionUi) -> Unit
) {
    require(options.isNotEmpty()) {
        "IntroCardMono requires at least one ConfigOptionUi."
    }

    val cs = MaterialTheme.colorScheme
    val corner = 20.dp

    val initialId = defaultOptionId ?: options.first().id
    var selectedId by remember(options, defaultOptionId) {
        mutableStateOf(initialId)
    }

    ElevatedCard(
        shape = RoundedCornerShape(corner),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .drawBehind {
                val dark = Color(0xFF1A1A1A).copy(alpha = 0.18f)
                val mid = Color(0xFF7A7A7A).copy(alpha = 0.14f)
                val light = Color(0xFFE5E5E5).copy(alpha = 0.10f)
                val sweep = Brush.sweepGradient(
                    0f to dark,
                    0.25f to mid,
                    0.5f to light,
                    0.75f to mid,
                    1f to dark
                )
                drawRoundRect(
                    brush = sweep,
                    style = Stroke(width = 1f),
                    cornerRadius = CornerRadius(corner.toPx(), corner.toPx())
                )
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GradientHeadlineMono(title)

            Spacer(Modifier.height(6.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = lerp(cs.onSurface, Color(0xFF909090), 0.25f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Select survey configuration",
                style = MaterialTheme.typography.labelSmall,
                color = lerp(cs.onSurface, Color(0xFFB0B0B0), 0.3f),
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 6.dp)
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
                        onClick = { selectedId = option.id }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            HorizontalDivider(
                thickness = DividerDefaults.Thickness,
                color = cs.outlineVariant.copy(alpha = 0.25f)
            )

            Spacer(Modifier.height(14.dp))

            val selectedOption =
                options.firstOrNull { it.id == selectedId } ?: options.first()

            Button(
                onClick = { onStart(selectedOption) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = lerp(Color(0xFF1F1F1F), cs.surface, 0.25f),
                    contentColor = Color.White
                ),
                modifier = Modifier.testTag("StartButton")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Start",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun GradientHeadlineMono(text: String) {
    val brush = Brush.verticalGradient(
        0f to Color(0xFF909090),
        1f to Color(0xFF090909)
    )
    val label = buildAnnotatedString {
        withStyle(SpanStyle(brush = brush)) {
            append(text)
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center
    )
}

/**
 * Single monotone configuration chip.
 */
@Composable
private fun MonoConfigOptionChip(
    option: ConfigOptionUi,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val background = if (selected) {
        lerp(Color(0xFF1C1C1C), cs.surface, 0.10f)
    } else {
        lerp(Color(0xFF181818), cs.surface, 0.40f)
    }
    val borderColor = if (selected) {
        Color(0xFFB0B0B0).copy(alpha = 0.90f)
    } else {
        Color(0xFF6A6A6A).copy(alpha = 0.60f)
    }
    val labelColor = if (selected) {
        Color.White
    } else {
        lerp(cs.onSurface, Color(0xFFE0E0E0), 0.40f)
    }
    val descriptionColor = lerp(cs.onSurface, Color(0xFF9B9B9B), 0.30f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .testTag("ConfigOption_${option.id}")
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = descriptionColor
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        val strokeWidth = 1.5.dp.toPx()
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            style = Stroke(width = strokeWidth)
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = size.minDimension / 2.5f
                        )
                    }
            )
        }
    }
}

/* ────────────────────────── Background (monotone) ───────────────────────── */

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

    val c0 = Color(0xFF0B0B0B)
    val c1 = Color(0xFF141414)
    val c2 = Color(0xFF1E1E1E)
    val c3 = Color(0xFF272727)

    val endX = 900f + 280f * p
    val endY = 720f - 220f * p

    return Brush.linearGradient(
        colors = listOf(c0, c1, c2, c3),
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}
