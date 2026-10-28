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
        // Group summaries and other Clock notifications have no chronometer: ignored.
        val remote = n.contentView ?: n.bigContentView ?: return null
        val root = remote.apply(context, null)
        val chrono = findChronometer(root) ?: return null

        val countDown = chrono.isCountDown
        val running = isStarted(chrono) ?: !n.sortKey.orEmpty().contains("PAUSED")
        // A stopped chronometer shows the time at the moment the app posted it; read it at that
        // moment too, so a paused time is exact even if we read it much later.
        val postedAt =
            SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sbn.postTime).coerceAtLeast(0L)
        val ms = if (countDown) chrono.base - postedAt else postedAt - chrono.base
        val clock = IslandClock(chrono.base, countDown, running, ms)
        val kind = if (countDown) IslandKind.TIMER else IslandKind.STOPWATCH

        val actions =
            n.actions
                .orEmpty()
                .filter { it.actionIntent != null && !it.title.isNullOrBlank() }
                .take(2)
                .mapIndexed { i, a ->
                    IslandAction(
                        label = a.title,
                        icon = a.getIcon()?.loadDrawable(context),
                        intent = a.actionIntent,
                        style = if (i == 0) IslandAction.Style.PRIMARY else IslandAction.Style.NEUTRAL,
                    )
                }

        return IslandActivity(
            key = sbn.key,
            kind = kind,
            packageName = sbn.packageName,
            accent = if (kind == IslandKind.TIMER) TIMER_ORANGE else STOPWATCH_BLUE,
            title = if (kind == IslandKind.TIMER) "Timer" else "Stopwatch",
            subtitle = null,
            art = null,
            clock = clock,
            actions = actions,
            contentIntent = n.contentIntent,
        )
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

        private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")

        private val startedField: Field? =
            try {
                Chronometer::class.java.getDeclaredField("mStarted").apply { isAccessible = true }
            } catch (e: ReflectiveOperationException) {
                null
            }
    }
}
