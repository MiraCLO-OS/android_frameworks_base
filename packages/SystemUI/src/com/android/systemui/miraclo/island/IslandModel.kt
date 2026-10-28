/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.android.systemui.media.controls.shared.model.MediaData

/** What an island activity is; decides its pill, its card and its colour. */
enum class IslandKind {
    MEDIA,
    CALL,
    TIMER,
    STOPWATCH,
}

/**
 * A live clock shown in a pill or card (call duration, timer, stopwatch).
 *
 * [base] is in [SystemClock.elapsedRealtime] time, like [android.widget.Chronometer]: a count-down
 * shows `base - now`, a count-up shows `now - base`. When not [running] the time is frozen at
 * [frozenMs].
 */
data class IslandClock(
    val base: Long,
    val countDown: Boolean,
    val running: Boolean,
    val frozenMs: Long,
) {
    fun millis(now: Long = SystemClock.elapsedRealtime()): Long =
        if (!running) frozenMs else if (countDown) base - now else now - base

    companion object {
        /** "4:59", "1:02:03"; with [tenths] "01:23.4" (stopwatch). */
        fun format(ms: Long, tenths: Boolean): String {
            val negative = ms < 0
            val abs = if (negative) -ms else ms
            val totalSec = abs / 1000
            val h = totalSec / 3600
            val m = (totalSec / 60) % 60
            val s = totalSec % 60
            val text =
                when {
                    h > 0 -> "%d:%02d:%02d".format(h, m, s)
                    tenths -> "%02d:%02d.%d".format(m, s, (abs / 100) % 10)
                    else -> "%02d:%02d".format(m, s)
                }
            return if (negative) "-$text" else text
        }
    }
}

/** A button on a compact card: the app's own notification action, or the call's hang-up. */
data class IslandAction(
    val label: CharSequence,
    val icon: Drawable?,
    val intent: PendingIntent,
    val style: Style,
) {
    enum class Style {
        /** Filled with the activity colour (pause, resume). */
        PRIMARY,
        /** Red (hang up). */
        DANGER,
        /** Glass grey (lap, +1 min). */
        NEUTRAL,
    }
}

/**
 * One live activity in the island. [key] is stable for the life of the activity; everything else
 * may change on each update.
 */
data class IslandActivity(
    val key: String,
    val kind: IslandKind,
    val packageName: String,
    /** Colour of the icon and the live value (green call, orange timer, ...). */
    val accent: Int,
    val title: CharSequence,
    val subtitle: CharSequence?,
    /** Album art (media) or the caller's photo. */
    val art: Bitmap?,
    val clock: IslandClock?,
    val actions: List<IslandAction>,
    val contentIntent: PendingIntent?,
    /** Media only: the full media session data for the music card. */
    val media: MediaData? = null,
    /** Media only: other recent players, resumable from the lone music card. */
    val recents: List<MediaData> = emptyList(),
    /**
     * Changes when the activity becomes "something new" (next song, a new call): a pill swiped
     * away comes back when this changes.
     */
    val signature: String = key,
) {
    val playing: Boolean
        get() = media?.isPlaying == true || clock?.running == true
}
