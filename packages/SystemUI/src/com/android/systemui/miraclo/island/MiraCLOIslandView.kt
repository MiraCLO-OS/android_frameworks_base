/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.internal.policy.SystemBarUtils
import com.android.settingslib.media.MediaOutputConstants
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.res.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * The Dynamic Island window content.
 *
 * - The pill sits on the status bar line around the front camera. Tap: open the card. Pull down:
 *   open the app. Swipe sideways (past a third of the screen, or a flick): hide. Small drags
 *   spring back. Long press does nothing but lift it.
 * - The card opens below the camera, morphing out of the pill (and back into it): one shape that
 *   grows, moves down and turns from black into frosted glass. Tap: open the app. Swipe up: back
 *   into the pill (follows the finger). Swipe left / right / down: stretches a little and springs
 *   back. Long press: only the touch glow.
 *
 * All motion is short and subtle. The morph only moves an outline clip and a translation (no
 * re-layout), and the blurred glow is drawn once and then just moved.
 */
@SuppressLint("ViewConstructor")
class MiraCLOIslandView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        /** Open the app (the island vanishes while the app is on screen). */
        fun openApp(intent: PendingIntent)

        fun mainHandler(): Handler

        /** The pill was swiped away; hide it until the next song or until the app is opened. */
        fun dismiss()
    }

    // Sizes ---------------------------------------------------------------------------------------

    private val pillW = dp(186)
    private val pillH = dp(34)
    private val cardRadius = dp(34).toFloat()
    /** Room under the card for the pull-down stretch and the ambient glow. */
    private val bottomRoom = dp(40)
    /** Room under the pill for dragging it around (only the pill itself takes touches). */
    private val pillDragRoom = dp(120)

    // Motion ----------------------------------------------------------------------------------------

    /** Expand: quick start, long soft landing. */
    private val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Collapse / vanish: standard, slightly quicker. */
    private val standard = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val flingVelocity = dp(1000).toFloat()
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    /** A gentle spring: settles with one small overshoot (about 2 percent), never a wobble. */
    private val softSpring =
        Interpolator { t ->
            if (t >= 1f) 1f
            else 1f - exp(-t * 11f) * cos(t * 1.4f * 2f * PI).toFloat()
        }

    // State -----------------------------------------------------------------------------------------

    private var data: MediaData? = null
    private var recents: List<MediaData> = emptyList()
    private var artKey: String? = null
    private var recentsKey: String? = null
    private var expanded = false
    /** Swiped away or opening the app: ignore touches until the window goes. */
    private var leaving = false
    private var vanishing = false
    private var accent = DEFAULT_ACCENT
    private var artColor = DEFAULT_ACCENT
    private var mediaController: MediaController? = null
    private var durationMs = 0L
    private var morphAnimator: ValueAnimator? = null

    // Views -----------------------------------------------------------------------------------------

    private val aura = AuraView(context)
    private val card = CardView(context)
    private val pill = PillView(context)

    private val content = LinearLayout(context)
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

    private val playIcon = PlayPauseDrawable()
    private val prevIcon = SkipDrawable(forward = false)
    private val nextIcon = SkipDrawable(forward = true)

    private val collapseRunnable = Runnable { setExpanded(false) }
    private val progressRunnable =
        object : Runnable {
            override fun run() {
                updateProgress()
                if (expanded) host.mainHandler().postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }

    /**
     * Only the pill (or the card) takes touches; the rest of the window lets them through to the
     * app below, and a touch there collapses the card (ACTION_OUTSIDE).
     */
    private val touchRect = Rect()
    private val insetsListener =
        ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            val target = if (expanded) card else pill
            if (leaving || target.visibility != VISIBLE) {
                info.touchableRegion.setEmpty()
                return@OnComputeInternalInsetsListener
            }
            target.getHitRect(touchRect)
            if (target === pill) touchRect.inset(-dp(6), -dp(6))
            info.touchableRegion.set(touchRect)
        }

    init {
        clipChildren = false
        clipToPadding = false

        // Order matters: the pill stays on top so it can fade over the card as the card grows.
        addView(aura, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            card,
            LayoutParams(cardWidth(), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { setMargins(0, cardTop(), 0, bottomRoom) },
        )
        addView(
            pill,
            LayoutParams(pillW, pillH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(0, pillTop(), 0, pillDragRoom)
            },
        )
        buildCard()

        pill.contentDescription = "Now playing. Tap to expand, swipe sideways to hide"
        aura.visibility = GONE
        card.visibility = GONE
    }

    // Geometry --------------------------------------------------------------------------------------

    /** Top of the pill: centred on the status bar icons (clock, battery), around the camera. */
    private fun pillTop(): Int {
        val barHeight = SystemBarUtils.getStatusBarHeight(context)
        val padTop = resources.getDimensionPixelSize(R.dimen.status_bar_padding_top)
        return max(0, (barHeight + padTop) / 2 - pillH / 2)
    }

    /** Top of the card: one pill-height below the camera hole, so the card never covers it. */
    private fun cardTop(): Int {
        val cutout = context.display?.cutout?.boundingRectTop
        val cameraBottom =
            if (cutout != null && !cutout.isEmpty) cutout.bottom else pillTop() + pillH
        return max(cameraBottom, pillTop() + pillH) + pillH
    }

    private fun cardWidth(): Int = min(context.resources.displayMetrics.widthPixels - dp(36), dp(420))

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
        super.onDetachedFromWindow()
    }

    fun onHostConfigurationChanged() {
        (card.layoutParams as LayoutParams).apply {
            width = cardWidth()
            topMargin = cardTop()
        }
        (pill.layoutParams as LayoutParams).topMargin = pillTop()
        requestLayout()
    }

    // Data ------------------------------------------------------------------------------------------

    fun bind(data: MediaData, recents: List<MediaData>) {
        val changedSession = this.data?.instanceId != data.instanceId
        this.data = data
        this.recents = recents

        // Decoding the art is the only costly part; do it only when the song changes.
        val key = "${data.packageName}|${data.song}|${data.artist}|${data.artwork != null}"
        if (key != artKey) {
            artKey = key
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
            art.setImageBitmap(artBitmap)
            aura.colors(artColor, accent)
            card.accent = accent
            appBadge.setImageIcon(data.appIcon)
        }

        pill.playing = data.isPlaying == true
        pill.invalidate()

        title.text = data.song ?: ""
        subtitle.text = data.artist ?: data.app ?: ""
        // Our own icons, so the row always looks the same and play / pause always shows the real
        // state (the apps' animated icons start on the opposite shape).
        val actions = data.semanticActions
        bindControl(prevButton, prevIcon, actions?.prevOrCustom, "Previous")
        bindControl(nextButton, nextIcon, actions?.nextOrCustom, "Next")
        val playPause = actions?.playOrPause
        playIcon.setPlaying(data.isPlaying == true, animate = playButton.isAttachedToWindow)
        playButton.contentDescription = playPause?.contentDescription ?: "Play or pause"
        playButton.isEnabled = playPause?.action != null
        playButton.setOnClickListener {
            val run = playPause?.action ?: return@setOnClickListener
            run.run()
            // Flip at once so it feels instant; the next update confirms it.
            playIcon.setPlaying(!playIcon.playing, animate = true)
        }

        val custom = actions?.custom0
        customButton.setImageDrawable(custom?.icon)
        customButton.contentDescription = custom?.contentDescription ?: "App action"
        customButton.setOnClickListener { custom?.action?.run() }
        customButton.visibility =
            if (custom?.icon != null && custom.action != null) VISIBLE else INVISIBLE

        bindRecents()
        if (changedSession) mediaController = null
        if (expanded) updateProgress()
    }

    fun release() {
        host.mainHandler().removeCallbacks(collapseRunnable)
        host.mainHandler().removeCallbacks(progressRunnable)
        morphAnimator?.cancel()
        pill.stop()
        card.stop()
    }

    /** Fade out quickly (the app is opening over it), then [end]. */
    fun vanish(end: () -> Unit) {
        if (vanishing) return
        vanishing = true
        leaving = true
        host.mainHandler().removeCallbacks(collapseRunnable)
        host.mainHandler().removeCallbacks(progressRunnable)
        morphAnimator?.cancel()
        // Let touches through right away.
        requestLayout()
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 140
            interpolator = standard
            addUpdateListener {
                val a = it.animatedValue as Float
                this@MiraCLOIslandView.alpha = a
                card.setBlurAlpha(a)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = end()
                }
            )
            start()
        }
    }

    // Expand / collapse -----------------------------------------------------------------------------

    private fun setExpanded(value: Boolean) {
        if (leaving || expanded == value) return
        expanded = value
        val handler = host.mainHandler()
        handler.removeCallbacks(collapseRunnable)
        handler.removeCallbacks(progressRunnable)
        if (value) {
            subtleTick()
            card.dragX = 0f
            card.dragY = 0f
            card.visibility = VISIBLE
            aura.visibility = VISIBLE
            // Start once the card has its size, from exactly the pill's shape.
            card.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        card.viewTreeObserver.removeOnPreDrawListener(this)
                        if (expanded) {
                            card.applyMorph(0f)
                            animateMorph(1f, EXPAND_MS, decelerate)
                        }
                        return true
                    }
                }
            )
            updateProgress()
            handler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS)
            handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
        } else {
            animateMorph(0f, COLLAPSE_MS, standard) {
                card.visibility = GONE
                aura.visibility = GONE
                card.dragX = 0f
                card.dragY = 0f
            }
        }
        // The touchable area changes (pill <-> card).
        requestLayout()
    }

    private fun animateMorph(
        target: Float,
        duration: Long,
        interpolator: Interpolator,
        end: (() -> Unit)? = null,
    ) {
        morphAnimator?.cancel()
        val from = card.morph
        // Shorter when part of the way is already done (e.g. after a drag).
        val scaled = (duration * max(0.4f, abs(target - from))).toLong()
        morphAnimator =
            ValueAnimator.ofFloat(from, target).apply {
                this.duration = scaled
                this.interpolator = interpolator
                addUpdateListener { card.applyMorph(it.animatedValue as Float) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationStart(animation: Animator) {
                            // The contents fade as one GPU layer (smooth, no per-frame re-draw).
                            content.setLayerType(LAYER_TYPE_HARDWARE, null)
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            content.setLayerType(LAYER_TYPE_NONE, null)
                            if (morphAnimator === animation) morphAnimator = null
                            if (!cancelled) end?.invoke()
                        }
                    }
                )
                start()
            }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            setExpanded(false)
            return true
        }
        if (leaving) return true
        if (expanded) {
            // Any interaction keeps the card open a little longer.
            host.mainHandler().removeCallbacks(collapseRunnable)
            host.mainHandler().postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun openApp() {
        val intent = data?.clickIntent ?: return
        host.openApp(intent)
    }

    /** Fly the pill off the side, then hide the island. */
    private fun dismissTo(direction: Float) {
        if (leaving) return
        leaving = true
        host.mainHandler().removeCallbacks(collapseRunnable)
        requestLayout()
        subtleTick()
        pill.animate()
            .translationX(direction * width.coerceAtLeast(pill.width) * 0.75f)
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(standard)
            .withEndAction { host.dismiss() }
            .start()
    }

    // Card contents ---------------------------------------------------------------------------------

    private fun buildCard() {
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(18), dp(18), dp(18), dp(18))
        card.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Header: art, title / artist, app badge. Not a button: a tap here opens the app.
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
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
        appBadge.imageTintList = ColorStateList.valueOf(0xFF111111.toInt())
        appBadge.setPadding(dp(5), dp(5), dp(5), dp(5))
        header.addView(appBadge, LinearLayout.LayoutParams(dp(26), dp(26)))
        content.addView(header)

        // Progress: a tall touch area with a thin line, so the song can be dragged to a position.
        content.addView(
            progress,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(24)).apply { topMargin = dp(6) },
        )
        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(elapsed, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        total.gravity = Gravity.END
        times.addView(total, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        content.addView(times, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Controls: output, previous, play / pause, next, app action. Five fixed slots, so play /
        // pause is always in the middle even when an app has no previous or no action.
        val controls =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        outputButton.setImageDrawable(OutputDrawable())
        outputButton.contentDescription = "Output device"
        outputButton.setOnClickListener { openOutputSwitcher() }
        prevButton.setImageDrawable(prevIcon)
        nextButton.setImageDrawable(nextIcon)
        playButton.setImageDrawable(playIcon)
        playButton.imageTintList = null
        playButton.setPadding(dp(14), dp(14), dp(14), dp(14))
        playButton.background = circle(Color.WHITE)
        playButton.elevation = dp(4).toFloat()
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
        val key = recents.joinToString { "${it.packageName}|${it.song}" }
        if (key == recentsKey) return
        recentsKey = key
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

    private fun bindControl(button: ImageButton, icon: SkipDrawable, action: MediaAction?, label: String) {
        val enabled = action?.action != null
        button.contentDescription = action?.contentDescription ?: label
        button.isEnabled = enabled
        icon.alpha = if (enabled) 255 else 80
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
        durationMs = duration
        progress.seekable =
            state != null && duration > 0L && (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L
        if (progress.dragging) return
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

    // Helpers ---------------------------------------------------------------------------------------

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
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
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

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** 0 below [from], 1 above [to], smooth in between. */
    private fun smooth(t: Float, from: Float, to: Float): Float {
        val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Resistance: follows the finger at first, then hardly moves, never past [limit]. */
    private fun rubber(distance: Float, limit: Float): Float =
        if (distance <= 0f) 0f else limit * (1f - exp(-distance / (limit * 2.5f)))

    private fun rubberSigned(distance: Float, limit: Float): Float =
        sign(distance) * rubber(abs(distance), limit)

    /** A barely-there tap, for snapping back and opening. */
    private fun subtleTick() {
        val v = vibrator ?: return
        if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.12f)
                    .compose()
            )
        }
    }

    /** Tracks one touch: tap, sideways, up or down, plus how long it was held. */
    private inner class Gesture {
        var mode = NONE
        var tracking = false
        var dx = 0f
        var dy = 0f
        var vx = 0f
        var vy = 0f
        var heldMs = 0L
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var velocity: VelocityTracker? = null

        fun track(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = NONE
                    tracking = true
                    downX = ev.rawX
                    downY = ev.rawY
                    downTime = ev.eventTime
                    dx = 0f
                    dy = 0f
                    vx = 0f
                    vy = 0f
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(ev)
                    dx = ev.rawX - downX
                    dy = ev.rawY - downY
                    if (mode == NONE && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        mode = when {
                            abs(dx) > abs(dy) -> SIDEWAYS
                            dy < 0 -> UP
                            else -> DOWN
                        }
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    heldMs = ev.eventTime - downTime
                    velocity?.let {
                        it.addMovement(ev)
                        it.computeCurrentVelocity(1000)
                        vx = it.xVelocity
                        vy = it.yVelocity
                        it.recycle()
                    }
                    velocity = null
                    tracking = false
                }
            }
        }
    }

    // The pill --------------------------------------------------------------------------------------

    /** The compact pill: album art, the camera in the middle, and moving sound bars. */
    private inner class PillView(context: Context) : View(context) {
        var art: Bitmap? = null
        var accent = DEFAULT_ACCENT
        var playing = true
        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        private val artClip = Path()
        private var phase = 0f
        private val gesture = Gesture()
        private var settleAnimator: ValueAnimator? = null
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
            isClickable = true
            // For accessibility (touches are handled in onTouchEvent).
            setOnClickListener { setExpanded(true) }
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                    }
                }
            clipToOutline = true
            rim.strokeWidth = dp(1).toFloat() / 2f
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator.start()
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        fun stop() {
            animator.cancel()
            settleAnimator?.cancel()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            body.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF16171C.toInt(), Color.BLACK, Shader.TileMode.CLAMP)
            rim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x40FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (leaving || expanded) return true
            gesture.track(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    settleAnimator?.cancel()
                    animate().cancel()
                    pivotX = width / 2f
                    pivotY = height / 2f
                    // Lifted under the finger, just a touch.
                    animate().scaleX(LIFT).scaleY(LIFT).setDuration(160).setInterpolator(decelerate).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gesture.mode != NONE) {
                        animate().cancel()
                        follow()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val sideways = abs(gesture.dx) > abs(gesture.dy)
                    val third = (this@MiraCLOIslandView.width / 3f).coerceAtLeast(dp(100).toFloat())
                    val flick = abs(gesture.vx) > flingVelocity && abs(gesture.vx) > abs(gesture.vy)
                    when {
                        // Swiped sideways far enough, or flicked: always hide.
                        sideways && (abs(gesture.dx) > third || flick) ->
                            dismissTo(if (gesture.dx + gesture.vx * 0.05f < 0) -1f else 1f)
                        // Pulled down: open the app.
                        !sideways && gesture.dy > dp(36) -> {
                            subtleTick()
                            openApp()
                        }
                        // A tap (not a long press): open the card.
                        gesture.mode == NONE && gesture.heldMs < longPressMs -> {
                            resetTransform()
                            setExpanded(true)
                        }
                        else -> settle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> settle()
            }
            return true
        }

        /** Follows the finger: sideways one to one (so it can be swiped off), down with resistance. */
        private fun follow() {
            translationX = gesture.dx
            translationY =
                if (gesture.dy >= 0) rubber(gesture.dy, (pillDragRoom - dp(12)).toFloat())
                else -rubber(-gesture.dy, dp(8).toFloat())
            // A hint of stretch in the direction of the pull.
            val pull = min(1f, (abs(gesture.dx) + abs(gesture.dy)) / dp(260))
            val horizontal = abs(gesture.dx) >= abs(gesture.dy)
            scaleX = LIFT + if (horizontal) 0.025f * pull else -0.015f * pull
            scaleY = LIFT + if (horizontal) -0.02f * pull else 0.03f * pull
        }

        private fun resetTransform() {
            animate().cancel()
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }

        /** Back home with a gentle spring. */
        private fun settle() {
            if (abs(translationX) > dp(6) || abs(translationY) > dp(6)) subtleTick()
            val sx = translationX
            val sy = translationY
            val ssx = scaleX
            val ssy = scaleY
            settleAnimator?.cancel()
            settleAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 420
                    interpolator = softSpring
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        translationX = lerp(sx, 0f, f)
                        translationY = lerp(sy, 0f, f)
                        scaleX = lerp(ssx, 1f, f)
                        scaleY = lerp(ssy, 1f, f)
                    }
                    start()
                }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f

            // Body: glossy black, like the camera cut-out it sits on.
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, body)
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
                artClip.reset()
                artClip.addRoundRect(rect, dp(7).toFloat(), dp(7).toFloat(), Path.Direction.CW)
                canvas.clipPath(artClip)
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
                val scale = if (playing) 0.35f + 0.65f * abs(1f - 2f * t) else 0.3f
                val bh = maxH * scale
                rect.set(x, h / 2f - bh / 2f, x + barW, h / 2f + bh / 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, bar)
                x += barW + gap
            }
        }
    }

    // The glow behind the card ----------------------------------------------------------------------

    /** Ambient glow behind the expanded card, coloured from the album art. Drawn once, then moved. */
    private inner class AuraView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var c1 = DEFAULT_ACCENT
        private var c2 = DEFAULT_ACCENT

        init {
            setRenderEffect(RenderEffect.createBlurEffect(dp(30).toFloat(), dp(30).toFloat(), Shader.TileMode.DECAL))
        }

        /**
         * Take no space of its own: the window wraps its content, and a plain View asked to "match
         * parent" there would report the whole screen, making the window cover the screen. The
         * FrameLayout then sizes it to the window.
         */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY)
                MeasureSpec.getSize(widthMeasureSpec) else 0
            val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
                MeasureSpec.getSize(heightMeasureSpec) else 0
            setMeasuredDimension(w, h)
        }

        fun colors(art: Int, accent: Int) {
            c1 = art
            c2 = accent
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            // Around where the card rests (the card's movement is applied as a translation).
            val l = card.left.toFloat()
            val t = card.top.toFloat()
            val w = card.width.toFloat()
            val h = card.height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.shader = RadialGradient(l + w * 0.28f, t + h * 0.35f, w * 0.45f,
                (c1 and 0x00FFFFFF) or 0x40000000, 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(l + w * 0.28f, t + h * 0.35f, w * 0.45f, paint)
            paint.shader = RadialGradient(l + w * 0.72f, t + h * 0.7f, w * 0.45f,
                (c2 and 0x00FFFFFF) or 0x33000000, 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(l + w * 0.72f, t + h * 0.7f, w * 0.45f, paint)
        }
    }

    // The card --------------------------------------------------------------------------------------

    /**
     * The expanded card. [morph] runs from 0 (exactly the pill: same place, size and black) to 1
     * (the frosted card below the camera). The shape is an outline clip, so morphing only moves the
     * clip and a translation; nothing is re-laid out.
     */
    private inner class CardView(context: Context) : FrameLayout(context) {
        var accent = DEFAULT_ACCENT
        var morph = 1f
            private set
        var dragX = 0f
        var dragY = 0f
        private val clip = RectF()
        private var clipRadius = cardRadius
        private val clipInt = Rect()
        private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6141519.toInt() }
        private val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val touchGlow = Paint(Paint.ANTI_ALIAS_FLAG)
        private var blur: BackgroundBlurDrawable? = null
        private var touchX = 0f
        private var touchY = 0f
        private var glowAlpha = 0f
        private var glowAnimator: ValueAnimator? = null
        private var settleAnimator: ValueAnimator? = null
        private val gesture = Gesture()

        init {
            setWillNotDraw(false)
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        clip.roundOut(clipInt)
                        outline.setRoundRect(clipInt, clipRadius)
                    }
                }
            clipToOutline = true
            rim.strokeWidth = dp(1).toFloat()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // Real frosted glass: blur what is behind the card (cross-window blur).
            blur =
                viewRootImpl?.createBackgroundBlurDrawable()?.apply {
                    setBlurRadius(dp(36))
                    setColor(0x8C141519.toInt())
                }
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        fun stop() {
            glowAnimator?.cancel()
            settleAnimator?.cancel()
        }

        fun setBlurAlpha(a: Float) {
            blur?.alpha = (255 * a).toInt().coerceIn(0, 255)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x47FFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP)
            glossPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.45f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            applyMorph(morph)
        }

        /** Places the shape for [p] (0 = pill, 1 = card), plus any drag offset. */
        fun applyMorph(p: Float) {
            morph = p
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val pw = min(pillW.toFloat(), w)
            clip.left = lerp((w - pw) / 2f, 0f, p)
            clip.right = w - clip.left
            clip.top = 0f
            clip.bottom = lerp(pillH.toFloat(), h, p)
            clipRadius = lerp(pillH / 2f, cardRadius, p)

            // From the pill's place on the status bar down to below the camera.
            val startY = (pillTop() - top).toFloat()
            translationY = lerp(startY, 0f, p) + dragY
            translationX = dragX
            elevation = dp(14) * p

            content.alpha = smooth(p, 0.5f, 1f)
            content.translationY = lerp(-dp(10).toFloat(), 0f, smooth(p, 0.35f, 1f))

            aura.alpha = smooth(p, 0.25f, 1f)
            aura.translationX = translationX
            aura.translationY = translationY
            // The pill stays on top and fades away as the card starts to grow (and back).
            pill.alpha = 1f - smooth(p, 0f, 0.22f)

            invalidateOutline()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            clip.roundOut(clipInt)
            val b = blur
            if (b != null) {
                b.setCornerRadius(clipRadius)
                b.setBounds(clipInt.left, clipInt.top, clipInt.right, clipInt.bottom)
                b.draw(canvas)
            } else {
                canvas.drawRoundRect(clip, clipRadius, clipRadius, fill)
            }
            glossPaint.alpha = (255 * morph).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(clip, clipRadius, clipRadius, glossPaint)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            // Glow under the finger: a faint light, never a flash.
            if (glowAlpha > 0.01f) {
                val radius = width * 0.3f
                val a = (glowAlpha * 255).toInt().coerceIn(0, 255)
                touchGlow.shader = RadialGradient(
                    touchX, touchY, radius,
                    intArrayOf(Color.argb((a * 0.08f).toInt(), 255, 255, 255),
                        Color.argb((a * 0.06f).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.TRANSPARENT),
                    floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(touchX, touchY, radius, touchGlow)
            }
            // Black "pill skin" while it is still (or again) mostly the pill.
            val skinAlpha = 1f - smooth(morph, 0f, 0.6f)
            if (skinAlpha > 0f) {
                skin.alpha = (255 * skinAlpha).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(clip, clipRadius, clipRadius, skin)
            }
            // Glass rim
            rim.alpha = (255 * morph).toInt().coerceIn(0, 255)
            val inset = rim.strokeWidth / 2f
            canvas.drawRoundRect(clip.left + inset, clip.top + inset, clip.right - inset,
                clip.bottom - inset, clipRadius, clipRadius, rim)
        }

        private fun busy() = leaving || morphAnimator != null || !expanded

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
                    if (glowAlpha > 0.01f) invalidate()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> animateGlow(0f)
            }
            return super.dispatchTouchEvent(ev)
        }

        /** Take over from the buttons once the finger clearly moves (a swipe, not a tap). */
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (busy()) return true
            gesture.track(ev)
            return gesture.mode != NONE
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (busy()) return true
            // Events reaching here directly (empty area) are not yet tracked by intercept.
            if (ev.actionMasked != MotionEvent.ACTION_DOWN || !gesture.tracking) gesture.track(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> settleAnimator?.cancel()
                MotionEvent.ACTION_MOVE -> drag()
                MotionEvent.ACTION_UP -> release()
                MotionEvent.ACTION_CANCEL -> settle()
            }
            return true
        }

        private fun drag() {
            when (gesture.mode) {
                SIDEWAYS -> {
                    dragX = rubberSigned(gesture.dx, dp(16).toFloat())
                    applyMorph(morph)
                }
                DOWN -> {
                    dragY = rubber(gesture.dy, dp(20).toFloat())
                    applyMorph(morph)
                }
                // Swipe up: the card turns back into the pill under the finger.
                UP -> applyMorph(1f - (-gesture.dy / collapseRange()).coerceIn(0f, 1f))
            }
        }

        private fun collapseRange() = height * 0.9f + dp(40)

        private fun release() {
            when (gesture.mode) {
                // Tap where there is no button: open the app. A long press only shows the glow.
                NONE ->
                    if (gesture.heldMs < longPressMs) {
                        subtleTick()
                        openApp()
                    }
                UP ->
                    if (morph < 0.75f || gesture.vy < -flingVelocity) {
                        setExpanded(false)
                    } else {
                        animateMorph(1f, EXPAND_MS, decelerate)
                    }
                else -> settle()
            }
        }

        /** Back in place with a gentle spring. */
        private fun settle() {
            if (abs(dragX) > dp(4) || abs(dragY) > dp(4)) subtleTick()
            val sx = dragX
            val sy = dragY
            settleAnimator?.cancel()
            settleAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 420
                    interpolator = softSpring
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        dragX = lerp(sx, 0f, f)
                        dragY = lerp(sy, 0f, f)
                        applyMorph(morph)
                    }
                    start()
                }
        }

        private fun animateGlow(target: Float) {
            glowAnimator?.cancel()
            glowAnimator =
                ValueAnimator.ofFloat(glowAlpha, target).apply {
                    duration = if (target > glowAlpha) 160 else 420
                    addUpdateListener {
                        glowAlpha = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }
    }

    // Progress line ---------------------------------------------------------------------------------

    /** Thin progress line with a white played part. Drag it to move through the song. */
    private inner class ProgressLine(context: Context) : View(context) {
        var fraction = 0f
            set(value) {
                field = value
                invalidate()
            }
        var seekable = false
        var dragging = false
            private set
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
        private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!seekable || durationMs <= 0L) return false
            val f = (ev.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = true
                    preview(f)
                }
                MotionEvent.ACTION_MOVE -> preview(f)
                MotionEvent.ACTION_UP -> {
                    preview(f)
                    mediaController?.transportControls?.seekTo((f * durationMs).toLong())
                    dragging = false
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    updateProgress()
                }
            }
            return true
        }

        private fun preview(f: Float) {
            fraction = f
            elapsed.text = formatTime((f * durationMs).toLong())
        }

        override fun onDraw(canvas: Canvas) {
            // The line sits in the middle of the taller touch area; thicker while dragging.
            val h = dp(if (dragging) 7 else 4).toFloat()
            val top = (height - h) / 2f
            val r = h / 2f
            rect.set(0f, top, width.toFloat(), top + h)
            canvas.drawRoundRect(rect, r, r, track)
            rect.set(0f, top, width * fraction.coerceIn(0f, 1f), top + h)
            if (rect.width() > 0f) canvas.drawRoundRect(rect, r, r, played)
        }
    }

    // Icons -----------------------------------------------------------------------------------------

    /** Base for the small drawn icons: no intrinsic size, so the button sizes them. */
    private abstract class IconDrawable : Drawable() {
        protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var alphaValue = 255

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
            invalidateSelf()
        }

        override fun getAlpha() = alphaValue

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Play (triangle) and pause (two bars), switching with a short fade and scale. */
    private inner class PlayPauseDrawable : IconDrawable() {
        var playing = false
            private set
        private var t = 0f // 0 = play shape, 1 = pause shape
        private var anim: ValueAnimator? = null
        private val path = Path()

        init {
            paint.color = 0xFF111111.toInt()
            paint.strokeJoin = Paint.Join.ROUND
            paint.style = Paint.Style.FILL_AND_STROKE
        }

        fun setPlaying(value: Boolean, animate: Boolean) {
            val target = if (value) 1f else 0f
            if (playing == value && anim == null && t == target) return
            playing = value
            anim?.cancel()
            anim = null
            if (!animate) {
                t = target
                invalidateSelf()
                return
            }
            anim =
                ValueAnimator.ofFloat(t, target).apply {
                    duration = 180
                    interpolator = decelerate
                    addUpdateListener {
                        t = it.animatedValue as Float
                        invalidateSelf()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (anim === animation) anim = null
                            }
                        }
                    )
                    start()
                }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = min(b.width(), b.height()) / 2f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            paint.strokeWidth = s * 0.14f
            val base = getAlpha()

            // Pause
            if (t > 0f) {
                paint.alpha = (base * t).toInt()
                val k = 0.75f + 0.25f * t
                val bw = s * 0.32f * k
                val bh = s * 1.1f * k
                val gap = s * 0.22f * k
                canvas.drawRoundRect(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f, bw / 3f, bw / 3f, paint)
                canvas.drawRoundRect(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f, bw / 3f, bw / 3f, paint)
            }
            // Play
            if (t < 1f) {
                paint.alpha = (base * (1f - t)).toInt()
                val k = 0.75f + 0.25f * (1f - t)
                path.reset()
                path.moveTo(cx - s * 0.38f * k, cy - s * 0.58f * k)
                path.lineTo(cx - s * 0.38f * k, cy + s * 0.58f * k)
                path.lineTo(cx + s * 0.62f * k, cy)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    /** Previous / next: a triangle and a bar. */
    private inner class SkipDrawable(private val forward: Boolean) : IconDrawable() {
        private val path = Path()

        init {
            paint.color = Color.WHITE
            paint.strokeJoin = Paint.Join.ROUND
            paint.style = Paint.Style.FILL_AND_STROKE
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = min(b.width(), b.height()) / 2f * 0.62f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            paint.alpha = getAlpha()
            paint.strokeWidth = s * 0.16f
            val d = if (forward) 1f else -1f
            path.reset()
            path.moveTo(cx - d * s * 0.7f, cy - s * 0.75f)
            path.lineTo(cx - d * s * 0.7f, cy + s * 0.75f)
            path.lineTo(cx + d * s * 0.45f, cy)
            path.close()
            canvas.drawPath(path, paint)
            val barX = cx + d * s * 0.62f
            canvas.drawRoundRect(barX - s * 0.1f, cy - s * 0.75f, barX + s * 0.1f, cy + s * 0.75f,
                s * 0.1f, s * 0.1f, paint)
        }
    }

    /** Audio output: a screen outline with a triangle below (the usual "play on" sign). */
    private inner class OutputDrawable : IconDrawable() {
        private val path = Path()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            paint.color = Color.WHITE
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = min(b.width(), b.height()) / 2f * 0.66f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            stroke.alpha = getAlpha()
            paint.alpha = getAlpha()
            stroke.strokeWidth = s * 0.15f
            val l = cx - s
            val r = cx + s
            val t = cy - s * 0.85f
            val bottom = cy + s * 0.35f
            val rad = s * 0.2f
            // Screen outline, open at the bottom middle for the triangle.
            path.reset()
            path.moveTo(cx - s * 0.42f, bottom)
            path.lineTo(l + rad, bottom)
            path.quadTo(l, bottom, l, bottom - rad)
            path.lineTo(l, t + rad)
            path.quadTo(l, t, l + rad, t)
            path.lineTo(r - rad, t)
            path.quadTo(r, t, r, t + rad)
            path.lineTo(r, bottom - rad)
            path.quadTo(r, bottom, r - rad, bottom)
            path.lineTo(cx + s * 0.42f, bottom)
            canvas.drawPath(path, stroke)
            path.reset()
            path.moveTo(cx, cy + s * 0.05f)
            path.lineTo(cx + s * 0.45f, cy + s * 0.95f)
            path.lineTo(cx - s * 0.45f, cy + s * 0.95f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val NONE = 0
        private const val SIDEWAYS = 1
        private const val UP = 2
        private const val DOWN = 3
        private const val LIFT = 1.03f
        private const val EXPAND_MS = 420L
        private const val COLLAPSE_MS = 340L
        private val DEFAULT_ACCENT = 0xFF34D399.toInt()
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val AUTO_COLLAPSE_MS = 8000L
    }
}
