/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.TextView
import java.lang.reflect.Field

/**
 * Turns notifications into island activities: ongoing calls (Android call style, which Google
 * Phone posts) and the Clock app's timer and stopwatch.
 *
 * Google Clock draws its timer and stopwatch in a custom layout, so the time is not in the
 * notification's fields. The layout is inflated here (as the notification shade does) and the
 * [Chronometer] inside it is read: its base and direction give a time that stays exact without
 * re-reading every second.
 *
 * [read] may be called on a background thread; it creates views but never attaches them.
 */
class IslandNotificationReader(private val context: Context) {

    fun read(sbn: StatusBarNotification): IslandActivity? =
        try {
            when (quickKind(sbn)) {
                IslandKind.CALL -> readCall(sbn)
                IslandKind.TIMER -> readClock(sbn)
                IslandKind.LIVE,
                IslandKind.PROGRESS -> readGeneric(sbn)
                else -> null
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read ${sbn.key}", e)
            null
        }

    /** Whether [read] can ever return something for this notification (cheap pre-check). */
    fun isCandidate(sbn: StatusBarNotification): Boolean = quickKind(sbn) != null

    /**
     * What kind of activity a notification would become, decided cheaply on the calling thread
     * (no layout inflation): CALL, TIMER (any Clock timer or stopwatch), LIVE, PROGRESS or null.
     * Used before reading, and to decide whether its heads-up should give way to the island.
     */
    fun quickKind(sbn: StatusBarNotification): IslandKind? {
        val n = sbn.notification
        val extras = n.extras
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        if (callType == Notification.CallStyle.CALL_TYPE_INCOMING ||
            callType == Notification.CallStyle.CALL_TYPE_ONGOING ||
            callType == Notification.CallStyle.CALL_TYPE_SCREENING
        ) {
            return IslandKind.CALL
        }
        if (isClockApp(sbn.packageName)) return IslandKind.TIMER
        // The system's own notifications (screen recorder, USB, ...) are handled elsewhere.
        if (sbn.packageName == "android" || sbn.packageName == SYSTEMUI) return null
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        if (n.isPromotedOngoing) return IslandKind.LIVE
        val ongoing =
            n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (!ongoing) return null
        if (progressOf(n) != null) return IslandKind.PROGRESS
        if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0) return IslandKind.PROGRESS
        return null
    }

