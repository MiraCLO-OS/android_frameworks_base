/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.os.UserHandle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.settingslib.media.MediaOutputConstants
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.res.R
import kotlin.math.max
import kotlin.math.min

/**
 * The Dynamic Island window content: a compact pill around the camera, and an expanded media card
 * with frosted-glass blur, gloss, an ambient glow taken from the album art and a glow that follows
 * the finger. Built from plain views so it has no dependency on the shade or QS.
 */
@SuppressLint("ViewConstructor")
class MiraCLOIslandView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun openApp(intent: PendingIntent)

        fun mainHandler(): Handler
    }

    private val margin = dp(16)
    private val pillW = dp(186)
    private val pillH = dp(34)
    private val cardRadius = dp(34).toFloat()
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var data: MediaData? = null
    private var recents: List<MediaData> = emptyList()
    private var expanded = false
    private var accent = DEFAULT_ACCENT
    private var artColor = DEFAULT_ACCENT
    private var mediaController: MediaController? = null

    private val aura = AuraView(context)
    private val pill = PillView(context)
    private val card = CardView(context)

    // Card contents
    private val art = roundedImage(dp(46), dp(10).toFloat())
    private val title = text(16f, Color.WHITE, bold = true)
    private val subtitle = text(14f, 0xA8FFFFFF.toInt(), bold = false)
    private val appBadge = ImageView(context)
    private val progress = ProgressLine(context)
    private val elapsed = text(12f, 0x9EFFFFFF.toInt(), bold = false)
    private val total = text(12f, 0x9EFFFFFF.toInt(), bold = false)
    private val outputButton = controlButton()
    private val prevButton = controlButton()
    private val playButton = controlButton()
    private val nextButton = controlButton()
    private val customButton = controlButton()
    private val recentRow = LinearLayout(context)

    private val collapseRunnable = Runnable { setExpanded(false) }
    private val progressRunnable =
        object : Runnable {
            override fun run() {
                updateProgress()
                if (expanded) host.mainHandler().postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }

    init {
        clipChildren = false
        clipToPadding = false

        addView(aura, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            pill,
            LayoutParams(pillW, pillH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(margin, margin, margin, margin)
            },
        )
        addView(
            card,
            LayoutParams(cardWidth(), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { setMargins(margin, margin, margin, margin) },
        )
        buildCard()

        pill.setOnClickListener { setExpanded(true) }
        pill.setOnLongClickListener {
            data?.clickIntent?.let { host.openApp(it) }
            true
        }
        pill.contentDescription = "Now playing. Tap to expand"

        aura.visibility = GONE
        card.visibility = GONE
    }

    /** Top offset of the window so the pill sits centred on the front camera. */
    fun windowTopOffset(): Int {
        val cutout = context.display?.cutout?.boundingRectTop
        val centerY =
            if (cutout != null && !cutout.isEmpty) cutout.centerY() else pillH / 2 + dp(6)
        return max(0, centerY - pillH / 2 - margin)
    }

    fun onHostConfigurationChanged() {
        (card.layoutParams as LayoutParams).width = cardWidth()
        card.requestLayout()
    }

    fun bind(data: MediaData, recents: List<MediaData>) {
        val changedSession = this.data?.instanceId != data.instanceId
        this.data = data
        this.recents = recents

        val artBitmap = data.artwork?.loadDrawable(context)?.let { toBitmap(it) }
        if (artBitmap != null) {
            artColor = averageColor(artBitmap)
            accent = vivid(artColor)
        } else {
            artColor = DEFAULT_ACCENT
            accent = DEFAULT_ACCENT
        }

        pill.art = artBitmap
        pill.accent = accent
        pill.playing = data.isPlaying == true
        pill.invalidate()

        art.setImageBitmap(artBitmap)
        title.text = data.song ?: ""
        subtitle.text = data.artist ?: data.app ?: ""
        appBadge.setImageIcon(data.appIcon)
        appBadge.imageTintList = android.content.res.ColorStateList.valueOf(0xFF111111.toInt())

        bindAction(prevButton, data.semanticActions?.prevOrCustom, "Previous")
        bindAction(nextButton, data.semanticActions?.nextOrCustom, "Next")
        bindAction(playButton, data.semanticActions?.playOrPause, "Play or pause")
        playButton.imageTintList = android.content.res.ColorStateList.valueOf(0xFF111111.toInt())
        val custom = data.semanticActions?.custom0
        bindAction(customButton, custom, "App action")
        customButton.visibility = if (custom?.icon != null) VISIBLE else INVISIBLE

        // Output button shows the current device (phone speaker, earbuds, ...), like ColorOS.
        val deviceIcon = data.device?.icon
        if (deviceIcon != null) outputButton.setImageDrawable(deviceIcon)
        else outputButton.setImageResource(R.drawable.ic_speaker_rounded)

        bindRecents()
        aura.colors(artColor, accent)
        card.accent = accent
        if (changedSession) mediaController = null
        if (expanded) updateProgress()
    }

    fun release() {
        host.mainHandler().removeCallbacks(collapseRunnable)
        host.mainHandler().removeCallbacks(progressRunnable)
        pill.stop()
        card.stop()
    }

    // Expand / collapse ----------------------------------------------------------------------

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        val handler = host.mainHandler()
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(progressRunnable)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (value) {
            pill.visibility = GONE
            aura.visibility = VISIBLE
            card.visibility = VISIBLE
            card.pivotX = cardWidth() / 2f
            card.pivotY = 0f
            card.scaleX = 0.55f
            card.scaleY = 0.3f
            card.alpha = 0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
                .setInterpolator(emphasized).start()
            aura.alpha = 0f
            aura.animate().alpha(1f).setDuration(500).start()
            updateProgress()
            handler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS)
            handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
        } else {
            card.animate().scaleX(0.55f).scaleY(0.3f).alpha(0f).setDuration(220)
                .setInterpolator(emphasized)
                .withEndAction {
                    card.visibility = GONE
                    aura.visibility = GONE
                    pill.visibility = VISIBLE
                    pill.alpha = 0f
                    pill.animate().alpha(1f).setDuration(160).start()
                }
                .start()
            aura.animate().alpha(0f).setDuration(200).start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            setExpanded(false)
            return true
        }
        if (expanded) {
            // Any interaction keeps the card open a little longer.
            host.mainHandler().removeCallbacks(collapseRunnable)
            host.mainHandler().postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
        }
        return super.dispatchTouchEvent(ev)
    }

    // Card ---------------------------------------------------------------------------------

    private fun buildCard() {
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
        card.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Header: art, title / artist, app badge. Tapping it opens the app.
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    data?.clickIntent?.let { host.openApp(it) }
                    setExpanded(false)
                }
            }
        header.addView(art, LinearLayout.LayoutParams(dp(46), dp(46)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title)
        texts.addView(subtitle)
        header.addView(
            texts,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(8)
            },
        )
        appBadge.background = circle(Color.WHITE)
        appBadge.setPadding(dp(5), dp(5), dp(5), dp(5))
        header.addView(appBadge, LinearLayout.LayoutParams(dp(26), dp(26)))
        content.addView(header)

        // Progress
        content.addView(
            progress,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(16) },
        )
        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(elapsed, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        total.gravity = Gravity.END
        times.addView(total, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        content.addView(
            times,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )

        // Controls: output, previous, play/pause, next, app action
        val controls =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        outputButton.setImageResource(R.drawable.ic_speaker_rounded)
        outputButton.contentDescription = "Output device"
        outputButton.setOnClickListener { openOutputSwitcher() }
        playButton.background = circle(Color.WHITE)
        playButton.elevation = dp(6).toFloat()
        playButton.outlineAmbientShadowColor = Color.WHITE
        playButton.outlineSpotShadowColor = Color.WHITE
        val slots = listOf(outputButton, prevButton, playButton, nextButton, customButton)
        slots.forEachIndexed { i, button ->
            val size = if (button === playButton) dp(52) else dp(44)
            controls.addView(button, LinearLayout.LayoutParams(size, size))
            if (i < slots.size - 1) {
                controls.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
        }
        content.addView(
            controls,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )

        // Recently played (other media apps), shown only when there are some.
        recentRow.orientation = LinearLayout.HORIZONTAL
        content.addView(
            recentRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )
    }

    private fun bindRecents() {
        recentRow.removeAllViews()
        if (recents.isEmpty()) {
            recentRow.visibility = GONE
            return
        }
        recentRow.visibility = VISIBLE
        val size = (cardWidth() - dp(36) - dp(10) * 3) / 4
        for (i in 0 until 4) {
            val item = recents.getOrNull(i)
            val tile = roundedImage(size, dp(12).toFloat())
            if (item != null) {
                tile.setImageIcon(item.artwork)
                tile.contentDescription = "Play ${item.song ?: item.app ?: ""}"
                tile.setOnClickListener {
                    item.resumeAction?.run() ?: item.clickIntent?.let { host.openApp(it) }
                    setExpanded(false)
                }
            } else {
                tile.visibility = INVISIBLE
            }
            recentRow.addView(
                tile,
                LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = dp(10) },
            )
        }
    }

    private fun bindAction(button: ImageButton, action: MediaAction?, label: String) {
        button.setImageDrawable(action?.icon)
        button.contentDescription = action?.contentDescription ?: label
        button.isEnabled = action?.action != null
        button.alpha = if (action?.action != null) 1f else 0.4f
        button.setOnClickListener { action?.action?.run() }
    }

    private fun openOutputSwitcher() {
        val d = data ?: return
        val intent =
            Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG)
                .setPackage(context.packageName)
                .putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, d.packageName)
                .putExtra(MediaOutputConstants.EXTRA_USER_HANDLE, UserHandle.of(d.userId))
        d.token?.let { intent.putExtra(MediaOutputConstants.KEY_MEDIA_SESSION_TOKEN, it) }
        context.sendBroadcast(intent)
        setExpanded(false)
    }

    private fun updateProgress() {
        val d = data ?: return
        val controller =
            mediaController
                ?: d.token?.let { MediaController(context, it) }.also { mediaController = it }
        val state = controller?.playbackState
        val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (state == null || duration <= 0L) {
            progress.fraction = 0f
            elapsed.text = ""
            total.text = ""
            return
        }
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (delta * state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0L, duration)
        progress.fraction = position.toFloat() / duration
        elapsed.text = formatTime(position)
        total.text = formatTime(duration)
    }

    // Helpers --------------------------------------------------------------------------------

    private fun cardWidth(): Int = min(context.resources.displayMetrics.widthPixels - dp(36), dp(420))

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    private fun text(sizeSp: Float, color: Int, bold: Boolean) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) 600 else 400, false)
        }

    private fun roundedImage(size: Int, radius: Float) =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(0x33FFFFFF)
            }
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
            clipToOutline = true
            minimumWidth = size
            minimumHeight = size
        }

    private fun controlButton() =
        ImageButton(context).apply {
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }

    private fun circle(color: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun toBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }

    private fun averageColor(bitmap: Bitmap): Int =
        Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0) or 0xFF000000.toInt()

    /** A brighter, more saturated version of a colour, used for glows and the sound bars. */
    private fun vivid(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = max(hsv[1], 0.55f)
        hsv[2] = max(hsv[2], 0.92f)
        return Color.HSVToColor(hsv)
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    // Child views ----------------------------------------------------------------------------

    /** The compact pill: album art, the camera in the middle, and moving sound bars. */
    private inner class PillView(context: Context) : View(context) {
        var art: Bitmap? = null
        var accent = DEFAULT_ACCENT
        var playing = true
        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private var phase = 0f
        private val animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    if (playing) invalidate()
                }
            }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            isClickable = true
            isLongClickable = true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        fun stop() = animator.cancel()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            rect.set(0f, 0f, w, h)

            // Body: glossy black with a soft glow in the music's colour.
            glow.color = Color.BLACK
            glow.setShadowLayer(dp(9).toFloat(), 0f, 0f, (accent and 0x00FFFFFF) or 0x59000000)
            canvas.drawRoundRect(rect, r, r, glow)
            body.shader = LinearGradient(0f, 0f, 0f, h, 0xFF16171C.toInt(), Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, r, r, body)
            rim.strokeWidth = dp(1).toFloat() / 2f
            rim.shader = LinearGradient(0f, 0f, 0f, h, 0x40FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP)
            rect.inset(rim.strokeWidth, rim.strokeWidth)
            canvas.drawRoundRect(rect, r, r, rim)

            // Album art on the left.
            val a = art
            val artSize = h - dp(10)
            val artLeft = dp(6).toFloat()
            val artTop = (h - artSize) / 2f
            rect.set(artLeft, artTop, artLeft + artSize, artTop + artSize)
            if (a != null) {
                canvas.save()
                val clip = android.graphics.Path().apply {
                    addRoundRect(rect, dp(7).toFloat(), dp(7).toFloat(), android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(clip)
                canvas.drawBitmap(a, null, rect, artPaint)
                canvas.restore()
            }

            // Sound bars on the right.
            bar.color = accent
            val barW = dp(3).toFloat()
            val gap = dp(2).toFloat() + dp(1).toFloat() / 2f
            val maxH = dp(16).toFloat()
            var x = w - dp(14) - (barW * 4 + gap * 3)
            for (i in 0 until 4) {
                val t = (phase + i * 0.2f) % 1f
                val scale = if (playing) 0.35f + 0.65f * kotlin.math.abs(1f - 2f * t) else 0.3f
                val bh = maxH * scale
                rect.set(x, h / 2f - bh / 2f, x + barW, h / 2f + bh / 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, bar)
                x += barW + gap
            }
        }
    }

    /** Ambient glow behind the expanded card, coloured from the album art. */
    private inner class AuraView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var c1 = DEFAULT_ACCENT
        private var c2 = DEFAULT_ACCENT

        init {
            setRenderEffect(RenderEffect.createBlurEffect(dp(30).toFloat(), dp(30).toFloat(), Shader.TileMode.DECAL))
        }

        fun colors(art: Int, accent: Int) {
            c1 = art
            c2 = accent
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.shader = RadialGradient(w * 0.28f, h * 0.35f, w * 0.45f,
                (c1 and 0x00FFFFFF) or 0x99000000.toInt(), 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(w * 0.28f, h * 0.35f, w * 0.45f, paint)
            paint.shader = RadialGradient(w * 0.72f, h * 0.7f, w * 0.45f,
                (c2 and 0x00FFFFFF) or 0x80000000.toInt(), 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(w * 0.72f, h * 0.7f, w * 0.45f, paint)
        }
    }

    /** The expanded card: frosted glass, gloss, rim and a glow that follows the finger. */
    private inner class CardView(context: Context) : FrameLayout(context) {
        var accent = DEFAULT_ACCENT
        private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val touchGlow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var touchX = 0f
        private var touchY = 0f
        private var glowAlpha = 0f
        private var glowAnimator: ValueAnimator? = null

        init {
            setWillNotDraw(false)
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cardRadius)
                    }
                }
            clipToOutline = true
            elevation = dp(18).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = cardRadius
                setColor(0xE6141519.toInt())
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // Real frosted glass: blur what is behind the card (needs cross-window blur support).
            val blur = viewRootImpl?.createBackgroundBlurDrawable()
            if (blur != null) {
                blur.setBlurRadius(dp(36))
                blur.setCornerRadius(cardRadius)
                blur.setColor(0x8C141519.toInt())
                background = blur
            }
        }

        fun stop() {
            glowAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // Gloss along the top
            gloss.shader = LinearGradient(0f, 0f, 0f, h * 0.45f, 0x29FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, cardRadius, cardRadius, gloss)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // Glow under the finger
            if (glowAlpha > 0.01f) {
                val radius = w * 0.45f
                val a = (glowAlpha * 255).toInt().coerceIn(0, 255)
                touchGlow.shader = RadialGradient(
                    touchX, touchY, radius,
                    intArrayOf(Color.argb((a * 0.32f).toInt(), 255, 255, 255),
                        Color.argb((a * 0.45f).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.TRANSPARENT),
                    floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(touchX, touchY, radius, touchGlow)
            }
            // Glass rim
            rim.strokeWidth = dp(1).toFloat()
            rim.shader = LinearGradient(0f, 0f, 0f, h, 0x5CFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
            rect.set(rim.strokeWidth / 2f, rim.strokeWidth / 2f, w - rim.strokeWidth / 2f, h - rim.strokeWidth / 2f)
            canvas.drawRoundRect(rect, cardRadius, cardRadius, rim)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = ev.x
                    touchY = ev.y
                    animateGlow(1f)
                }
                MotionEvent.ACTION_MOVE -> {
                    touchX = ev.x
                    touchY = ev.y
                    invalidate()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> animateGlow(0f)
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true

        private fun animateGlow(target: Float) {
            glowAnimator?.cancel()
            glowAnimator =
                ValueAnimator.ofFloat(glowAlpha, target).apply {
                    duration = if (target > glowAlpha) 140 else 420
                    addUpdateListener {
                        glowAlpha = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }
    }

    /** Thin progress line with a glowing played part. */
    private inner class ProgressLine(context: Context) : View(context) {
        var fraction = 0f
            set(value) {
                field = value
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
        private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(dp(5).toFloat(), 0f, 0f, 0xCCFFFFFF.toInt())
        }
        private val rect = RectF()

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val r = h / 2f
            rect.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(rect, r, r, track)
            rect.set(0f, 0f, width * fraction.coerceIn(0f, 1f), h)
            if (rect.width() > 0f) canvas.drawRoundRect(rect, r, r, played)
        }
    }

    companion object {
        private val DEFAULT_ACCENT = 0xFF34D399.toInt()
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val AUTO_COLLAPSE_MS = 8000L
    }
}
