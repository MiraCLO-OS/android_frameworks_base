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

package com.android.systemui.volume.dialog.miraclo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.os.SystemClock
import android.util.PathParser
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStateModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The MiraCLO volume panel, in the style of the One UI one: a slim pill at the screen edge
 * (ringer mode, the stream being changed, more) that opens into a card with every stream,
 * the sound mode, sound settings and the media output. Drawn in code, like the island.
 *
 * Coordinates are the window's, which fills the screen; only the panel itself is touchable.
 */
class MiraCLOVolumePanelView(context: Context) : View(context) {

    interface Callbacks {
        fun onSetVolume(stream: Int, level: Int)
        fun onSetRingerMode(mode: Int)
        fun onSettings()
        fun onOutput()
        fun onUserActivity()
    }

    /** Colours of one look (light, dark, Liquid light or Liquid dark). */
    private class Look(
        val panel: Int,
        val blurDp: Int,
        val track: Int,
        val chip: Int,
        val text: Int,
        val text2: Int,
        val liquid: Boolean,
        val hairline: Int,
    )

    var callbacks: Callbacks? = null
    var onLeft = false

    private val density = resources.displayMetrics.density
    // Everything is laid out in the mock's sizes and drawn a little smaller (SCALE).
    private fun dp(v: Float) = v * density * SCALE
    private fun dp(v: Int) = v * density * SCALE
    /** Text sizes: shrunk less than the shapes so labels stay easy to read. */
    private fun sp(v: Float) = v * density * TEXT_SCALE

    private var look = lookFor(dark = true, liquid = false)
    private var fillColor = 0xFF3E91FF.toInt()
    private var onFillColor = Color.WHITE

    private var state = VolumeDialogStateModel()
    private var streams: List<Int> = emptyList()
    private var mainStream = AudioManager.STREAM_MUSIC
    private var showSettings = true
    private var hasVibrator = true
    private var outputTitle = ""
    private var outputSubtitle = ""
    private var outputBluetooth = false

    // 0 = hidden, 1 = shown; 0 = pill, 1 = card.
    private var shown = 0f
    private var expand = 0f
    private var showAnimator: ValueAnimator? = null
    private var expandAnimator: ValueAnimator? = null

    private val shownFrac = HashMap<Int, Float>()
    private val pendingLevel = HashMap<Int, Int>()
    private val pendingUntil = HashMap<Int, Long>()

