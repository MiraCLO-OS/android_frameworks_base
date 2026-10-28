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
            readCall(sbn) ?: if (isClockApp(sbn.packageName)) readClock(sbn) else null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read ${sbn.key}", e)
            null
        }

    /** Whether [read] can ever return something for this notification (cheap pre-check). */
    fun isCandidate(sbn: StatusBarNotification): Boolean =
        sbn.notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) != 0 ||
            isClockApp(sbn.packageName)

    // Calls ---------------------------------------------------------------------------------------

    private fun readCall(sbn: StatusBarNotification): IslandActivity? {
        val n = sbn.notification
        val extras = n.extras
        val type = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        // Stage 1: ongoing calls (and call screening). Incoming calls keep their usual heads-up.
        if (type != Notification.CallStyle.CALL_TYPE_ONGOING &&
            type != Notification.CallStyle.CALL_TYPE_SCREENING
        ) {
            return null
        }
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
            listOfNotNull(
                hangUp?.let { IslandAction("End call", null, it, IslandAction.Style.DANGER) }
            )

        return IslandActivity(
            key = sbn.key,
            kind = IslandKind.CALL,
            packageName = sbn.packageName,
            accent = CALL_GREEN,
            title = name,
            subtitle = if (clock == null) extras.getCharSequence(Notification.EXTRA_TEXT) else null,
            art = photo,
            clock = clock,
            actions = actions,
            contentIntent = n.contentIntent,
        )
    }

    // Clock: timer and stopwatch -------------------------------------------------------------------

    @Suppress("DEPRECATION") // contentView / bigContentView: Clock's own layout is the source.
    private fun readClock(sbn: StatusBarNotification): IslandActivity? {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val remote = n.contentView ?: n.bigContentView ?: return null
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
        val kind = if (countDown) IslandKind.TIMER else IslandKind.STOPWATCH

        return IslandActivity(
            key = sbn.key,
            kind = kind,
            packageName = sbn.packageName,
            accent = if (alert) ALERT_RED else if (kind == IslandKind.TIMER) TIMER_ORANGE else STOPWATCH_BLUE,
            title = if (alert) TIMES_UP else if (kind == IslandKind.TIMER) "Timer" else "Stopwatch",
            subtitle = null,
            art = null,
            clock = clock,
            actions = clockActions(n, alert),
            contentIntent = n.contentIntent,
            alert = alert,
        )
    }

    /** A Clock timer that went off, posted without a chronometer: "Time's up" plus its buttons. */
    private fun readFiredTimer(sbn: StatusBarNotification, root: View): IslandActivity? {
        val n = sbn.notification
        val texts = ArrayList<String>().also { collectTexts(root, it) }
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
                IslandAction(
                    label = a.title,
                    icon = a.getIcon()?.loadDrawable(context),
                    intent = a.actionIntent,
                    style = style,
                )
            }

    private fun collectTexts(view: View, out: MutableList<String>) {
        if (view is TextView) view.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTexts(view.getChildAt(i), out)
        }
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
        private const val AVATAR_PX = 128
        val CALL_GREEN = 0xFF4ADE80.toInt()
        val TIMER_ORANGE = 0xFFFB923C.toInt()
        val STOPWATCH_BLUE = 0xFF60A5FA.toInt()
        val ALERT_RED = 0xFFF87171.toInt()
        private const val TIMES_UP = "Time's up"
        private const val ALERT_SLACK_MS = 500L
        private val STOP_WORDS = listOf("stop", "dismiss")

        private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")

        private val startedField: Field? =
            try {
                Chronometer::class.java.getDeclaredField("mStarted").apply { isAccessible = true }
            } catch (e: ReflectiveOperationException) {
                null
            }
    }
}
