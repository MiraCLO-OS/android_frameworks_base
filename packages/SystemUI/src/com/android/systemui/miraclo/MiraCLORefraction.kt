/*
 * Copyright (C) 2026 MiraCLO
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.miraclo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.android.internal.graphics.drawable.BackgroundBlurDrawable

/**
 * MiraCLO Liquid Glass refraction (Settings > Display > Liquid Glass > Refraction).
 *
 * The glass is drawn by SurfaceFlinger: each surface gets a blur region of the shade window
 * with refraction values, so what is under the shade (apps, home screen, wallpaper) is bent near
 * the edge of the surface, its colours split slightly and its rim lit. The tint and everything
 * on the surface stay drawn here, on top.
 */
object MiraCLORefraction {
    /** Settings.Secure key, 0 = off (default), 1 = on. Only used while Liquid Glass is on. */
    const val KEY = "miraclo_lg_refraction"

    /** Settings.Secure key for the touch glow (and the music card's corner glow), 0 = off. */
    const val KEY_GLOW = "miraclo_lg_glow"

    /** Values chosen on the mock: blur behind the glass, bent edge width, bend and colour split. */
    val BLUR = 10.dp
    val BEVEL = 16.dp
    const val STRENGTH = 1.0f
    const val DISPERSION = 0.08f
}

/** Whether QS glass surfaces use refraction. Provided by QSFragmentCompose. */
val LocalMiraCLORefraction = compositionLocalOf { false }

/** Whether the Liquid Glass glow is on. Provided by QSFragmentCompose. */
val LocalMiraCLOGlow = compositionLocalOf { false }

/**
 * How visible the QS glass is right now (0..1), read while drawing. SurfaceFlinger does not see
 * Compose alpha, so the blur region needs it to fade in and out with the panel.
 */
val LocalMiraCLOGlassAlpha = compositionLocalOf<() -> Float> { { 1f } }

/**
 * Draws a refracting glass region of [shape] behind this element, when [enabled]. Draw tint and
 * content after it.
 */
@Composable
fun Modifier.miracloRefraction(shape: Shape, enabled: Boolean): Modifier {
    if (!enabled) return this
    val view = LocalView.current
    val alpha = LocalMiraCLOGlassAlpha.current
    val drawable: BackgroundBlurDrawable? =
        remember(view) {
            try {
                view.viewRootImpl?.createBackgroundBlurDrawable()
            } catch (_: Exception) {
                null
            }
        }
    DisposableEffect(drawable) { onDispose { drawable?.setVisible(false, false) } }
    if (drawable == null) return this
    // SurfaceFlinger does not clip the glass to the pager: fade it out as the surface leaves the
    // visible area (next or previous Quick Settings page, screen edges).
    val visible = remember { mutableFloatStateOf(1f) }
    return this.onGloballyPositioned { c ->
        val full = c.size.width.toFloat() * c.size.height.toFloat()
        val b = c.boundsInWindow()
        val f = if (full > 0f) (b.width * b.height) / full else 0f
        visible.floatValue = ((f - 0.7f) / 0.3f).coerceIn(0f, 1f)
    }.drawBehind {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@drawBehind
        val radius =
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                is Outline.Generic -> minOf(size.width, size.height) / 2f
                else -> 0f
            }
        val a = (alpha().coerceIn(0f, 1f) * visible.floatValue * 255f).toInt()
        drawable.setVisible(a > 0, false)
        drawable.setBlurRadius(MiraCLORefraction.BLUR.toPx().toInt())
        drawable.setCornerRadius(radius)
        drawable.setLiquidGlass(
            MiraCLORefraction.STRENGTH, MiraCLORefraction.BEVEL.toPx(), MiraCLORefraction.DISPERSION)
        drawable.alpha = a
        drawable.setBounds(0, 0, w, h)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            // The blur region follows a RenderNode, which only hardware canvases can draw.
            if (native.isHardwareAccelerated) drawable.draw(native)
        }
    }
}

/**
 * A soft light that follows the finger anywhere on this element while it is touched, like the
 * Dynamic Island cards. Touches are only watched, never consumed.
 */
@Composable
fun Modifier.miracloTouchGlow(enabled: Boolean): Modifier {
    if (!enabled) return this
    var pos by remember { mutableStateOf(Offset.Unspecified) }
    var pressed by remember { mutableStateOf(false) }
    val a by
        animateFloatAsState(
            if (pressed) 1f else 0f,
            tween(durationMillis = if (pressed) 160 else 420),
            label = "MiraCLOTouchGlow",
        )
    return this.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = e.changes.firstOrNull() ?: continue
                    pos = ch.position
                    pressed = e.changes.any { it.pressed }
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (a > 0.01f && pos.isSpecified) {
                val r = 110.dp.toPx()
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.12f * a),
                        0.45f to Color(0xFF9FB3FF).copy(alpha = 0.06f * a),
                        1f to Color.Transparent,
                        center = pos,
                        radius = r,
                    ),
                    radius = r,
                    center = pos,
                )
            }
        }
}

/** A faint, constant light in the bottom corner of the music card. */
fun Modifier.miracloCornerGlow(enabled: Boolean): Modifier =
    if (!enabled) this
    else
        this.drawWithContent {
            drawContent()
            val c = Offset(size.width * 0.88f, size.height)
            val r = size.minDimension * 0.9f
            drawRect(
                Brush.radialGradient(
                    0f to Color(0xFFBFD0FF).copy(alpha = 0.16f),
                    0.5f to Color.White.copy(alpha = 0.05f),
                    1f to Color.Transparent,
                    center = c,
                    radius = r,
                )
            )
        }