    private var blur: BackgroundBlurDrawable? = null
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shaderMatrix = Matrix()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val textMedium = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val panelRect = RectF()
    private val pillRect = RectF()
    private val cardRect = RectF()
    private val tmp = RectF()
    private val path = Path()
    private val iconMatrix = Matrix()
    private val iconPath = Path()
    private val icons = HashMap<String, Path>()
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        setWillNotDraw(false)
        contentDescription = context.getString(R.string.miraclo_volume_panel)
    }

    // region Public API

    fun setLook(dark: Boolean, liquid: Boolean, fill: Int, onFill: Int) {
        look = lookFor(dark, liquid)
        fillColor = fill
        onFillColor = onFill
        applyBlurStyle()
        invalidate()
    }

    fun setState(model: VolumeDialogStateModel) {
        state = model
        val now = SystemClock.uptimeMillis()
        for ((stream, level) in pendingLevel.toList()) {
            val s = model.streamModels[stream]
            if (s == null || s.level == level || now > (pendingUntil[stream] ?: 0L)) {
                pendingLevel.remove(stream)
                pendingUntil.remove(stream)
            }
        }
        val models = model.streamModels
        mainStream =
            if (dragStream >= 0) dragStream
            else if (models.containsKey(model.activeStream)) model.activeStream
            else if (models.containsKey(AudioManager.STREAM_MUSIC)) AudioManager.STREAM_MUSIC
            else models.keys.firstOrNull() ?: AudioManager.STREAM_MUSIC
        val list = ArrayList<Int>()
        for (s in BASE_STREAMS) if (models.containsKey(s)) list.add(s)
        val active = model.activeStream
        if (models.containsKey(active) && !list.contains(active)) {
            // A call, alarm or accessibility volume being changed takes the System slot.
            list.remove(AudioManager.STREAM_SYSTEM)
            list.add(0, active)
        }
        streams = list.take(4)
        invalidate()
    }

    fun setShowSettings(show: Boolean) {
        showSettings = show
        invalidate()
    }

    fun setHasVibrator(has: Boolean) {
        hasVibrator = has
    }

    fun setOutput(title: String, subtitle: String, bluetooth: Boolean) {
        outputTitle = title
        outputSubtitle = subtitle
        outputBluetooth = bluetooth
        invalidate()
    }

    fun isExpanded() = expand > 0.5f

    fun animateIn() {
        showAnimator?.cancel()
        showAnimator = ValueAnimator.ofFloat(shown, 1f).apply {
            duration = 320
            interpolator = ease
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun animateOut(end: Runnable) {
        showAnimator?.cancel()
        expandAnimator?.cancel()
        showAnimator = ValueAnimator.ofFloat(shown, 0f).apply {
            duration = 200
            interpolator = ease
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var done = false
                override fun onAnimationEnd(animation: Animator) {
                    if (done) return
                    done = true
                    end.run()
                }
            })
            start()
        }
    }

    /** The touchable area, in window coordinates. */
    fun panelBounds(out: Rect) {
        computePanelRect()
        panelRect.roundOut(out)
    }

    // endregion

    // region Window and layout

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blur = try {
            viewRootImpl?.createBackgroundBlurDrawable()
        } catch (_: Exception) {
            null
        }
        applyBlurStyle()
    }

    override fun onDetachedFromWindow() {
        showAnimator?.cancel()
        expandAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun applyBlurStyle() {
        blur?.setBlurRadius(dp(look.blurDp).roundToInt())
        blur?.setColor(look.panel)
        panelFill.color = look.panel or 0xF0000000.toInt()
        hairline.color = look.hairline
        hairline.strokeWidth = max(1f, dp(0.6f))
        rim.strokeWidth = dp(1.3f)
    }

    private fun computeGeometry() {
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = dp(PILL_W)
        val ph = dp(PILL_H)
        val px = if (onLeft) dp(EDGE) else w - dp(EDGE) - pw
        val py = (h * 0.29f).coerceIn(dp(48), max(dp(48), h - ph - dp(48)))
        pillRect.set(px, py, px + pw, py + ph)

        val cw = min(w - dp(32), dp(CARD_MAX_W))
        val ch = dp(CARD_H)
        val cx = (w - cw) / 2f
        val cy =
            if (h < ch + dp(72)) max(dp(8), (h - ch) / 2f)
            else (h * 0.225f).coerceIn(dp(48), h - ch - dp(24))
        cardRect.set(cx, cy, cx + cw, cy + ch)
    }

    private fun computePanelRect() {
        computeGeometry()
        val e = ease.getInterpolation(expand)
        panelRect.set(
            lerp(pillRect.left, cardRect.left, e),
            lerp(pillRect.top, cardRect.top, e),
            lerp(pillRect.right, cardRect.right, e),
            lerp(pillRect.bottom, cardRect.bottom, e),
        )
        val slide = (1f - shown) * dp(24) * (if (onLeft) -1f else 1f)
        panelRect.offset(slide, 0f)
    }

    // endregion

    // region Drawing

    override fun onDraw(canvas: Canvas) {
        if (shown <= 0.001f) return
        computePanelRect()
        val r = lerp(dp(PILL_W / 2), dp(CARD_R), ease.getInterpolation(expand))
        val alpha = (255 * shown).roundToInt().coerceIn(0, 255)
        val L = look

        val b = blur
        if (b != null) {
            b.setCornerRadius(r)
            b.setBounds(
                panelRect.left.roundToInt(), panelRect.top.roundToInt(),
                panelRect.right.roundToInt(), panelRect.bottom.roundToInt())
            b.alpha = alpha
            b.draw(canvas)
        } else {
            panelFill.alpha = alpha
            canvas.drawRoundRect(panelRect, r, r, panelFill)
        }
        if (L.liquid) {
            fitShaders()
            gloss.alpha = alpha
            canvas.drawRoundRect(panelRect, r, r, gloss)
            val inset = rim.strokeWidth / 2f
            tmp.set(panelRect)
            tmp.inset(inset, inset)
            rim.alpha = alpha
            canvas.drawRoundRect(tmp, r - inset, r - inset, rim)
        } else if (Color.alpha(L.hairline) > 0) {
            hairline.alpha = (Color.alpha(L.hairline) * shown).roundToInt()
            canvas.drawRoundRect(panelRect, r, r, hairline)
        }

        canvas.save()
        path.reset()
        path.addRoundRect(panelRect, r, r, Path.Direction.CW)
        canvas.clipPath(path)
        val pillA = (1f - smooth(expand, 0f, 0.4f)) * shown
        if (pillA > 0.01f) {
            val ox = if (onLeft) panelRect.left else panelRect.right - dp(PILL_W)
            drawPill(canvas, ox, panelRect.top, pillA)
        }
        val cardA = smooth(expand, 0.45f, 1f) * shown
        if (cardA > 0.01f) {
            drawCard(canvas, panelRect.left, panelRect.top, cardRect.width(), cardA)
        }
        canvas.restore()

        if (stepFractions()) postInvalidateOnAnimation()
    }

    private fun drawPill(canvas: Canvas, ox: Float, oy: Float, a: Float) {
        val L = look
        circle(canvas, ox + dp(7f + 22f), oy + dp(7f + 22f), dp(22), withAlpha(L.chip, a))
        icon(canvas, ringerIcon(), ox + dp(7f + 22f), oy + dp(29), dp(21), withAlpha(L.text, a))
        slider(canvas, mainStream, ox + dp(7), oy + dp(60), dp(44), dp(230), a, showLevel = true)
        icon(canvas, "more", ox + dp(29), oy + dp(296f + 16f), dp(22), withAlpha(L.text2, a))
    }

    private fun drawCard(canvas: Canvas, ox: Float, oy: Float, cw: Float, a: Float) {
        val L = look
        // Sound mode: Sound / Vibrate / Mute.
        tmp.set(ox + dp(18), oy + dp(18), ox + dp(18 + 156), oy + dp(18 + 44))
        fill.color = withAlpha(L.chip, a)
        canvas.drawRoundRect(tmp, dp(22), dp(22), fill)
        val modes = ringerModes()
        val segW = dp(156 - 8).toFloat() / modes.size
        for ((i, mode) in modes.withIndex()) {
            val on = state.ringerModeInternal == mode
            tmp.set(ox + dp(22) + i * segW, oy + dp(22), ox + dp(22) + (i + 1) * segW, oy + dp(58))
            if (on) {
                fill.color = withAlpha(fillColor, a)
                canvas.drawRoundRect(tmp, dp(18), dp(18), fill)
            }
            icon(canvas, ringerIconFor(mode), tmp.centerX(), tmp.centerY(), dp(20),
                withAlpha(if (on) onFillColor else L.text2, a))
        }
        if (showSettings) {
            circle(canvas, ox + cw - dp(62f - 22f), oy + dp(40), dp(22), withAlpha(L.chip, a))
            icon(canvas, "gear", ox + cw - dp(40), oy + dp(40), dp(20), withAlpha(L.text, a))
        }

        // Every stream.
        val n = streams.size
        if (n > 0) {
            val sw = dp(52)
            val gap = dp(30)
            val x0 = ox + (cw - (n * sw + (n - 1) * gap)) / 2f
            text.textSize = sp(12.5f)
            text.textAlign = Paint.Align.CENTER
            text.color = withAlpha(L.text, a)
            for ((i, stream) in streams.withIndex()) {
                val sx = x0 + i * (sw + gap)
                slider(canvas, stream, sx, oy + dp(86), sw, dp(262), a, showLevel = false)
                canvas.drawText(label(stream), sx + sw / 2f, oy + dp(358f + 13f), text)
            }
        }

        // Media output.
        tmp.set(ox + dp(18), oy + dp(400), ox + cw - dp(18), oy + dp(458))
        fill.color = withAlpha(L.chip, a)
        canvas.drawRoundRect(tmp, dp(29), dp(29), fill)
        icon(canvas, if (outputBluetooth) "head" else "spk", ox + dp(45), oy + dp(429), dp(22),
            withAlpha(L.text, a))
        textMedium.textSize = sp(15f)
        textMedium.textAlign = Paint.Align.LEFT
        textMedium.color = withAlpha(L.text, a)
        val maxText = cw - dp(68 + 60)
        canvas.drawText(ellipsize(outputTitle, textMedium, maxText), ox + dp(68), oy + dp(425),
            textMedium)
        text.textSize = sp(12f)
        text.textAlign = Paint.Align.LEFT
        text.color = withAlpha(L.text2, a)
        canvas.drawText(ellipsize(outputSubtitle, text, maxText), ox + dp(68), oy + dp(444), text)
        icon(canvas, "chev", ox + cw - dp(42), oy + dp(429), dp(20), withAlpha(L.text2, a))
    }

    private fun slider(
        canvas: Canvas, stream: Int, x: Float, y: Float, w: Float, h: Float, a: Float,
        showLevel: Boolean,
    ) {
        val L = look
        val r = w / 2f
        tmp.set(x, y, x + w, y + h)
        fill.color = withAlpha(L.track, a)
        canvas.drawRoundRect(tmp, r, r, fill)
        val frac = shownFrac[stream] ?: targetFrac(stream).also { shownFrac[stream] = it }
        val fh = h * frac
        if (fh > 0.5f) {
            canvas.save()
            path.reset()
            path.addRoundRect(tmp, r, r, Path.Direction.CW)
            canvas.clipPath(path)
            tmp.set(x, y + h - fh, x + w, y + h)
            fill.color = withAlpha(fillColor, a)
            val fr = min(r, fh / 2f)
            canvas.drawRoundRect(tmp, fr, fr, fill)
            canvas.restore()
        }
        val iconSize = dp(22)
        val iconCy = y + h - dp(12) - iconSize / 2f
        val onFill = fh >= dp(12) + iconSize * 0.75f
        icon(canvas, streamIcon(stream), x + w / 2f, iconCy, iconSize,
            withAlpha(if (onFill) onFillColor else L.text, a))
        if (showLevel) {
            textMedium.textSize = sp(13f)
            textMedium.textAlign = Paint.Align.CENTER
            val levelOnFill = fh >= h - dp(30)
            textMedium.color = withAlpha(if (levelOnFill) onFillColor else L.text2, a)
            canvas.drawText(displayLevel(stream).toString(), x + w / 2f, y + dp(12f + 13f),
                textMedium)
        }
    }

    private fun circle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(cx, cy, r, fill)
    }

    private fun icon(canvas: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        if (name == "more") {
            iconFill.color = color
            for (dx in intArrayOf(-6, 0, 6)) canvas.drawCircle(cx + dx * s, cy, 1.6f * s, iconFill)
            return
        }
        val src = icons.getOrPut(name) {
            try {
                PathParser.createPathFromPathData(ICONS[name] ?: ICONS["note"]!!)
            } catch (_: Exception) {
                Path()
            }
        }
        iconMatrix.setScale(s, s)
        iconMatrix.postTranslate(cx - size / 2f, cy - size / 2f)
        iconPath.reset()
        src.transform(iconMatrix, iconPath)
        if (name in FILLED) {
            iconFill.color = color
            canvas.drawPath(iconPath, iconFill)
        } else {
            iconStroke.color = color
            iconStroke.strokeWidth = 2f * s
            canvas.drawPath(iconPath, iconStroke)
        }
    }

    private fun fitShaders() {
        if (gloss.shader == null) {
            // Made for a unit box and scaled onto the panel, as the island does.
            gloss.shader = LinearGradient(0f, 0f, 0.42f, 1f,
                intArrayOf(0x38FFFFFF, 0x0AFFFFFF, 0x05FFFFFF, 0x1AFFFFFF),
                floatArrayOf(0f, 0.42f, 0.6f, 1f), Shader.TileMode.CLAMP)
            rim.shader = LinearGradient(0f, 0f, 1f, 1f,
                intArrayOf(0xF2FFFFFF.toInt(), 0x40FFFFFF, 0x0AFFFFFF, 0x59BED7FF, 0xD9FFFFFF.toInt()),
                floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
        }
        shaderMatrix.setScale(panelRect.width(), panelRect.height())
        shaderMatrix.postTranslate(panelRect.left, panelRect.top)
        gloss.shader.setLocalMatrix(shaderMatrix)
        rim.shader.setLocalMatrix(shaderMatrix)
    }

    /** Moves each slider's drawn level a step towards its real level; true while moving. */
    private fun stepFractions(): Boolean {
        var moving = false
        for (stream in streams + mainStream) {
            val target = targetFrac(stream)
            val cur = shownFrac[stream] ?: target
            val next = if (stream == dragStream) target else cur + (target - cur) * 0.35f
            if (abs(next - target) < 0.002f) {
                shownFrac[stream] = target
            } else {
                shownFrac[stream] = next
                moving = true
            }
        }
        return moving
    }

    // endregion

    // region Touch

    private var dragStream = -1
    private var dragTrackTop = 0f
    private var dragTrackH = 0f
    private var dragDownY = 0f
    private var dragStartFrac = 0f
    private var dragMoved = false
    private var downTarget: String? = null
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (shown < 0.5f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                callbacks?.onUserActivity()
                downX = event.x
                downY = event.y
                downTarget = null
                dragStream = -1
                computePanelRect()
                if (!panelRect.contains(event.x, event.y)) return false
                if (expandAnimator?.isRunning == true) return true
                val slider = sliderAt(event.x, event.y)
                if (slider != null) {
                    dragStream = slider.first
                    dragTrackTop = slider.second.top
                    dragTrackH = slider.second.height()
                    dragDownY = event.y
                    dragStartFrac = targetFrac(dragStream)
                    dragMoved = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    downTarget = buttonAt(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                callbacks?.onUserActivity()
                if (dragStream >= 0) {
                    if (!dragMoved && abs(event.y - dragDownY) > touchSlop) dragMoved = true
                    if (dragMoved) {
                        val frac = (dragStartFrac + (dragDownY - event.y) / dragTrackH)
                            .coerceIn(0f, 1f)
                        setFromFrac(dragStream, frac)
                    }
                } else if (downTarget != null &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    downTarget = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                callbacks?.onUserActivity()
                if (dragStream >= 0) {
                    if (!dragMoved) {
                        val frac = ((dragTrackTop + dragTrackH - event.y) / dragTrackH)
                            .coerceIn(0f, 1f)
                        setFromFrac(dragStream, frac)
                    }
                    dragStream = -1
                } else {
                    downTarget?.let { click(it) }
                }
                downTarget = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragStream = -1
                downTarget = null
                return true
            }
        }
        return false
    }

    private fun setFromFrac(stream: Int, frac: Float) {
        val s = state.streamModels[stream] ?: return
        val range = s.levelMax - s.levelMin
        if (range <= 0) return
        val level = (s.levelMin + frac * range).roundToInt().coerceIn(s.levelMin, s.levelMax)
        if (level == displayLevel(stream) && pendingLevel.containsKey(stream)) return
        val old = displayLevel(stream)
        pendingLevel[stream] = level
        pendingUntil[stream] = SystemClock.uptimeMillis() + 800
        if (level != old) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            callbacks?.onSetVolume(stream, level)
        }
        invalidate()
    }

    /** The slider under (x, y): its stream and its track, in window coordinates. */
    private fun sliderAt(x: Float, y: Float): Pair<Int, RectF>? {
        if (isExpanded()) {
            val n = streams.size
            if (n == 0) return null
            val cw = cardRect.width()
            val sw = dp(52)
            val gap = dp(30)
            val x0 = panelRect.left + (cw - (n * sw + (n - 1) * gap)) / 2f
            for ((i, stream) in streams.withIndex()) {
                val r = RectF(x0 + i * (sw + gap), panelRect.top + dp(86),
                    x0 + i * (sw + gap) + sw, panelRect.top + dp(86 + 262))
                tmp.set(r)
                tmp.inset(-gap / 2f, -dp(8))
                if (tmp.contains(x, y)) return stream to r
            }
            return null
        }
        val ox = if (onLeft) panelRect.left else panelRect.right - dp(PILL_W)
        val r = RectF(ox + dp(7), panelRect.top + dp(60), ox + dp(51), panelRect.top + dp(290))
        tmp.set(r)
        tmp.inset(-dp(7), 0f)
        return if (tmp.contains(x, y)) mainStream to r else null
    }

    private fun buttonAt(x: Float, y: Float): String? {
        val ox = panelRect.left
        val oy = panelRect.top
        if (isExpanded()) {
            val cw = cardRect.width()
            if (y in oy + dp(14)..oy + dp(66)) {
                if (x in ox + dp(18)..ox + dp(18 + 156)) {
                    val modes = ringerModes()
                    val i = ((x - ox - dp(22)) / (dp(148) / modes.size)).toInt()
                        .coerceIn(0, modes.size - 1)
                    return "ringer:${modes[i]}"
                }
                if (showSettings && x in ox + cw - dp(66)..ox + cw - dp(14)) return "settings"
            }
            if (y in oy + dp(400)..oy + dp(458) && x in ox + dp(18)..ox + cw - dp(18)) {
                return "output"
            }
            return null
        }
        val px = if (onLeft) panelRect.left else panelRect.right - dp(PILL_W)
        if (y in oy..oy + dp(56) && x in px..px + dp(PILL_W)) return "ringer:cycle"
        if (y in oy + dp(290)..oy + dp(PILL_H) && x in px..px + dp(PILL_W)) return "more"
        return null
    }

    private fun click(target: String) {
        when {
            target == "more" -> expand()
            target == "settings" -> callbacks?.onSettings()
            target == "output" -> callbacks?.onOutput()
            target == "ringer:cycle" -> {
                val modes = ringerModes()
                val i = modes.indexOf(state.ringerModeInternal)
                callbacks?.onSetRingerMode(modes[(i + 1).mod(modes.size)])
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            target.startsWith("ringer:") -> {
                val mode = target.substringAfter(':').toIntOrNull() ?: return
                if (mode != state.ringerModeInternal) {
                    callbacks?.onSetRingerMode(mode)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
            }
        }
    }

    private fun expand() {
        if (expand >= 1f) return
        expandAnimator?.cancel()
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        expandAnimator = ValueAnimator.ofFloat(expand, 1f).apply {
            duration = 380
            interpolator = null
            addUpdateListener {
                expand = it.animatedValue as Float
                invalidate()
                // The touchable region follows the panel.
                requestLayout()
            }
            start()
        }
    }

    // endregion

    // region State helpers

    private fun displayLevel(stream: Int): Int {
        pendingLevel[stream]?.let { return it }
        val s = state.streamModels[stream] ?: return 0
        return if (s.muted) s.levelMin else s.level
    }

    private fun targetFrac(stream: Int): Float {
        val s: VolumeDialogStreamModel = state.streamModels[stream] ?: return 0f
        val range = s.levelMax - s.levelMin
        if (range <= 0) return 0f
        return ((displayLevel(stream) - s.levelMin).toFloat() / range).coerceIn(0f, 1f)
    }

    private fun ringerModes(): IntArray =
        if (hasVibrator) intArrayOf(
            AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_SILENT)
        else intArrayOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_SILENT)

    private fun ringerIcon() = ringerIconFor(state.ringerModeInternal)

    private fun ringerIconFor(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_VIBRATE -> "vib"
        AudioManager.RINGER_MODE_SILENT -> "mute"
        else -> "bell"
    }

    private fun streamIcon(stream: Int): String {
        val level = displayLevel(stream)
        val s = state.streamModels[stream]
        val silent = s != null && (s.muted || level <= s.levelMin)
        return when (stream) {
            AudioManager.STREAM_RING ->
                if (state.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE) "vib"
                else if (silent) "mute" else "bell"
            AudioManager.STREAM_NOTIFICATION -> if (silent) "mute" else "bellb"
            AudioManager.STREAM_SYSTEM -> "sys"
            AudioManager.STREAM_ALARM -> "alarm"
            AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_BLUETOOTH_SCO -> "call"
            AudioManager.STREAM_ACCESSIBILITY -> "a11y"
            else ->
                if (silent) "spkoff"
                else if (s?.routedToBluetooth == true) "head"
                else "note"
        }
    }

    private fun label(stream: Int): String = context.getString(
        when (stream) {
            AudioManager.STREAM_RING -> R.string.miraclo_volume_ringtone
            AudioManager.STREAM_NOTIFICATION -> R.string.miraclo_volume_notifications
            AudioManager.STREAM_SYSTEM -> R.string.miraclo_volume_system
            AudioManager.STREAM_ALARM -> R.string.miraclo_volume_alarm
            AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_BLUETOOTH_SCO ->
                R.string.miraclo_volume_call
            AudioManager.STREAM_ACCESSIBILITY -> R.string.miraclo_volume_accessibility
            else -> R.string.miraclo_volume_media
        })

    // endregion

    private fun ellipsize(s: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 0 && paint.measureText(s, 0, end) + paint.measureText("…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    private fun lookFor(dark: Boolean, liquid: Boolean): Look = when {
        liquid && dark -> Look(0x4D10131C, 14, 0x1FFFFFFF, 0x1AFFFFFF,
            0xFFF2F3F6.toInt(), 0xFFA1A5AE.toInt(), true, 0)
        liquid -> Look(0x4DFFFFFF, 14, 0x66FFFFFF, 0x59FFFFFF,
            0xFF16171A.toInt(), 0xFF5E616A.toInt(), true, 0)
        dark -> Look(0xF01A1A1E.toInt(), 30, 0x1AFFFFFF, 0x14FFFFFF,
            0xFFF2F3F6.toInt(), 0xFFA1A5AE.toInt(), false, 0x14FFFFFF)
        else -> Look(0xF0F7F7FA.toInt(), 30, 0x12000000, 0x0D000000,
            0xFF16171A.toInt(), 0xFF6B6E76.toInt(), false, 0x14000000)
    }

    private companion object {
        const val PILL_W = 58f
        const val PILL_H = 336f
        const val EDGE = 14f
        const val CARD_H = 478f
        const val CARD_R = 32f
        const val CARD_MAX_W = 390f
        const val SCALE = 0.86f
        const val TEXT_SCALE = 0.94f

        val BASE_STREAMS = intArrayOf(
            AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM)

        val FILLED = setOf("note")

        val ICONS = mapOf(
            "note" to "M9 17.5V6l10-2v11.5a2.6 2.6 0 1 1-1.8-2.5V7.6L10.8 8.9v8.6a2.6 2.6 0 1 1-1.8-2.5z",
            "bell" to "M6 16v-5a6 6 0 0 1 12 0v5l2 2H4zM10 21h4",
            "bellb" to "M6 16v-5a6 6 0 0 1 9-5.2M18 11v5l2 2H4l2-2M10 21h4M18 3.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z",
            "vib" to "M8.5 4h7v16h-7zM4.5 8.5v7M19.5 8.5v7",
            "mute" to "M6 16v-5a6 6 0 0 1 9-5.2M18 11v5l2 2H8M10 21h4M3 3l18 18",
            "sys" to "M7 3h10a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 17 21H7a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 7 3zM10.5 18h3",
            "gear" to "M12 9a3 3 0 1 1 0 6a3 3 0 1 1 0-6zM12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1",
            "spk" to "M4 9.5h3.5L12 5.5v13l-4.5-4H4zM15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11",
            "spkoff" to "M4 9.5h3.5L12 5.5v13l-4.5-4H4zM16 9.5l5 5M21 9.5l-5 5",
            "head" to "M4 15v-3a8 8 0 0 1 16 0v3M4 15h3v5H4zM17 15h3v5h-3z",
            "chev" to "M9 6l6 6-6 6",
            "alarm" to "M12 7v5l3 2M12 4a8 8 0 1 1 0 16a8 8 0 1 1 0-16zM5 3L2.5 5.5M19 3l2.5 2.5",
            "call" to "M6.5 3.5h3l1.5 4-2 1.5a11 11 0 0 0 6 6l1.5-2 4 1.5v3a2 2 0 0 1-2 2A16 16 0 0 1 4.5 5.5a2 2 0 0 1 2-2z",
            "a11y" to "M12 3.5a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6zM5 9l7 1.5L19 9M12 10.5V15l-3 5.5M12 15l3 5.5",
        )

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun smooth(v: Float, from: Float, to: Float): Float {
            val t = ((v - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        fun withAlpha(color: Int, a: Float): Int =
            (color and 0x00FFFFFF) or ((Color.alpha(color) * a).roundToInt().coerceIn(0, 255) shl 24)
    }
}
