/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
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
import android.util.PathParser
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
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
import kotlin.math.sqrt

/**
 * The Dynamic Island window content.
 *
 * Every activity is ONE view ([Item]) that is always the same shape, morphed between three forms:
 * a round pill beside the camera, the main pill on the camera, and a card under the status bar.
 * Each change of state only retargets those shapes (position, size, corners, how much "card" and
 * how much "bubble" they are) and animates from wherever they are now, so every transition is a
 * single continuous movement and an interrupted one simply carries on to the new target.
 *
 * Morphing never re-lays out anything: the shape is an outline clip plus a translation, the card
 * contents fade as one GPU layer, and the coloured glow is drawn once and then only moved.
 */
@SuppressLint("ViewConstructor")
class MiraCLOIslandView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun mainHandler(): Handler

        /** A pill was tapped: open it as the top card. */
        fun onTapPill(key: String)

        /** The main pill was swiped onto a round pill: swap them. False if that is not possible. */
        fun onSwap(fromKey: String, toKey: String): Boolean

        /** The main pill was swiped up: close it. */
        fun onDismiss(key: String)

        /** Open the activity's app (tap a card, pull the main pill down). */
        fun onOpenApp(key: String)

        /** Fold every card back into its pill (tap outside, swipe a card up). */
        fun onCollapseAll()

        /** A recently played album on the music card: play it here, in place of the current one. */
        fun onPlayRecent(recent: MediaData)

        /** A card button (pause, lap, end call, ...). */
        fun onAction(key: String, action: IslandAction)

        /** Everything has left and finished animating out: the window may go. */
        fun onEmpty()
    }

    data class Config(
        val touchGlow: Boolean = true,
        val ambientGlow: Boolean = true,
        val haptics: Boolean = true,
        /** Liquid Glass cards: clearer glass with a bright edge instead of the frosted dark glass. */
        val glass: Boolean = false,
    )

    /**
     * [pills] by slot: main, right, left. A slot whose activity is open as a card is null, so the
     * other pills keep their places. [cards]: top card first.
     */
    data class State(val pills: List<IslandActivity?>, val cards: List<IslandActivity>)

    /** How an activity leaves when it is no longer shown. */
    enum class Exit {
        /** The activity ended: it shrinks back into the camera. */
        ENDED,
        /** Swiped up: it slides up and fades. */
        SWIPED_UP,
        /** Its app opened over it: a quick fade. */
        OPENED,
    }

    private enum class Role { MAIN, RIGHT, LEFT, CARD }

    var config = Config()
        set(value) {
            field = value
            items.values.forEach { it.onConfigChanged() }
        }

    // Sizes --------------------------------------------------------------------------------------

    private val pillW = dp(174).toFloat()
    private val pillH = dp(34).toFloat()
    private val bubbleGap = dp(6).toFloat()
    private val compactH = dp(84)
    private val compactRadius = dp(30).toFloat()
    private val cardRadius = dp(30).toFloat()
    private val cardGap = dp(8).toFloat()
    private val auraMargin = dp(36)
    private val auraHeight = dp(330)

    // Motion -------------------------------------------------------------------------------------

    /** Large moves (open / close cards): quick start, long soft landing. */
    private val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Exits. */
    private val standard = PathInterpolator(0.3f, 0f, 0.1f, 1f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val flingVelocity = dp(1000).toFloat()
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    // State --------------------------------------------------------------------------------------

    private val items = LinkedHashMap<String, Item>()
    private val exitStyles = HashMap<String, Exit>()
    private var state = State(emptyList(), emptyList())
    private var retargetPending = false
    private var released = false

    /** Island-wide visibility: faded out (not removed) for the shade, full screen, lock screen. */
    private var shownTarget = true
    private var shownFactor = 1f
    private var shadeFraction = 0f
    private var shownAnimator: ValueAnimator? = null

    /** One frame loop drives every shape's spring. */
    private var frameScheduled = false
    private var lastFrameNs = 0L
    private val frameCallback =
        Choreographer.FrameCallback { now ->
            frameScheduled = false
            if (released) return@FrameCallback
            val dt =
                if (lastFrameNs == 0L) 1f / 90f
                else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0.001f, 0.034f)
            lastFrameNs = now
            var running = false
            for (item in items.values.toList()) {
                if (item.springing) running = item.step(dt) or running
            }
            if (running) scheduleFrame() else lastFrameNs = 0L
        }

    private val collapseRunnable = Runnable { host.onCollapseAll() }
    private val tickRunnable =
        object : Runnable {
            override fun run() {
                if (released) return
                var keepGoing = false
                for (item in items.values) keepGoing = item.tick() or keepGoing
                if (keepGoing) host.mainHandler().postDelayed(this, TICK_MS)
            }
        }

    /** Only pills and cards take touches; elsewhere the window lets touches through. */
    private val touchRect = Rect()
    private val insetsListener =
        ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.setEmpty()
            if (effectiveAlpha() < 0.5f) return@OnComputeInternalInsetsListener
            for (item in items.values) {
                if (item.leaving) continue
                item.hitRect(touchRect)
                info.touchableRegion.op(touchRect, android.graphics.Region.Op.UNION)
            }
        }

    private val preDraw =
        ViewTreeObserver.OnPreDrawListener {
            if (retargetPending) {
                retargetPending = false
                retarget()
            }
            true
        }

    init {
        clipChildren = false
        clipToPadding = false
    }

    // Geometry -----------------------------------------------------------------------------------

    private fun statusBarHeight() = SystemBarUtils.getStatusBarHeight(context)

    /** Pills are centred on the status bar icons (clock, battery), around the camera. */
    private fun pillTop(): Float {
        val padTop = resources.getDimensionPixelSize(R.dimen.status_bar_padding_top)
        return max(0f, (statusBarHeight() + padTop) / 2f - pillH / 2f)
    }

    /** Cards sit just under the status bar, with a small gap. */
    private fun cardTop(): Float = statusBarHeight() + dp(6).toFloat()

    private fun cardWidth(): Int = min(resources.displayMetrics.widthPixels - dp(36), dp(420))

    private fun cameraX(): Float {
        val cutout = context.display?.cutout?.boundingRectTop
        return if (cutout != null && !cutout.isEmpty) cutout.exactCenterX() else width / 2f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    fun onHostConfigurationChanged() {
        val w = cardWidth()
        for (item in items.values) {
            (item.layoutParams as LayoutParams).width = w
            (item.aura.layoutParams as LayoutParams).width = w + auraMargin * 2
        }
        retargetPending = true
        requestLayout()
    }

    fun release() {
        released = true
        host.mainHandler().removeCallbacks(collapseRunnable)
        host.mainHandler().removeCallbacks(tickRunnable)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        shownAnimator?.cancel()
        items.values.forEach { it.stop() }
        IslandStatusBarSpace.request(0)
    }

    /** True once nothing is shown or animating out. */
    val isEmpty: Boolean
        get() = items.isEmpty()

    // Visibility ---------------------------------------------------------------------------------

    /** Fades the whole island in or out (lock screen, full screen, dozing) without rebuilding it. */
    fun setShown(shown: Boolean, animate: Boolean = true) {
        if (shown == shownTarget) return
        shownTarget = shown
        shownAnimator?.cancel()
        if (!animate) {
            shownFactor = if (shown) 1f else 0f
            applyAlpha()
            publishStatusBarSpace()
            return
        }
        shownAnimator =
            ValueAnimator.ofFloat(shownFactor, if (shown) 1f else 0f).apply {
                duration = if (shown) 220 else 150
                interpolator = standard
                addUpdateListener {
                    shownFactor = it.animatedValue as Float
                    applyAlpha()
                }
                start()
            }
        publishStatusBarSpace()
    }

    /** Follows the notification shade: gone by the time it is a little open. */
    fun setShadeFraction(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        if (f == shadeFraction) return
        val wasHidden = shadeFraction >= SHADE_HIDE_FRACTION
        shadeFraction = f
        applyAlpha()
        if (wasHidden != (f >= SHADE_HIDE_FRACTION)) publishStatusBarSpace()
    }

    private fun effectiveAlpha(): Float = shownFactor * (1f - smooth(shadeFraction, 0f, SHADE_HIDE_FRACTION))

    private fun applyAlpha() {
        val a = effectiveAlpha()
        if (a == alpha) return
        val wasTouchable = alpha >= 0.5f
        alpha = a
        visibility = if (a <= 0.001f) INVISIBLE else VISIBLE
        // Touchable region follows (none while faded out).
        if (wasTouchable != (a >= 0.5f)) requestLayout()
    }

    /** Tells the status bar which icons can stay beside the pills (see [IslandStatusBarSpace]). */
    private fun publishStatusBarSpace() {
        if (released || !shownTarget || shadeFraction >= SHADE_HIDE_FRACTION) {
            IslandStatusBarSpace.request(IslandStatusBarSpace.NONE)
            return
        }
        val live = { a: IslandActivity? -> a != null && items[a.key]?.leaving == false }
        val mode =
            when {
                live(state.pills.getOrNull(1)) || live(state.pills.getOrNull(2)) -> IslandStatusBarSpace.SIDE_PILLS
                live(state.pills.getOrNull(0)) -> IslandStatusBarSpace.MAIN_PILL
                else -> IslandStatusBarSpace.NONE
            }
        IslandStatusBarSpace.request(mode)
    }

    private fun scheduleFrame() {
        if (frameScheduled || released) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // Rendering ----------------------------------------------------------------------------------

    fun setExitStyle(key: String, exit: Exit) {
        exitStyles[key] = exit
    }

    fun render(newState: State) {
        state = newState
        val shown = newState.pills.filterNotNull() + newState.cards
        val keys = shown.map { it.key }.toSet()

        // Leaving.
        for ((key, item) in items.toMap()) {
            if (key !in keys && !item.leaving) item.exit(exitStyles.remove(key) ?: Exit.ENDED)
        }
        // Arriving / updating.
        val stacked = newState.cards.size > 1
        for (activity in shown) {
            val item = items[activity.key]?.takeIf { !it.leaving } ?: createItem(activity)
            item.bind(activity, stacked)
        }
        exitStyles.keys.retainAll(items.keys)

        if (newState.cards.isNotEmpty()) {
            scheduleAutoCollapse()
        } else {
            host.mainHandler().removeCallbacks(collapseRunnable)
        }

        retargetPending = true
        requestLayout()
        invalidate()
        startTicking()
    }

    private fun createItem(activity: IslandActivity): Item {
        // A key whose old view is still animating out gets a fresh one.
        items.remove(activity.key)?.let { old -> old.finishExitNow() }
        val item = Item(context, activity)
        items[activity.key] = item
        addView(item.aura, 0, LayoutParams(cardWidth() + auraMargin * 2, auraHeight, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(item, LayoutParams(cardWidth(), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        // A new activity grows out of the camera.
        val cx = cameraX().takeIf { width > 0 } ?: (resources.displayMetrics.widthPixels / 2f)
        val dot = dp(12).toFloat()
        item.jumpTo(Geo(cx - dot / 2f, pillTop() + pillH / 2f - dot / 2f, dot, dot, dot / 2f, 0f, 1f))
        item.alpha = 0f
        item.entering = true
        return item
    }

    /** Works out where every shape should be and sends each one there. */
    private fun retarget() {
        if (width == 0) return
        val cardW = cardWidth().toFloat()
        val cardLeft = (width - cardW) / 2f
        var y = cardTop()
        state.cards.forEachIndexed { i, a ->
            val item = items[a.key] ?: return@forEachIndexed
            val h = item.height.toFloat()
            val r = if (a.kind == IslandKind.MEDIA) cardRadius else compactRadius
            item.role = Role.CARD
            item.translationZ = dp(3) * (state.cards.size - i).toFloat()
            item.animateTo(Geo(cardLeft, y, cardW, h, r, 1f, 0f), CARD_STIFFNESS, CARD_DAMPING)
            y += h + cardGap
        }
        val pillLeft = cameraX() - pillW / 2f
        val top = pillTop()
        state.pills.forEachIndexed { i, a ->
            val item = a?.let { items[it.key] } ?: return@forEachIndexed
            item.translationZ = dp(10).toFloat()
            when (i) {
                0 -> {
                    item.role = Role.MAIN
                    item.animateTo(Geo(pillLeft, top, pillW, pillH, pillH / 2f, 0f, 0f), pillStiffness(item), pillDamping(item))
                }
                1 -> {
                    item.role = Role.RIGHT
                    item.animateTo(Geo(pillLeft + pillW + bubbleGap, top, pillH, pillH, pillH / 2f, 0f, 1f), pillStiffness(item), pillDamping(item))
                }
                else -> {
                    item.role = Role.LEFT
                    item.animateTo(Geo(pillLeft - bubbleGap - pillH, top, pillH, pillH, pillH / 2f, 0f, 1f), pillStiffness(item), pillDamping(item))
                }
            }
        }
        publishStatusBarSpace()
    }

    /** A card folding back into a pill lands without overshoot; pill moves get a soft one. */
    private fun pillStiffness(item: Item) = if (item.cardness > 0.05f) CARD_STIFFNESS else PILL_STIFFNESS

    private fun pillDamping(item: Item) = if (item.cardness > 0.05f) CARD_DAMPING else PILL_DAMPING

    /** Called when any shape finishes moving or leaving. */
    private fun onItemSettled() {
        if (items.isEmpty()) host.onEmpty()
    }

    private fun removeItem(item: Item) {
        if (items[item.key] === item) items.remove(item.key)
        item.stop()
        removeView(item.aura)
        removeView(item)
        onItemSettled()
    }

    private fun scheduleAutoCollapse() {
        host.mainHandler().removeCallbacks(collapseRunnable)
        // "Time's up" stays open until it is answered.
        if (state.cards.any { it.alert }) return
        host.mainHandler().postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
    }

    private fun startTicking() {
        host.mainHandler().removeCallbacks(tickRunnable)
        host.mainHandler().post(tickRunnable)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (state.cards.isNotEmpty()) host.onCollapseAll()
            return true
        }
        if (state.cards.isNotEmpty()) scheduleAutoCollapse()
        return super.dispatchTouchEvent(ev)
    }

    // Helpers ------------------------------------------------------------------------------------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** 0 below [from], 1 above [to], smooth in between. */
    private fun smooth(t: Float, from: Float, to: Float): Float {
        val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Resistance: follows the finger at first, then hardly moves, never past [limit]. */
    private fun rubber(distance: Float, limit: Float): Float =
        sign(distance) * limit * (1f - exp(-abs(distance) / (limit * 2.5f)))

    /** A barely-there tap. */
    private fun subtleTick() {
        if (!config.haptics) return
        val v = vibrator ?: return
        if (v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.12f)
                    .compose()
            )
        }
    }

    private fun text(sizeSp: Float, color: Int, weight: Int) =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create(Typeface.DEFAULT, weight, false)
            fontFeatureSettings = "tnum"
        }

    private fun circle(color: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun roundRect(color: Int, radius: Float) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }

    private fun isLight(color: Int): Boolean {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b > 0.55f
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    // Shapes -------------------------------------------------------------------------------------

    /** Where a shape is: box in window pixels, corner radius, how much card (c) and bubble (b). */
    private data class Geo(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val r: Float,
        val c: Float,
        val b: Float,
    )

    private fun lerpGeo(a: Geo, b: Geo, t: Float) =
        Geo(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t),
            lerp(a.r, b.r, t), lerp(a.c, b.c, t), lerp(a.b, b.b, t))

    /** Tracks one touch: tap, sideways, up or down, and how long it was held. */
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

    // One activity --------------------------------------------------------------------------------

    /**
     * One activity's shape. Laid out once at card size (centred, top of the window); every form is
     * that box clipped by an outline and moved by a translation.
     */
    private inner class Item(context: Context, first: IslandActivity) : FrameLayout(context) {
        var activity = first
            private set
        val key = first.key
        val kind = first.kind
        var role = Role.MAIN
        var leaving = false
            private set
        var entering = false
        /** The spring is running. */
        var springing = false
            private set
        /** Still clearly on its way (touches wait); the last few pixels of a landing do not count. */
        val moving: Boolean
            get() =
                exitAnimator?.isRunning == true ||
                    (springing &&
                        (abs(pos[0] - tgt[0]) > dp(6) || abs(pos[1] - tgt[1]) > dp(6) ||
                            abs(pos[2] - tgt[2]) > dp(6) || abs(pos[5] - tgt[5]) > 0.08f))
        /** Where it is heading. */
        val target: Geo
            get() = Geo(tgt[0], tgt[1], tgt[2], tgt[3], tgt[4], tgt[5], tgt[6])
        /** How much of a card it is right now. */
        val cardness: Float
            get() = geo.c

        val aura = AuraView(context)
        private val content: CardContent =
            if (kind == IslandKind.MEDIA) MediaContent(context, this) else CompactContent(context, this)

        private var geo = Geo(0f, 0f, 1f, 1f, 0f, 0f, 0f)
        // Spring state, one value per Geo field (x, y, w, h, r, c, b): position, velocity, target.
        private val pos = FloatArray(7)
        private val vel = FloatArray(7)
        private val tgt = FloatArray(7)
        private var stiffness = CARD_STIFFNESS
        private var damping = CARD_DAMPING
        private var exitAnimator: ValueAnimator? = null
        private var dragX = 0f
        private var dragY = 0f
        private val gesture = Gesture()
        private var ignoreTouch = false

        // Drawing
        private val clip = RectF()
        private val clipInt = Rect()
        private var blur: BackgroundBlurDrawable? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6141519.toInt() }
        private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
        private val skin = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pillRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val cardRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val touchGlow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            textSize = sp(14f)
            textAlign = Paint.Align.RIGHT
            fontFeatureSettings = "tnum"
        }
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val tmpRect = RectF()
        private val artClip = Path()
        private var pillText = ""
        private var shaderH = -1f
        private var touchX = 0f
        private var touchY = 0f
        private var glowAlpha = 0f
        private var glowAnimator: ValueAnimator? = null
        private var barPhase = 0f
        private val barAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    barPhase = it.animatedValue as Float
                    if (activity.media?.isPlaying == true && geo.c < 0.5f) invalidate()
                }
            }

        init {
            setWillNotDraw(false)
            clipChildren = true
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        clip.roundOut(clipInt)
                        outline.setRoundRect(clipInt, geo.r)
                    }
                }
            clipToOutline = true
            pillRim.strokeWidth = dp(1) / 2f
            cardRim.strokeWidth = dp(1).toFloat()
            addView(content.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            contentDescription = first.title
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            blur = viewRootImpl?.createBackgroundBlurDrawable()
            applyGlassStyle()
            if (kind == IslandKind.MEDIA) barAnimator.start()
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        fun stop() {
            springing = false
            exitAnimator?.cancel()
            glowAnimator?.cancel()
            barAnimator.cancel()
        }

        fun onConfigChanged() {
            applyGlassStyle()
            aura.invalidate()
            apply()
        }

        /**
         * Frosted dark glass (default), or Liquid Glass: much less blur and tint so what is
         * behind stays visible, with a bright specular edge (top-left and bottom-right) and a
         * soft inner light.
         */
        private fun applyGlassStyle() {
            val glass = config.glass
            blur?.setBlurRadius(dp(if (glass) 14 else 36))
            blur?.setColor(if (glass) 0x4D10131C else 0x8C141519.toInt())
            if (width > 0) updateRimShaders(width, height)
            invalidate()
        }

        private fun updateRimShaders(w: Int, h: Int) {
            if (config.glass) {
                gloss.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                cardRim.shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(0xF2FFFFFF.toInt(), 0x40FFFFFF, 0x0AFFFFFF, 0x59BED7FF, 0xD9FFFFFF.toInt()),
                    floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f),
                    Shader.TileMode.CLAMP,
                )
                cardRim.strokeWidth = dp(1) * 1.3f
            } else {
                gloss.shader = LinearGradient(0f, 0f, 0f, h * 0.45f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                cardRim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x47FFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP)
                cardRim.strokeWidth = dp(1).toFloat()
            }
        }

        fun bind(a: IslandActivity, stacked: Boolean) {
            activity = a
            contentDescription = a.title
            content.bind(a, stacked)
            aura.colors(a.accent, if (a.kind == IslandKind.MEDIA) a.accent else lighten(a.accent))
            refreshPillText()
            invalidate()
        }

        /** Live time; returns true while it needs more ticks. */
        fun tick(): Boolean {
            if (leaving) return false
            val clockRunning = activity.clock?.running == true
            if (clockRunning && refreshPillText()) invalidate()
            val cardTicking = content.tick(geo.c > 0.5f)
            return clockRunning || cardTicking
        }

        /** Updates the pill's value text; true if it changed. */
        private fun refreshPillText(): Boolean {
            val clock = activity.clock
            val t =
                when {
                    clock != null ->
                        IslandClock.format(clock.millis(), tenths = kind == IslandKind.STOPWATCH)
                    else -> (activity.value ?: activity.subtitle)?.toString().orEmpty()
                }
            if (t == pillText) return false
            pillText = t
            return true
        }

        // Geometry

        fun jumpTo(g: Geo) {
            springing = false
            write(g, pos)
            write(g, tgt)
            vel.fill(0f)
            geo = g
            apply()
        }

        /**
         * Sends the shape towards [g] on a spring. The current speed is kept, so a retarget in the
         * middle of a move bends the path instead of restarting it.
         */
        fun animateTo(g: Geo, k: Float, zeta: Float) {
            if (leaving) return
            val wasEntering = entering
            entering = false
            if (wasEntering) {
                animate().alpha(1f).setDuration(ENTER_FADE_MS).setInterpolator(standard).start()
            }
            if (!springing && !wasEntering && geo == g) return
            write(g, tgt)
            stiffness = k
            damping = zeta
            startSpring()
        }

        private fun startSpring() {
            if (!springing) {
                springing = true
                // Card contents fade as one GPU layer while morphing.
                content.view.setLayerType(LAYER_TYPE_HARDWARE, null)
            }
            scheduleFrame()
        }

        /** Advances the spring by [dt] seconds; returns true while it still moves. */
        fun step(dt: Float): Boolean {
            if (!springing) return false
            val k = stiffness
            val c = 2f * damping * sqrt(k)
            var left = dt
            while (left > 0f) {
                val h = min(left, SPRING_SUBSTEP)
                for (i in 0 until 7) {
                    val a = -k * (pos[i] - tgt[i]) - c * vel[i]
                    vel[i] += a * h
                    pos[i] += vel[i] * h
                }
                left -= h
            }
            var done = true
            for (i in 0 until 7) {
                val unit = i >= 5 // c and b are 0..1, the rest are pixels
                val dx = if (unit) 0.002f else 0.35f
                val dv = if (unit) 0.03f else 6f
                if (abs(pos[i] - tgt[i]) > dx || abs(vel[i]) > dv) {
                    done = false
                    break
                }
            }
            if (done) {
                tgt.copyInto(pos)
                vel.fill(0f)
            }
            geo = Geo(
                pos[0], pos[1], max(1f, pos[2]), max(1f, pos[3]), max(0f, pos[4]),
                pos[5].coerceIn(0f, 1f), pos[6].coerceIn(0f, 1f),
            )
            apply()
            if (done) {
                springing = false
                content.view.setLayerType(LAYER_TYPE_NONE, null)
                if (alpha < 1f && !leaving) alpha = 1f
                onItemSettled()
            }
            return !done
        }

        private fun write(g: Geo, out: FloatArray) {
            out[0] = g.x
            out[1] = g.y
            out[2] = g.w
            out[3] = g.h
            out[4] = g.r
            out[5] = g.c
            out[6] = g.b
        }

        fun exit(style: Exit) {
            leaving = true
            springing = false
            exitAnimator?.cancel()
            animate().cancel()
            val from = geo
            val camX = cameraX()
            val target =
                when (style) {
                    Exit.ENDED -> {
                        val dot = dp(12).toFloat()
                        Geo(camX - dot / 2f, pillTop() + pillH / 2f - dot / 2f, dot, dot, dot / 2f, 0f, 1f)
                    }
                    Exit.SWIPED_UP -> from.copy(y = from.y - from.h - dp(24))
                    Exit.OPENED -> from
                }
            val startAlpha = alpha
            val startDragX = dragX
            val startDragY = dragY
            val startScale = scaleX
            exitAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = if (style == Exit.OPENED) 160 else 300
                    interpolator = standard
                    addUpdateListener {
                        val t = it.animatedValue as Float
                        geo = lerpGeo(from, target, t)
                        dragX = lerp(startDragX, 0f, t)
                        dragY = lerp(startDragY, 0f, t)
                        scaleX = lerp(startScale, 1f, t)
                        scaleY = scaleX
                        alpha = startAlpha * (1f - smooth(t, if (style == Exit.ENDED) 0.3f else 0f, 1f))
                        apply()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) = removeItem(this@Item)
                        }
                    )
                    start()
                }
        }

        fun finishExitNow() {
            exitAnimator?.end()
        }

        /** Applies the current shape: translation, clip, glass amount, glow. */
        fun apply() {
            val g = geo
            translationX = g.x - left + dragX
            translationY = g.y - top + dragY
            clip.set(0f, 0f, g.w, g.h)
            elevation = dp(14) * g.c
            // Shadows only for cards (translationZ orders the stack but must not shade the pills).
            val shadow = Color.argb((255 * g.c).toInt().coerceIn(0, 255), 0, 0, 0)
            outlineSpotShadowColor = shadow
            outlineAmbientShadowColor = shadow
            content.view.alpha = smooth(g.c, 0.55f, 1f)
            content.view.translationY = lerp(-dp(8).toFloat(), 0f, smooth(g.c, 0.35f, 1f))
            aura.translationX = translationX
            aura.translationY = translationY - auraMargin
            aura.alpha = if (config.ambientGlow) smooth(g.c, 0.25f, 1f) * alpha else 0f
            aura.cardHeight = g.h
            blur?.alpha = (255 * g.c).toInt().coerceIn(0, 255)
            invalidateOutline()
            invalidate()
        }

        /** Whether a point (in this view's coordinates) is on the visible shape. */
        private fun isInShape(x: Float, y: Float): Boolean {
            val g = geo
            val grow = if (g.c > 0.5f) 0f else dp(if (g.b > 0.5f) 8 else 6).toFloat()
            return x >= -grow && y >= -grow && x <= g.w + grow && y <= g.h + grow
        }

        fun hitRect(out: Rect) {
            val g = geo
            val x = g.x + dragX
            val y = g.y + dragY
            out.set(x.toInt(), y.toInt(), (x + g.w).toInt(), (y + g.h).toInt())
            val grow = if (g.c > 0.5f) 0 else dp(if (g.b > 0.5f) 8 else 6)
            out.inset(-grow, -grow)
        }

        // Drawing

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateRimShaders(w, h)
            // The shape's target may depend on the height (card stack).
            retargetPending = true
            apply()
        }

        override fun onDraw(canvas: Canvas) {
            val g = geo
            clip.roundOut(clipInt)
            if (g.c > 0.001f) {
                val b = blur
                if (b != null) {
                    b.setCornerRadius(g.r)
                    b.setBounds(clipInt.left, clipInt.top, clipInt.right, clipInt.bottom)
                    b.draw(canvas)
                } else {
                    fill.alpha = (0xE6 * g.c).toInt()
                    canvas.drawRoundRect(clip, g.r, g.r, fill)
                }
                gloss.alpha = (255 * g.c).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(clip, g.r, g.r, gloss)
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val g = geo
            // Touch glow (cards only): a faint light under the finger.
            if (config.touchGlow && glowAlpha > 0.01f && g.c > 0.9f) {
                val radius = g.w * 0.32f
                val a = (glowAlpha * 255).toInt().coerceIn(0, 255)
                val ac = activity.accent
                touchGlow.shader = RadialGradient(
                    touchX, touchY, radius,
                    intArrayOf(Color.argb((a * 0.12f).toInt(), 255, 255, 255),
                        Color.argb((a * 0.10f).toInt(), Color.red(ac), Color.green(ac), Color.blue(ac)),
                        Color.TRANSPARENT),
                    floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(touchX, touchY, radius, touchGlow)
            }
            // Black skin: the pill / bubble body, fading into glass as it becomes a card.
            val skinA = 1f - smooth(g.c, 0f, 0.6f)
            if (skinA > 0f && g.h != shaderH) {
                shaderH = g.h
                skin.shader = LinearGradient(0f, 0f, 0f, g.h, 0xFF16171C.toInt(), Color.BLACK, Shader.TileMode.CLAMP)
                pillRim.shader = LinearGradient(0f, 0f, 0f, g.h, 0x40FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP)
            }
            if (skinA > 0f) {
                skin.alpha = (255 * skinA).toInt()
                canvas.drawRoundRect(clip, g.r, g.r, skin)
            }
            // Pill and bubble contents, gone as soon as it starts becoming a card.
            val small = 1f - smooth(g.c, 0f, 0.25f)
            val bubbleA = g.b.coerceIn(0f, 1f) * small
            val pillA = (1f - g.b).coerceIn(0f, 1f) * small
            if (pillA > 0.01f) drawPill(canvas, g, pillA)
            if (bubbleA > 0.01f) drawBubble(canvas, g, bubbleA)
            // Rims: thin on the pill, glassy on the card.
            if (skinA > 0f) {
                pillRim.alpha = (255 * skinA).toInt()
                val i = pillRim.strokeWidth / 2f
                canvas.drawRoundRect(i, i, g.w - i, g.h - i, g.r, g.r, pillRim)
            }
            if (g.c > 0f) {
                cardRim.alpha = (255 * g.c).toInt().coerceIn(0, 255)
                val i = cardRim.strokeWidth / 2f
                canvas.drawRoundRect(i, i, g.w - i, g.h - i, g.r, g.r, cardRim)
            }
        }

        private fun drawPill(canvas: Canvas, g: Geo, alpha: Float) {
            val h = g.h
            val a255 = (255 * alpha).toInt()
            // Left: album art, or the activity's icon in a disc.
            val lead = h - dp(10)
            val leadLeft = dp(5).toFloat()
            val leadTop = (h - lead) / 2f
            if (kind == IslandKind.MEDIA) {
                drawArt(canvas, activity.art, leadLeft, leadTop, lead, dp(7).toFloat(), a255)
            } else {
                drawDisc(canvas, leadLeft + lead / 2f, h / 2f, lead / 2f, a255)
            }
            // Right: sound bars for media, the live value for the others.
            if (kind == IslandKind.MEDIA) {
                barPaint.color = activity.accent
                barPaint.alpha = a255
                val barW = dp(3).toFloat()
                val gap = dp(2) + dp(1) / 2f
                val maxH = dp(16).toFloat()
                var x = g.w - dp(14) - (barW * 4 + gap * 3)
                val playing = activity.media?.isPlaying == true
                for (i in 0 until 4) {
                    val t = (barPhase + i * 0.2f) % 1f
                    val s = if (playing) 0.35f + 0.65f * abs(1f - 2f * t) else 0.3f
                    val bh = maxH * s
                    tmpRect.set(x, h / 2f - bh / 2f, x + barW, h / 2f + bh / 2f)
                    canvas.drawRoundRect(tmpRect, barW / 2f, barW / 2f, barPaint)
                    x += barW + gap
                }
            } else {
                valuePaint.color = valueColor()
                valuePaint.alpha = a255
                val baseline = h / 2f - (valuePaint.descent() + valuePaint.ascent()) / 2f
                canvas.drawText(pillText, g.w - dp(13), baseline, valuePaint)
            }
        }

        private fun drawBubble(canvas: Canvas, g: Geo, alpha: Float) {
            val a255 = (255 * alpha).toInt()
            val cx = g.w / 2f
            val cy = g.h / 2f
            if (kind == IslandKind.MEDIA) {
                val s = min(g.w, g.h) - dp(10)
                drawArt(canvas, activity.art, cx - s / 2f, cy - s / 2f, s, dp(6).toFloat(), a255)
            } else {
                drawDisc(canvas, cx, cy, min(g.w, g.h) / 2f - dp(5), a255)
            }
        }

        private fun drawArt(canvas: Canvas, art: Bitmap?, x: Float, y: Float, s: Float, r: Float, a: Int) {
            tmpRect.set(x, y, x + s, y + s)
            if (art == null) {
                discPaint.color = activity.accent
                discPaint.alpha = a
                canvas.drawRoundRect(tmpRect, r, r, discPaint)
                return
            }
            canvas.save()
            artClip.reset()
            artClip.addRoundRect(tmpRect, r, r, Path.Direction.CW)
            canvas.clipPath(artClip)
            artPaint.alpha = a
            canvas.drawBitmap(art, null, tmpRect, artPaint)
            canvas.restore()
        }

        /** The round icon at the left of a pill (or inside a round pill). */
        private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, radius: Float, a: Int) {
            val act = activity
            val solid = kind == IslandKind.CALL
            val glyph = glyphFor(act)
            val photo = act.art.takeIf { kind == IslandKind.LIVE || kind == IslandKind.PROGRESS }
            when {
                photo != null -> drawArt(canvas, photo, cx - radius, cy - radius, radius * 2f, radius, a)
                glyph == null && act.icon != null -> {
                    discPaint.color = act.accent
                    discPaint.alpha = (a * 0.18f).toInt()
                    canvas.drawCircle(cx, cy, radius, discPaint)
                    val d = act.icon
                    val s = (radius * 1.1f).toInt()
                    d.setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
                    d.setTint(act.accent)
                    d.alpha = a
                    d.draw(canvas)
                }
                else -> {
                    discPaint.color = if (solid) 0xFF22C55E.toInt() else act.accent
                    discPaint.alpha = if (solid) a else (a * 0.18f).toInt()
                    canvas.drawCircle(cx, cy, radius, discPaint)
                    Glyphs.draw(canvas, glyph ?: Glyphs.Glyph.DOT, cx, cy, radius * 1.15f,
                        if (solid) Color.WHITE else act.accent, a)
                }
            }
            // Progress (downloads, transfers): a ring around the icon.
            val p = act.progress
            if (p != null) {
                ringPaint.color = 0x2EFFFFFF
                ringPaint.alpha = (a * 0.18f).toInt()
                ringPaint.strokeWidth = dp(2).toFloat()
                tmpRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
                tmpRect.inset(ringPaint.strokeWidth / 2f, ringPaint.strokeWidth / 2f)
                canvas.drawArc(tmpRect, 0f, 360f, false, ringPaint)
                ringPaint.color = act.accent
                ringPaint.alpha = a
                canvas.drawArc(tmpRect, -90f, 360f * p, false, ringPaint)
            }
        }

        private fun valueColor(): Int = if (kind == IslandKind.STOPWATCH) Color.WHITE else activity.accent

        private fun glyphColor(): Int = activity.accent

        // Touch

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // Every shape is laid out at card size and only clipped to its form, so a touch can
            // land in a pill's invisible box. Refuse it there: the window then offers the touch
            // to the shape that really is under the finger.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !isInShape(ev.x, ev.y)) return false
            if (role == Role.CARD) {
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
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (leaving || moving) return true
            // Pills and bubbles: never let the (invisible) card contents get the touch.
            if (role != Role.CARD) return true
            gesture.track(ev)
            return gesture.mode != NONE
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            // A touch that starts while the shape is moving or leaving is ignored to its end.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) ignoreTouch = leaving || moving
            if (ignoreTouch || leaving) return true
            if (ev.actionMasked != MotionEvent.ACTION_DOWN || !gesture.tracking || role != Role.CARD) {
                gesture.track(ev)
            }
            when (role) {
                Role.CARD -> onCardTouch(ev)
                Role.MAIN -> onMainPillTouch(ev)
                Role.RIGHT,
                Role.LEFT -> onBubbleTouch(ev)
            }
            return true
        }

        private fun onMainPillTouch(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pivotX = geo.w / 2f
                    pivotY = geo.h / 2f
                    animate().scaleX(LIFT).scaleY(LIFT).setDuration(160).setInterpolator(decelerate).start()
                }
                MotionEvent.ACTION_MOVE ->
                    if (gesture.mode != NONE) {
                        dragX = rubber(gesture.dx, dp(44).toFloat())
                        dragY = if (gesture.dy < 0) rubber(gesture.dy, dp(20).toFloat()) else rubber(gesture.dy, dp(28).toFloat())
                        apply()
                    }
                MotionEvent.ACTION_UP -> {
                    val sideways = abs(gesture.dx) > abs(gesture.dy)
                    val flickX = abs(gesture.vx) > flingVelocity && abs(gesture.vx) > abs(gesture.vy)
                    when {
                        // Up: close this pill.
                        !sideways && (gesture.dy < -dp(22) || gesture.vy < -flingVelocity) -> {
                            subtleTick()
                            unlift()
                            host.onDismiss(key)
                        }
                        // Down: open the app.
                        !sideways && gesture.dy > dp(32) -> {
                            subtleTick()
                            unlift()
                            host.onOpenApp(key)
                        }
                        // Sideways: swap with the round pill on that side.
                        sideways && (abs(gesture.dx) > dp(36) || flickX) -> {
                            val right = gesture.dx + gesture.vx * 0.05f > 0
                            val other = (if (right) state.pills.getOrNull(1) else state.pills.getOrNull(2))?.key
                            unlift()
                            if (other != null && host.onSwap(key, other)) {
                                subtleTick()
                                releaseDrag(animated = true)
                            } else {
                                settle()
                            }
                        }
                        gesture.mode == NONE && gesture.heldMs < longPressMs -> {
                            unlift()
                            host.onTapPill(key)
                        }
                        else -> settle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> settle()
            }
        }

        private fun onBubbleTouch(ev: MotionEvent) {
            if (ev.actionMasked == MotionEvent.ACTION_UP &&
                gesture.mode == NONE &&
                gesture.heldMs < longPressMs
            ) {
                host.onTapPill(key)
            }
        }

        private fun onCardTouch(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {}
                MotionEvent.ACTION_MOVE ->
                    when (gesture.mode) {
                        SIDEWAYS -> {
                            dragX = rubber(gesture.dx, dp(16).toFloat())
                            apply()
                        }
                        DOWN -> {
                            dragY = rubber(gesture.dy, dp(20).toFloat())
                            apply()
                        }
                        UP -> {
                            dragY = rubber(gesture.dy, dp(24).toFloat())
                            apply()
                        }
                    }
                MotionEvent.ACTION_UP ->
                    when {
                        gesture.mode == NONE ->
                            // A tap where there is no button opens the app; a long press only glows.
                            if (gesture.heldMs < longPressMs) {
                                subtleTick()
                                host.onOpenApp(key)
                            }
                        gesture.mode == UP && (gesture.dy < -dp(40) || gesture.vy < -flingVelocity) -> {
                            releaseDrag(animated = true)
                            host.onCollapseAll()
                        }
                        else -> settle()
                    }
                MotionEvent.ACTION_CANCEL -> settle()
            }
        }

        private fun unlift() {
            animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(decelerate).start()
        }

        /**
         * Hands the drag over to the shape's own spring: the finger offset becomes its position and
         * (part of) the finger's speed its velocity, so the next move starts exactly here, already
         * moving the way the finger was.
         */
        private fun releaseDrag(animated: Boolean) {
            pos[0] += dragX
            pos[1] += dragY
            vel[0] += (gesture.vx * RELEASE_VELOCITY).coerceIn(-flingVelocity, flingVelocity)
            vel[1] += (gesture.vy * RELEASE_VELOCITY).coerceIn(-flingVelocity, flingVelocity)
            geo = geo.copy(x = pos[0], y = pos[1])
            dragX = 0f
            dragY = 0f
            if (!animated) apply()
        }

        /** Back in place with a gentle bounce. */
        private fun settle() {
            unlift()
            if (abs(dragX) > dp(4) || abs(dragY) > dp(4)) subtleTick()
            if (dragX == 0f && dragY == 0f) return
            releaseDrag(animated = true)
            stiffness = SNAP_STIFFNESS
            damping = SNAP_DAMPING
            startSpring()
        }

        private fun animateGlow(target: Float) {
            if (!config.touchGlow) return
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

        fun sendAction(action: IslandAction) {
            subtleTick()
            host.onAction(key, action)
        }

        fun openApp() = host.onOpenApp(key)
    }

    private fun lighten(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + 28f) % 360f
        return Color.HSVToColor(hsv)
    }

    /** The island's own glyph for an activity, or null when it shows the app's icon. */
    /** The island's own icon for a common button label (Pause, Resume, Lap, Reset), or null. */
    private fun ownGlyph(label: CharSequence): Glyphs.Glyph? {
        val l = label.toString().lowercase()
        return when {
            "pause" in l -> Glyphs.Glyph.PAUSE
            "resume" in l || "start" in l || l == "play" || "continue" in l -> Glyphs.Glyph.PLAY
            "lap" in l -> Glyphs.Glyph.LAP
            "reset" in l -> Glyphs.Glyph.RESET
            "stop" in l || "dismiss" in l -> Glyphs.Glyph.STOP
            else -> null
        }
    }

    private fun isAddMinute(label: CharSequence): Boolean {
        val l = label.toString().lowercase().replace(" ", "")
        return l.startsWith("+1") || l.startsWith("add1") || "1min" in l
    }

    private fun glyphFor(a: IslandActivity): Glyphs.Glyph? =
        when (a.symbol) {
            IslandSymbol.RECORD -> Glyphs.Glyph.RECORD
            IslandSymbol.BOLT -> Glyphs.Glyph.BOLT
            IslandSymbol.EARBUDS -> Glyphs.Glyph.EARBUDS
            IslandSymbol.BELL -> Glyphs.Glyph.BELL
            IslandSymbol.VIBRATE -> Glyphs.Glyph.VIBRATE
            IslandSymbol.SILENT -> Glyphs.Glyph.SILENT
            IslandSymbol.BATTERY_LOW -> Glyphs.Glyph.BATTERY_LOW
            null ->
                when (a.kind) {
                    IslandKind.CALL -> Glyphs.Glyph.PHONE
                    IslandKind.TIMER -> Glyphs.Glyph.HOURGLASS
                    IslandKind.STOPWATCH -> Glyphs.Glyph.STOPWATCH
                    IslandKind.MEDIA -> Glyphs.Glyph.NOTE
                    IslandKind.SCREEN_RECORD -> Glyphs.Glyph.RECORD
                    IslandKind.LIVE,
                    IslandKind.PROGRESS,
                    IslandKind.SYSTEM -> if (a.icon != null) null else Glyphs.Glyph.DOT
                }
        }

    // Glow behind a card ---------------------------------------------------------------------------

    /** Coloured glow behind a card. Drawn once (blurred), then only moved and faded. */
    private inner class AuraView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var c1 = 0
        private var c2 = 0
        var cardHeight = 0f
            set(value) {
                // Redraw only when the card height really changes (not every frame of a move).
                if (abs(field - value) > dp(24)) {
                    field = value
                    invalidate()
                }
            }

        init {
            setRenderEffect(RenderEffect.createBlurEffect(dp(30).toFloat(), dp(30).toFloat(), Shader.TileMode.DECAL))
            alpha = 0f
        }

        fun colors(a: Int, b: Int) {
            if (a == c1 && b == c2) return
            c1 = a
            c2 = b
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val m = auraMargin.toFloat()
            val w = width - m * 2
            val h = cardHeight.coerceIn(dp(68).toFloat(), height - m * 2)
            paint.shader = RadialGradient(m + w * 0.25f, m + h * 0.35f, w * 0.42f,
                (c1 and 0x00FFFFFF) or 0x8C000000.toInt(), 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(m + w * 0.25f, m + h * 0.35f, w * 0.42f, paint)
            paint.shader = RadialGradient(m + w * 0.75f, m + h * 0.65f, w * 0.42f,
                (c2 and 0x00FFFFFF) or 0x73000000, 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(m + w * 0.75f, m + h * 0.65f, w * 0.42f, paint)
        }
    }

    // Card contents --------------------------------------------------------------------------------

    private interface CardContent {
        val view: View

        fun bind(a: IslandActivity, stacked: Boolean)

        /** Live updates while shown; true while it needs more ticks. */
        fun tick(shown: Boolean): Boolean
    }

    /** Compact card (call, timer, stopwatch): icon, big live value, label, buttons. */
    private inner class CompactContent(context: Context, private val item: Item) : CardContent {
        private val row = LinearLayout(context)
        private val frame = FrameLayout(context)
        override val view: View = frame
        /** The app's icon (live updates, downloads), tinted with its colour. */
        private val appIcon = ImageView(context)
        /** A thin progress line along the bottom of the card. */
        private val progressLine = ThinProgress(context)
        private val lead = FrameLayout(context)
        private val glyph = GlyphView(context)
        private val avatar = ImageView(context)
        private val initial = text(17f, Color.WHITE, 600)
        private val big = text(30f, Color.WHITE, 600)
        private val label = text(13f, 0x99FFFFFF.toInt(), 400)
        private val buttons = LinearLayout(context)
        private var activity: IslandActivity? = null
        private var actionsKey: String? = null
        private var lastBig = ""
        /** "Time's up": the icon blinks softly. */
        private val blink =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    val t = it.animatedValue as Float
                    glyph.alpha = 1f - 0.55f * (0.5f - 0.5f * cos(t * 2f * PI).toFloat())
                }
            }

        init {
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(20), 0, dp(16), 0)
            row.minimumHeight = compactH
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
            avatar.background = circle(0xFF475569.toInt())
            avatar.clipToOutline = true
            initial.gravity = Gravity.CENTER
            lead.addView(glyph, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            lead.addView(appIcon, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            lead.addView(avatar, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            lead.addView(initial, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            row.addView(lead, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            big.includeFontPadding = false
            texts.addView(big)
            texts.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
                marginEnd = dp(10)
            })
            buttons.orientation = LinearLayout.HORIZONTAL
            buttons.gravity = Gravity.CENTER_VERTICAL
            row.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, compactH))
            frame.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            frame.addView(progressLine, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(14), Gravity.BOTTOM).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
                bottomMargin = dp(4)
            })
            row.addOnAttachStateChangeListener(
                object : OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        if (activity?.alert == true) blink.start()
                    }

                    override fun onViewDetachedFromWindow(v: View) = blink.cancel()
                }
            )
        }

        override fun bind(a: IslandActivity, stacked: Boolean) {
            activity = a
            val isCall = a.kind == IslandKind.CALL
            val generic = a.kind == IslandKind.LIVE || a.kind == IslandKind.PROGRESS
            val photo = if (isCall || generic) a.art else null
            val g = glyphFor(a)
            glyph.visibility = if (!isCall && photo == null && g != null) VISIBLE else GONE
            if (g != null) glyph.set(g, a.accent)
            appIcon.visibility = if (!isCall && photo == null && g == null && a.icon != null) VISIBLE else GONE
            appIcon.setImageDrawable(a.icon)
            appIcon.imageTintList = ColorStateList.valueOf(a.accent)
            avatar.visibility = if (photo != null) VISIBLE else GONE
            avatar.setImageBitmap(photo)
            initial.visibility = if (isCall && a.art == null) VISIBLE else GONE
            initial.background = circle(0xFF475569.toInt())
            initial.text = a.title.firstOrNull()?.uppercase() ?: ""
            val paused = a.clock?.running == false
            when {
                isCall -> {
                    big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    big.setTextColor(Color.WHITE)
                    big.text = a.title
                    label.setTextColor(a.accent)
                }
                // A live update or download without a short value: its title, like a call.
                generic && a.value == null && a.clock == null -> {
                    big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    big.setTextColor(Color.WHITE)
                    big.text = a.title
                    label.setTextColor(0x99FFFFFF.toInt())
                    label.text = a.subtitle
                }
                else -> {
                    val long = (a.value?.length ?: 0) > 7
                    big.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (long) 20f else 30f)
                    val color = if (a.kind == IslandKind.STOPWATCH) Color.WHITE else a.accent
                    // A paused time dims.
                    big.setTextColor(if (paused) (color and 0x00FFFFFF) or 0xB3000000.toInt() else color)
                    label.setTextColor(0x99FFFFFF.toInt())
                    label.text =
                        when {
                            paused && !a.alert -> "${a.title} · Paused"
                            generic && a.subtitle != null -> "${a.title} · ${a.subtitle}"
                            a.kind == IslandKind.SCREEN_RECORD -> a.subtitle
                            else -> a.title
                        }
                }
            }
            val p = a.progress
            progressLine.visibility = if (p != null) VISIBLE else GONE
            if (p != null) progressLine.set(p, a.accent)
            if (a.alert) {
                if (!blink.isStarted && row.isAttachedToWindow) blink.start()
            } else {
                blink.cancel()
                glyph.alpha = 1f
            }
            lastBig = ""
            update()
            bindButtons(a)
        }

        private fun bindButtons(a: IslandActivity) {
            val k = a.actions.joinToString { "${it.label}|${it.style}|${it.icon != null}" } + "|${a.accent}|${a.kind}"
            if (k == actionsKey) {
                // Same buttons: just point them at the new intents.
                a.actions.forEachIndexed { i, action ->
                    buttons.getChildAt(i)?.setOnClickListener { item.sendAction(action) }
                }
                return
            }
            actionsKey = k
            buttons.removeAllViews()
            a.actions.forEachIndexed { i, action ->
                val b = actionButton(action, a)
                b.setOnClickListener { item.sendAction(action) }
                // Round buttons stay round: a fixed square, never stretched to their content.
                val w = if (b is ImageButton) dp(46) else LinearLayout.LayoutParams.WRAP_CONTENT
                buttons.addView(b, LinearLayout.LayoutParams(w, dp(46)).apply {
                    if (i > 0) marginStart = dp(10)
                })
            }
        }

        private fun actionButton(action: IslandAction, a: IslandActivity): View {
            val accent = a.accent
            val bg =
                when (action.style) {
                    IslandAction.Style.PRIMARY -> accent
                    IslandAction.Style.DANGER -> 0xFFEF4444.toInt()
                    IslandAction.Style.NEUTRAL -> 0x29FFFFFF
                }
            val fg = if (action.style == IslandAction.Style.PRIMARY && isLight(accent)) 0xFF111111.toInt() else Color.WHITE
            val answer = a.kind == IslandKind.CALL && action.style == IslandAction.Style.PRIMARY
            // Timer, stopwatch and other everyday buttons get the island's own icons, so they all
            // look alike whatever icons the app ships ("+1:00" stays as text).
            val own = if (a.kind == IslandKind.CALL) null else ownGlyph(action.label)
            val plusMinute = a.kind != IslandKind.CALL && isAddMinute(action.label)
            if (plusMinute) {
                return TextView(context).apply {
                    text = "+1:00"
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    minWidth = dp(46)
                    setPadding(dp(14), 0, dp(14), 0)
                    background = roundRect(bg, dp(23).toFloat())
                    isClickable = true
                    contentDescription = action.label
                }
            }
            if (own != null && action.style != IslandAction.Style.DANGER) {
                return ImageButton(context).apply {
                    background = circle(bg)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val pad = dp(if (own == Glyphs.Glyph.LAP || own == Glyphs.Glyph.RESET) 12 else 13)
                    setPadding(pad, pad, pad, pad)
                    setImageDrawable(GlyphDrawable(own, fg))
                    contentDescription = action.label
                }
            }
            return if (action.style == IslandAction.Style.DANGER || action.icon != null || answer) {
                ImageButton(context).apply {
                    background = circle(if (answer) 0xFF22C55E.toInt() else bg)
                    minimumWidth = dp(46)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (answer) {
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setImageDrawable(GlyphDrawable(Glyphs.Glyph.PHONE, Color.WHITE))
                    } else if (action.style == IslandAction.Style.DANGER) {
                        // Our own shapes: hang up for calls, a stop square for the rest.
                        val g = if (a.kind == IslandKind.CALL) Glyphs.Glyph.END_CALL else Glyphs.Glyph.STOP
                        val pad = dp(if (g == Glyphs.Glyph.STOP) 13 else 11)
                        setPadding(pad, pad, pad, pad)
                        setImageDrawable(GlyphDrawable(g, Color.WHITE))
                    } else {
                        setPadding(dp(13), dp(13), dp(13), dp(13))
                        setImageDrawable(action.icon?.mutate())
                        imageTintList = ColorStateList.valueOf(fg)
                    }
                    contentDescription = action.label
                }
            } else {
                TextView(context).apply {
                    text = action.label
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    minWidth = dp(46)
                    setPadding(dp(14), 0, dp(14), 0)
                    background = roundRect(bg, dp(23).toFloat())
                    isClickable = true
                }
            }
        }

        private fun update() {
            val a = activity ?: return
            val clock = a.clock
            val value =
                when {
                    clock != null -> IslandClock.format(clock.millis(), a.kind == IslandKind.STOPWATCH)
                    a.value != null -> a.value.toString()
                    a.kind == IslandKind.LIVE || a.kind == IslandKind.PROGRESS -> return
                    else -> a.subtitle?.toString().orEmpty()
                }
            if (a.kind == IslandKind.CALL) {
                if (value != lastBig) {
                    lastBig = value
                    label.text = value
                }
            } else if (value != lastBig) {
                lastBig = value
                big.text = value
            }
        }

        override fun tick(shown: Boolean): Boolean {
            val running = activity?.clock?.running == true
            if (shown && running) update()
            return false
        }
    }

    /** A thin rounded progress line (downloads, transfers, live updates). */
    private inner class ThinProgress(context: Context) : View(context) {
        private var fraction = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x29FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        fun set(f: Float, color: Int) {
            fraction = f.coerceIn(0f, 1f)
            fill.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val h = dp(3).toFloat()
            val y = height - h - dp(4)
            r.set(0f, y, width.toFloat(), y + h)
            canvas.drawRoundRect(r, h / 2f, h / 2f, track)
            r.right = width * fraction
            if (r.right > h) canvas.drawRoundRect(r, h / 2f, h / 2f, fill)
        }
    }

    /** The full media card: header, one-line progress, controls, and (when alone) recently played. */
    private inner class MediaContent(context: Context, private val item: Item) : CardContent {
        private val column = LinearLayout(context)
        override val view: View = column
        private val art = roundedImage(dp(50), dp(12).toFloat())
        private val title = text(17f, Color.WHITE, 600)
        private val subtitle = text(14f, 0x9EFFFFFF.toInt(), 400)
        private val appBadge = ImageView(context)
        private val progress = ProgressLine(context)
        private val elapsed = text(12f, 0xA8FFFFFF.toInt(), 400)
        private val total = text(12f, 0xA8FFFFFF.toInt(), 400)
        private val outputButton = controlButton()
        private val prevButton = controlButton()
        private val playButton = controlButton()
        private val nextButton = controlButton()
        private val customButton = controlButton()
        private val recentRow = LinearLayout(context)
        private val playIcon = PlayPauseDrawable()
        private val prevIcon = SkipDrawable(forward = false)
        private val nextIcon = SkipDrawable(forward = true)
        private var data: MediaData? = null
        private var controller: MediaController? = null
        private var appIconKey: String? = null
        private var durationMs = 0L
        private var lastProgressUpdate = 0L

        init {
            column.orientation = LinearLayout.VERTICAL
            column.setPadding(dp(20), dp(18), dp(20), dp(16))

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(art, LinearLayout.LayoutParams(dp(50), dp(50)))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(title)
            texts.addView(subtitle)
            header.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
                marginEnd = dp(8)
            })
            appBadge.background = circle(Color.WHITE)
            appBadge.setPadding(dp(6), dp(6), dp(6), dp(6))
            appBadge.imageTintList = ColorStateList.valueOf(0xFF111111.toInt())
            header.addView(appBadge, LinearLayout.LayoutParams(dp(28), dp(28)))
            column.addView(header)

            // One line: elapsed, the line (drag to seek), total.
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(elapsed, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
            line.addView(progress, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
            total.gravity = Gravity.END
            line.addView(total, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
            column.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })

            val controls = LinearLayout(context).apply {
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
            slots.forEachIndexed { i, b ->
                val s = if (b === playButton) dp(52) else dp(44)
                controls.addView(b, LinearLayout.LayoutParams(s, s))
                if (i < slots.size - 1) controls.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            column.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })

            recentRow.orientation = LinearLayout.HORIZONTAL
            column.addView(recentRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            })
            recentRow.visibility = GONE
        }

        override fun bind(a: IslandActivity, stacked: Boolean) {
            val d = a.media ?: return
            val changedSession = data?.instanceId != d.instanceId
            data = d
            art.setImageBitmap(a.art)
            title.text = d.song ?: ""
            subtitle.text = d.artist ?: d.app ?: ""
            val iconKey = d.packageName
            if (iconKey != appIconKey) {
                appIconKey = iconKey
                appBadge.setImageIcon(d.appIcon)
            }
            val actions = d.semanticActions
            bindControl(prevButton, prevIcon, actions?.prevOrCustom, "Previous")
            bindControl(nextButton, nextIcon, actions?.nextOrCustom, "Next")
            val playPause = actions?.playOrPause
            playIcon.setPlaying(d.isPlaying == true, animate = playButton.isAttachedToWindow)
            playButton.contentDescription = playPause?.contentDescription ?: "Play or pause"
            playButton.isEnabled = playPause?.action != null
            playButton.setOnClickListener {
                val run = playPause?.action ?: return@setOnClickListener
                run.run()
                playIcon.setPlaying(!playIcon.playing, animate = true)
            }
            val custom = actions?.custom0
            customButton.setImageDrawable(custom?.icon)
            customButton.contentDescription = custom?.contentDescription ?: "App action"
            customButton.setOnClickListener { custom?.action?.run() }
            customButton.visibility = if (custom?.icon != null && custom.action != null) VISIBLE else INVISIBLE
            if (changedSession) controller = null
            // Recently played only when the music card is the only card (keeps the stack short).
            bindRecents(if (stacked) emptyList() else a.recents)
            updateProgress()
        }

        private var recentsKey: String? = null

        private fun bindRecents(recents: List<MediaData>) {
            val k = recents.joinToString { "${it.packageName}|${it.song}" }
            if (k == recentsKey) return
            recentsKey = k
            recentRow.removeAllViews()
            if (recents.isEmpty()) {
                recentRow.visibility = GONE
                return
            }
            recentRow.visibility = VISIBLE
            val size = (cardWidth() - dp(40) - dp(10) * 3) / 4
            for (i in 0 until 4) {
                val r = recents.getOrNull(i)
                val tile = roundedImage(size, dp(14).toFloat())
                if (r != null) {
                    tile.setImageIcon(r.artwork)
                    tile.contentDescription = "Play ${r.song ?: r.app ?: ""}"
                    // Plays that song right here: it becomes the music card, and the one playing
                    // now drops into this row. The card stays open.
                    tile.setOnClickListener { host.onPlayRecent(r) }
                } else {
                    tile.visibility = INVISIBLE
                }
                recentRow.addView(tile, LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = dp(10) })
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
            host.onCollapseAll()
        }

        override fun tick(shown: Boolean): Boolean {
            if (!shown) return false
            val now = SystemClock.uptimeMillis()
            if (now - lastProgressUpdate >= PROGRESS_INTERVAL_MS) {
                lastProgressUpdate = now
                updateProgress()
            }
            return true
        }

        private fun updateProgress() {
            val d = data ?: return
            val c = controller ?: d.token?.let { MediaController(context, it) }.also { controller = it }
            val state = c?.playbackState
            val duration = c?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
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

        private fun roundedImage(size: Int, radius: Float) =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundRect(0x33FFFFFF, radius)
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

        /** Thin line with a glowing played part and a glowing tip. Drag it to seek. */
        private inner class ProgressLine(context: Context) : View(context) {
            var fraction = 0f
                set(value) {
                    field = value
                    invalidate()
                }
            var seekable = false
            var dragging = false
                private set
            private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
            private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
                        controller?.transportControls?.seekTo((f * durationMs).toLong())
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
                val h = dp(if (dragging) 6 else 3).toFloat()
                val top = (height - h) / 2f
                val r = h / 2f
                rect.set(0f, top, width.toFloat(), top + h)
                canvas.drawRoundRect(rect, r, r, trackPaint)
                val x = width * fraction.coerceIn(0f, 1f)
                rect.set(0f, top, x, top + h)
                if (rect.width() > 0f) canvas.drawRoundRect(rect, r, r, playedPaint)
                // Glowing tip.
                val tip = dp(if (dragging) 7 else 4).toFloat()
                tipPaint.shader = RadialGradient(x, height / 2f, tip * 2.4f,
                    intArrayOf(0xCCFFFFFF.toInt(), 0x33FFFFFF, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(x, height / 2f, tip * 2.4f, tipPaint)
            }
        }
    }

    // Icons --------------------------------------------------------------------------------------

    /** Simple glyphs drawn from 24-unit path data, so they scale and tint cleanly. */
    private object Glyphs {
        enum class Glyph(val stroke: Boolean, val data: String) {
            PHONE(false, "M6.6 3.5 9 6.9a1.6 1.6 0 0 1-.2 2l-1.2 1.2a12 12 0 0 0 6.3 6.3l1.2-1.2a1.6 1.6 0 0 1 2-.2l3.4 2.4a1.6 1.6 0 0 1 .3 2.3l-1.2 1.5c-.8 1-2.1 1.3-3.3.9A19 19 0 0 1 3.1 7.7c-.4-1.2 0-2.5.9-3.3l1.5-1.2a1.6 1.6 0 0 1 2.3.3z"),
            END_CALL(false, "M3 14.5c5-4.6 13-4.6 18 0l-1.8 2.3a1.4 1.4 0 0 1-1.9.3l-2.4-1.6a1.4 1.4 0 0 1-.6-1.2v-1.6a12 12 0 0 0-4.6 0v1.6a1.4 1.4 0 0 1-.6 1.2l-2.4 1.6a1.4 1.4 0 0 1-1.9-.3z"),
            HOURGLASS(true, "M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"),
            STOPWATCH(true, "M12 5a8 8 0 1 1 0 16a8 8 0 1 1 0-16zM12 13V9M10 2.5h4M18.5 6l1.5-1.5"),
            STOP(false, "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"),
            RECORD(false, "M12 6.5a5.5 5.5 0 1 1 0 11a5.5 5.5 0 1 1 0-11z"),
            BOLT(false, "M13 2 4.5 13.5H11l-1 8.5L19.5 10H13z"),
            EARBUDS(true, "M8 4a3 3 0 0 0-3 3v2a3 3 0 0 0 3 3v8M16 4a3 3 0 0 1 3 3v2a3 3 0 0 1-3 3v8"),
            BELL(true, "M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4"),
            VIBRATE(true, "M8 4h8v16H8zM4 8v8M20 8v8"),
            SILENT(true, "M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4M3 3l18 18"),
            BATTERY_LOW(true, "M4 8h14v8H4zM20 11v2M7 11v2"),
            DOT(false, "M12 8a4 4 0 1 1 0 8a4 4 0 1 1 0-8z"),
            NOTE(false, "M9 17.5V6l10-2v11.5a2.6 2.6 0 1 1-1.8-2.5V7.6L10.8 8.9v8.6a2.6 2.6 0 1 1-1.8-2.5z"),
            PAUSE(false, "M7.5 5h3a.5.5 0 0 1 .5.5v13a.5.5 0 0 1-.5.5h-3a.5.5 0 0 1-.5-.5v-13a.5.5 0 0 1 .5-.5zM13.5 5h3a.5.5 0 0 1 .5.5v13a.5.5 0 0 1-.5.5h-3a.5.5 0 0 1-.5-.5v-13a.5.5 0 0 1 .5-.5z"),
            PLAY(false, "M8 5.6v12.8a1 1 0 0 0 1.5.86l10.2-6.4a1 1 0 0 0 0-1.72L9.5 4.74A1 1 0 0 0 8 5.6z"),
            LAP(true, "M6 21V4M6 4h11l-2.5 4L17 12H6"),
            RESET(true, "M5 12a7 7 0 1 0 2.05-4.95M5 4.5V8h3.5"),
        }

        private val paths = HashMap<Glyph, Path>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val matrix = Matrix()
        private val scaled = Path()

        fun draw(canvas: Canvas, glyph: Glyph, cx: Float, cy: Float, size: Float, color: Int, alpha: Int) {
            val src = paths.getOrPut(glyph) { PathParser.createPathFromPathData(glyph.data) }
            val s = size / 24f
            matrix.setScale(s, s)
            matrix.postTranslate(cx - size / 2f, cy - size / 2f)
            scaled.reset()
            src.transform(matrix, scaled)
            paint.color = color
            paint.alpha = alpha
            if (glyph.stroke) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.2f * s
            } else {
                paint.style = Paint.Style.FILL
            }
            canvas.drawPath(scaled, paint)
        }
    }

    private inner class GlyphView(context: Context) : View(context) {
        private var glyph = Glyphs.Glyph.NOTE
        private var color = Color.WHITE

        fun set(g: Glyphs.Glyph, c: Int) {
            if (g == glyph && c == color) return
            glyph = g
            color = c
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            Glyphs.draw(canvas, glyph, width / 2f, height / 2f, min(width, height).toFloat(), color, 255)
        }
    }

    private inner class GlyphDrawable(private val glyph: Glyphs.Glyph, private val color: Int) : IconDrawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            Glyphs.draw(canvas, glyph, b.exactCenterX(), b.exactCenterY(), min(b.width(), b.height()).toFloat(), color, getAlpha())
        }
    }

    /** Base for the small drawn icons: no intrinsic size, so the button sizes them. */
    private abstract class IconDrawable : android.graphics.drawable.Drawable() {
        protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var alphaValue = 255

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
            invalidateSelf()
        }

        override fun getAlpha() = alphaValue

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Play (triangle) and pause (two bars), switching with a short fade and scale. */
    private inner class PlayPauseDrawable : IconDrawable() {
        var playing = false
            private set
        private var t = 0f
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
            if (t > 0f) {
                paint.alpha = (base * t).toInt()
                val k = 0.75f + 0.25f * t
                val bw = s * 0.32f * k
                val bh = s * 1.1f * k
                val gap = s * 0.22f * k
                canvas.drawRoundRect(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f, bw / 3f, bw / 3f, paint)
                canvas.drawRoundRect(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f, bw / 3f, bw / 3f, paint)
            }
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
            canvas.drawRoundRect(barX - s * 0.1f, cy - s * 0.75f, barX + s * 0.1f, cy + s * 0.75f, s * 0.1f, s * 0.1f, paint)
        }
    }

    /** Audio output: a phone and a speaker (as in the ColorOS media card). */
    private inner class OutputDrawable : IconDrawable() {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
        }
        private val r = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = min(b.width(), b.height()) / 24f
            val ox = b.exactCenterX() - 12f * s
            val oy = b.exactCenterY() - 12f * s
            stroke.alpha = getAlpha()
            paint.color = Color.WHITE
            paint.alpha = getAlpha()
            stroke.strokeWidth = 1.7f * s
            r.set(ox + 3f * s, oy + 3f * s, ox + 12f * s, oy + 19f * s)
            canvas.drawRoundRect(r, 2f * s, 2f * s, stroke)
            r.set(ox + 14.5f * s, oy + 7f * s, ox + 21f * s, oy + 20f * s)
            canvas.drawRoundRect(r, 1.6f * s, 1.6f * s, stroke)
            canvas.drawCircle(ox + 17.75f * s, oy + 15.5f * s, 1.5f * s, paint)
            canvas.drawCircle(ox + 17.75f * s, oy + 10.2f * s, 0.9f * s, paint)
        }
    }

    companion object {
        private const val NONE = 0
        private const val SIDEWAYS = 1
        private const val UP = 2
        private const val DOWN = 3
        private const val LIFT = 1.03f

        // Springs (stiffness in 1/s^2, damping ratio: 1 = no overshoot).
        /** Cards opening, closing and restacking: quick and calm, no overshoot. */
        private const val CARD_STIFFNESS = 340f
        private const val CARD_DAMPING = 1f
        /** Pills moving and swapping: about 1% overshoot. */
        private const val PILL_STIFFNESS = 400f
        private const val PILL_DAMPING = 0.82f
        /** Snap back after a drag that did not go far enough: one soft bounce. */
        private const val SNAP_STIFFNESS = 520f
        private const val SNAP_DAMPING = 0.7f
        /** Share of the finger's speed handed to the shape on release (drags are rubber-banded). */
        private const val RELEASE_VELOCITY = 0.25f
        private const val SPRING_SUBSTEP = 0.004f
        private const val ENTER_FADE_MS = 180L
        /** The island is gone once the shade is this far open. */
        private const val SHADE_HIDE_FRACTION = 0.35f
        private const val TICK_MS = 100L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val AUTO_COLLAPSE_MS = 8000L
    }
}