    private fun progressOf(n: Notification): Float? {
        val extras = n.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) return null
        return (extras.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f)
    }

    // Calls ---------------------------------------------------------------------------------------

    private fun readCall(sbn: StatusBarNotification): IslandActivity? {
        val n = sbn.notification
        val extras = n.extras
        val type = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        val incoming = type == Notification.CallStyle.CALL_TYPE_INCOMING
        val person = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        val name =
            person?.name?.takeIf { it.isNotBlank() }
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                ?: "Call"
        val photo = person?.icon?.loadDrawable(context)?.let { toBitmap(it, AVATAR_PX) }

        val clock =
            if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0) {
                val elapsedNow = SystemClock.elapsedRealtime()
                val base = elapsedNow - (System.currentTimeMillis() - n.`when`)
                IslandClock(
                    base = base,
                    countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
                    running = true,
                    frozenMs = elapsedNow - base,
                )
            } else {
                null
            }

        val hangUp = extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
        val actions =
            if (incoming) {
                // Decline (red), then Answer (green), like the call screen.
                val decline = extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
                val answer = extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
                listOfNotNull(
                    (decline ?: hangUp)?.let { IslandAction("Decline", null, it, IslandAction.Style.DANGER) },
                    answer?.let { IslandAction("Answer", null, it, IslandAction.Style.PRIMARY) },
                )
            } else {
                listOfNotNull(
                    hangUp?.let { IslandAction("End call", null, it, IslandAction.Style.DANGER) }
                )
            }

        return IslandActivity(
            key = sbn.key,
            kind = IslandKind.CALL,
            packageName = sbn.packageName,
            accent = CALL_GREEN,
            title = name,
            subtitle =
                if (incoming) INCOMING
                else if (clock == null) extras.getCharSequence(Notification.EXTRA_TEXT) else null,
            art = photo,
            clock = if (incoming) null else clock,
            actions = actions,
            contentIntent = n.contentIntent,
            // An incoming call opens its card by itself; it becomes a normal call once answered.
            alert = incoming,
            signature = if (incoming) "${sbn.key}|incoming" else sbn.key,
        )
    }

    // Clock: timer and stopwatch -------------------------------------------------------------------

    @Suppress("DEPRECATION") // contentView / bigContentView: Clock's own layout is the source.
    private fun readClock(sbn: StatusBarNotification): IslandActivity? {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        // Newer Clock versions post the Android 17 metric style (no custom layout): read the time
        // straight from it.
        metricClock(n)?.let { (clock, label) -> return clockActivity(sbn, clock, label) }
        val remote = n.contentView ?: n.bigContentView ?: return readFiredTimer(sbn, null)
        val root = remote.apply(context, null)
        // Other Clock notifications (alarms) have no chronometer; a timer that went off may not
        // either, depending on the Clock version.
        val chrono = findChronometer(root) ?: return readFiredTimer(sbn, root)

        val countDown = chrono.isCountDown
        // A stopped chronometer shows the time at the moment the app posted it; read it at that
        // moment too, so a paused time is exact even if we read it much later.
        val postedAt =
            SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sbn.postTime).coerceAtLeast(0L)
        val ms = if (countDown) chrono.base - postedAt else postedAt - chrono.base
        // A count-down at (or past) zero is a timer that went off: "Time's up".
        val alert = countDown && ms <= ALERT_SLACK_MS
        val running = alert || (isStarted(chrono) ?: !n.sortKey.orEmpty().contains("PAUSED"))
        val clock = IslandClock(chrono.base, countDown, running, ms)
        return clockActivity(sbn, clock, null, alertOverride = alert)
    }

    /** A Clock timer or stopwatch activity for [clock]; [label] is the app's own name for it. */
    private fun clockActivity(
        sbn: StatusBarNotification,
        clock: IslandClock,
        label: CharSequence?,
        alertOverride: Boolean? = null,
    ): IslandActivity {
        val n = sbn.notification
        val countDown = clock.countDown
        // A count-down at (or past) zero is a timer that went off: "Time's up".
        val alert = alertOverride ?: (countDown && clock.millis() <= ALERT_SLACK_MS)
        val kind = if (countDown) IslandKind.TIMER else IslandKind.STOPWATCH
        val plain = if (kind == IslandKind.TIMER) "Timer" else "Stopwatch"
        return IslandActivity(
            key = sbn.key,
            kind = kind,
            packageName = sbn.packageName,
            accent = if (alert) ALERT_RED else if (kind == IslandKind.TIMER) TIMER_ORANGE else STOPWATCH_BLUE,
            title = if (alert) TIMES_UP else plain,
            // "70h timer": the app's own label, when it says more than "Timer".
            subtitle = label?.takeIf { it.isNotBlank() && !it.toString().equals(plain, ignoreCase = true) },
            art = null,
            clock = clock,
            actions = clockActions(n, alert),
            contentIntent = n.contentIntent,
            alert = alert,
        )
    }

    /** A Clock timer that went off, posted without a chronometer: "Time's up" plus its buttons. */
    private fun readFiredTimer(sbn: StatusBarNotification, root: View?): IslandActivity? {
        val n = sbn.notification
        val texts = ArrayList<String>().also { if (root != null) collectTexts(root, it) }
        n.extras.getCharSequence(Notification.EXTRA_TITLE)?.let { texts.add(it.toString()) }
        n.extras.getCharSequence(Notification.EXTRA_TEXT)?.let { texts.add(it.toString()) }
        val timesUp = texts.any { it.contains("time's up", ignoreCase = true) || it.contains("times up", ignoreCase = true) }
        val timerChannel = n.channelId.orEmpty().contains("timer", ignoreCase = true)
        val actions = clockActions(n, alert = true)
        if (!(timesUp || timerChannel) || actions.isEmpty()) return null
        return IslandActivity(
            key = sbn.key,
            kind = IslandKind.TIMER,
            packageName = sbn.packageName,
            accent = ALERT_RED,
            title = TIMES_UP,
            subtitle = TIMES_UP,
            art = null,
            clock = null,
            actions = actions,
            contentIntent = n.contentIntent,
            alert = true,
        )
    }

    /** The app's first two buttons. On "Time's up", Stop is the red one. */
    private fun clockActions(n: Notification, alert: Boolean): List<IslandAction> =
        n.actions
            .orEmpty()
            .filter { it.actionIntent != null && !it.title.isNullOrBlank() }
            .take(2)
            .mapIndexed { i, a ->
                val style =
                    when {
                        alert && STOP_WORDS.any { w -> a.title.toString().contains(w, ignoreCase = true) } ->
                            IslandAction.Style.DANGER
                        !alert && i == 0 -> IslandAction.Style.PRIMARY
                        else -> IslandAction.Style.NEUTRAL
                    }
                // The island draws its own icons for these (see the view), not Clock's.
                IslandAction(label = a.title, icon = null, intent = a.actionIntent, style = style)
            }

    private fun collectTexts(view: View, out: MutableList<String>) {
        if (view is TextView) view.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTexts(view.getChildAt(i), out)
        }
    }

    // Live updates, downloads, transfers, recorders --------------------------------------------------

    /**
     * Any app's live update (Android 16 promoted notification) or ongoing notification with a
     * progress bar or a running time. Uses only the notification's standard fields, so it works
     * for every app that follows the platform's notification styles.
     */
    private fun readGeneric(sbn: StatusBarNotification): IslandActivity? {
        val n = sbn.notification
        val extras = n.extras
        val kind = if (n.isPromotedOngoing) IslandKind.LIVE else IslandKind.PROGRESS
        val title =
            extras.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                ?: metricClock(n)?.second?.takeIf { it.isNotBlank() }
                ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        val progress = progressOf(n)
        val metric = metricClock(n)
        val clock =
            metric?.first
                ?: if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0) {
                    val now = SystemClock.elapsedRealtime()
                    val base = now - (System.currentTimeMillis() - n.`when`)
                    val down = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
                    IslandClock(base, down, true, if (down) base - now else now - base)
                } else {
                    null
                }
        val value =
            extras.getCharSequence(Notification.EXTRA_SHORT_CRITICAL_TEXT)?.takeIf { it.isNotBlank() }
                ?: progress?.let { "${(it * 100).toInt()}%" }
        val accent = accentOf(n)
        val large = n.getLargeIcon()?.loadDrawable(context)?.let { toBitmap(it, AVATAR_PX) }
        val small = n.smallIcon?.loadDrawable(context)?.mutate()
        val actions =
            n.actions
                .orEmpty()
                .filter { it.actionIntent != null && !it.title.isNullOrBlank() }
                .take(2)
                .map { a ->
                    val stop = STOP_WORDS.any { w -> a.title.toString().contains(w, ignoreCase = true) } ||
                        CANCEL_WORDS.any { w -> a.title.toString().contains(w, ignoreCase = true) }
                    IslandAction(
                        label = a.title,
                        icon = null,
                        intent = a.actionIntent,
                        style = if (stop) IslandAction.Style.NEUTRAL else IslandAction.Style.PRIMARY,
                    )
                }
        return IslandActivity(
            key = sbn.key,
            kind = kind,
            packageName = sbn.packageName,
            accent = accent,
            title = title,
            subtitle = text,
            art = large,
            clock = clock,
            actions = actions,
            contentIntent = n.contentIntent,
            icon = small,
            progress = progress,
            value = value,
            // A new title (next turn, new status) counts as something new.
            signature = "${sbn.key}|$title",
        )
    }

    /** The app's notification colour, made bright enough for the dark island. */
    private fun accentOf(n: Notification): Int {
        val c = n.color
        if (c == Notification.COLOR_DEFAULT || c == 0) return DEFAULT_ACCENT
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        hsv[2] = maxOf(hsv[2], 0.85f)
        if (hsv[1] < 0.15f) return DEFAULT_ACCENT
        return android.graphics.Color.HSVToColor(hsv)
    }

    /**
     * The time in an Android 17 metric style notification ([Notification.MetricStyle]): the
     * critical metric if it is a time difference (timer, stopwatch, ...), plus its label. Read
     * from the extras directly, as the platform stores them.
     */
    private fun metricClock(n: Notification): Pair<IslandClock, CharSequence?>? {
        val extras = n.extras
        val metrics = extras.getParcelableArrayList(EXTRA_METRICS, android.os.Bundle::class.java)
            ?: return null
        if (metrics.isEmpty()) return null
        val index = extras.getInt(EXTRA_METRICS_CRITICAL, 0).takeIf { it in metrics.indices } ?: 0
        val ordered = listOf(metrics[index]) + metrics.filterIndexed { i, _ -> i != index }
        for (metric in ordered) {
            val value = metric.getBundle("value") ?: continue
            val countDown = value.getBoolean("countDown")
            val now = SystemClock.elapsedRealtime()
            val clock =
                when {
                    value.containsKey("zeroElapsedRealtime") -> {
                        val base = value.getLong("zeroElapsedRealtime")
                        IslandClock(base, countDown, true, if (countDown) base - now else now - base)
                    }
                    value.containsKey("zeroTime") -> {
                        val base = now + (value.getLong("zeroTime") - System.currentTimeMillis())
                        IslandClock(base, countDown, true, if (countDown) base - now else now - base)
                    }
                    value.containsKey("pausedDuration") -> {
                        // Paused: the time stays still (remaining for a timer, elapsed otherwise).
                        val ms = value.getLong("pausedDuration")
                        IslandClock(if (countDown) now + ms else now - ms, countDown, false, ms)
                    }
                    else -> null
                } ?: continue
            return clock to metric.getCharSequence("label")
        }
        return null
    }

    private fun findChronometer(view: View): Chronometer? {
        if (view is Chronometer) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findChronometer(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Whether the app started the chronometer (running) or set it stopped (paused). */
    private fun isStarted(chrono: Chronometer): Boolean? =
        try {
            startedField?.getBoolean(chrono)
        } catch (e: ReflectiveOperationException) {
            null
        }

    private fun isClockApp(pkg: String) = pkg in CLOCK_PACKAGES || pkg.endsWith(".deskclock")

    private fun toBitmap(drawable: Drawable, size: Int): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    companion object {
        private const val TAG = "MiraCLOIsland"
        /** Notification.EXTRA_METRICS / EXTRA_METRICS_CRITICAL_INDEX (hidden constants). */
        private const val EXTRA_METRICS = "android.metrics"
        private const val EXTRA_METRICS_CRITICAL = "android.metrics.criticalIndex"
        private const val AVATAR_PX = 128
        val CALL_GREEN = 0xFF4ADE80.toInt()
        val TIMER_ORANGE = 0xFFFB923C.toInt()
        val STOPWATCH_BLUE = 0xFF60A5FA.toInt()
        val ALERT_RED = 0xFFF87171.toInt()
        private const val TIMES_UP = "Time's up"
        private const val ALERT_SLACK_MS = 500L
        private val STOP_WORDS = listOf("stop", "dismiss")
        private val CANCEL_WORDS = listOf("cancel", "end", "exit")
        private const val INCOMING = "Incoming call"
        private const val SYSTEMUI = "com.android.systemui"
        private val DEFAULT_ACCENT = 0xFF7DD3FC.toInt()

        private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")

        private val startedField: Field? =
            try {
                Chronometer::class.java.getDeclaredField("mStarted").apply { isAccessible = true }
            } catch (e: ReflectiveOperationException) {
                null
            }
    }
}
