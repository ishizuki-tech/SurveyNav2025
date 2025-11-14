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
 *   • Explicit pure-black strips over status/navigation bar insets to avoid
 *     accidental light edges or surfaces.
 *
 *  Notes:
 *   • No Scaffold is used here to keep full control over system bar painting.
 *   • Test tags and semantics are provided for UI tests and accessibility.
 * =====================================================================
 */

package com.negi.survey.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Intro screen rendered in a strict grayscale palette.
 *
 * Responsibilities:
 *  - Paint an animated dark monotone background for the content region.
 *  - Force pure black under the system status/navigation bars using the
 *    corresponding insets.
 *  - Present a single centered intro card with title, subtitle, and a
 *    “Start” button.
 *
 * The root uses a plain [Box] instead of [androidx.compose.material3.Scaffold]
 * to avoid any default container colors bleeding into system bar regions.
 *
 * @param onStart Callback invoked when the primary action button is pressed.
 */
@Composable
fun IntroScreen(
    onStart: () -> Unit,
) {
    val bgBrush = animatedMonotoneBackground()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Main content background: animated dark monotone gradient.
            .background(bgBrush)
            // Accessibility & testing hooks.
            .semantics { contentDescription = "Survey intro screen" }
            .testTag("IntroScreenRoot")
    ) {
        // Top system bar strip: paint the status bar height in pure black.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxSize()
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Color.Black)
            )
        }

        // Center content layer: hero card in the middle of the viewport.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            IntroCardMono(
                title = "Survey Test App",
                subtitle = "A focused, privacy-friendly evaluation flow",
                onStart = onStart
            )
        }

        // Bottom system bar strip: paint the navigation bar height in pure black.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxSize()
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(Color.Black)
            )
        }
    }
}

/* ──────────────────────────── Card & Typography ─────────────────────────── */

/**
 * Centered glass-like card rendered in strict grayscale.
 *
 * Structure:
 *  - Gradient headline rendered with a vertical brush.
 *  - Supporting subtitle in a desaturated mid-gray.
 *  - Divider, then a single “Start” button with icon.
 *
 * The card is wrapped in an [ElevatedCard] to inherit Material3 elevation
 * behavior and then augmented with a thin neutral rim drawn via [drawBehind].
 */
@Composable
private fun IntroCardMono(
    title: String,
    subtitle: String,
    onStart: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val corner = 20.dp

    ElevatedCard(
        shape = RoundedCornerShape(corner),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .drawBehind {
                // Neutral rim (no chroma) for subtle edge definition around the card.
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

            HorizontalDivider(
                thickness = DividerDefaults.Thickness,
                color = cs.outlineVariant.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onStart,
                shape = CircleShape,
                // Neutral dark gray with white text to avoid any chroma.
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

/**
 * Monotone gradient headline rendered with a vertical brush.
 *
 * Implementation:
 *  - A [Brush.verticalGradient] is applied through [SpanStyle.brush] to the
 *    entire string, producing a dark-to-light vertical fade.
 *  - Shadows are intentionally avoided; visual hierarchy is provided through
 *    contrast and typography only.
 */
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

/* ────────────────────────── Background (monotone) ───────────────────────── */

/**
 * Animated monotone background brush.
 *
 * Implementation details:
 *  - Uses a single infinite transition to animate a scalar `p` in [0, 1].
 *  - `p` drives the end vector of a linear gradient, producing slow drift in
 *    space without changing hue (strict grayscale stops).
 *  - System bar strips in [IntroScreen] paint pure black over the insets, so
 *    even if the gradient reaches lighter grays near edges, bars remain black.
 */
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

    // Strict grayscale stops.
    val c0 = Color(0xFF0B0B0B)
    val c1 = Color(0xFF141414)
    val c2 = Color(0xFF1E1E1E)
    val c3 = Color(0xFF272727)

    // End vector drifts slowly to create subtle motion.
    val endX = 900f + 280f * p
    val endY = 720f - 220f * p

    return Brush.linearGradient(
        colors = listOf(c0, c1, c2, c3),
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}

/* ───────────────────────────────── Preview ──────────────────────────────── */

@Preview(showBackground = true, name = "Intro — Monotone Chic / Black Bars")
@Composable
private fun IntroScreenPreview() {
    MaterialTheme {
        IntroScreen(onStart = {})
    }
}
