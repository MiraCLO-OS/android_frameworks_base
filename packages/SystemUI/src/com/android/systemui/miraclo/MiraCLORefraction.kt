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

    /** Values chosen on the mock: blur behind the glass, bent edge width, bend and colour split. */
    val BLUR = 10.dp
    val BEVEL = 16.dp
    const val STRENGTH = 1.0f
    const val DISPERSION = 0.08f
}

/** Whether QS glass surfaces use refraction. Provided by QSFragmentCompose. */
val LocalMiraCLORefraction = compositionLocalOf { false }

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
    return this.drawBehind {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@drawBehind
        val radius =
            when (val outline = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                is Outline.Generic -> minOf(size.width, size.height) / 2f
                else -> 0f
            }
        val a = (alpha().coerceIn(0f, 1f) * 255f).toInt()
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
