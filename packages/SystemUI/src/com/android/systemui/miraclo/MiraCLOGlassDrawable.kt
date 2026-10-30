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

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * A Liquid Glass panel: a light see-through tint, the gloss and the bright rim used by the
 * Liquid Glass quick settings, island, notifications and volume panel. [radius] < 0 draws a
 * circle / full pill.
 */
class MiraCLOGlassDrawable @JvmOverloads constructor(
    private val tint: Int,
    private val radius: Float,
    private val rimWidth: Float,
    /** Panels ignore tints (the power menu tints its panel); buttons take them. */
    private val acceptTint: Boolean = true,
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimWidth
    }
    private val rect = RectF()
    private var alphaValue = 255

    private var solid = false

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (solid) return
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        gloss.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.left + w * 0.42f, bounds.top + h,
            intArrayOf(0x38FFFFFF, 0x0AFFFFFF, 0x05FFFFFF, 0x1AFFFFFF),
            floatArrayOf(0f, 0.42f, 0.6f, 1f), Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(),
            intArrayOf(0xF2FFFFFF.toInt(), 0x40FFFFFF, 0x0AFFFFFF, 0x59BED7FF, 0xD9FFFFFF.toInt()),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
    }

    private fun r(): Float =
        if (radius < 0f) minOf(bounds.width(), bounds.height()) / 2f else radius

    override fun draw(canvas: Canvas) {
        val r = r()
        rect.set(bounds)
        fill.alpha = android.graphics.Color.alpha(fill.color) * alphaValue / 255
        canvas.drawRoundRect(rect, r, r, fill)
        if (solid) return
        gloss.alpha = alphaValue
        canvas.drawRoundRect(rect, r, r, gloss)
        val i = rimWidth / 2f
        rect.inset(i, i)
        rim.alpha = alphaValue
        canvas.drawRoundRect(rect, r - i, r - i, rim)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, r())
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    /** A tint (for example the red emergency button) makes it a solid button again. */
    override fun setTintList(tint: android.content.res.ColorStateList?) {
        if (!acceptTint) return
        fill.color = tint?.defaultColor ?: this.tint
        solid = tint != null
        if (tint != null) {
            gloss.shader = null
            rim.shader = null
            rim.color = 0
            gloss.color = 0
        }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
