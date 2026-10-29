/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.os.Handler
import android.os.Looper

/**
 * How much of the status bar the Dynamic Island pills need, read by [PhoneStatusBarView].
 *
 * While any pill shows, the status bar keeps only the clock on the left and the battery on the
 * right; with just the main pill there is also room for one network icon (Wi-Fi, else mobile).
 * Everything else (notification icons, chips, network speed, other status icons and the overflow
 * dot) fades out in place. When only cards are open the whole status bar comes back.
 *
 * A stricter mode applies at once (icons leave before a pill reaches them); a looser one waits
 * until the pills have finished shrinking, so icons never appear under a pill. Main thread only.
 */
object IslandStatusBarSpace {
    /** No pills: the normal status bar. */
    const val NONE = 0
    /** Only the main pill: clock | network icon + battery. */
    const val MAIN_PILL = 1
    /** Round pills beside the camera too: clock | battery. */
    const val SIDE_PILLS = 2

    private const val RELAX_DELAY_MS = 240L

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Runnable>()
    private var current = NONE
    private var pending = NONE
    private val relax = Runnable { applyNow(pending) }

    /** The current mode: [NONE], [MAIN_PILL] or [SIDE_PILLS]. */
    @JvmStatic fun mode(): Int = current

    @JvmStatic
    fun addListener(listener: Runnable) {
        if (listener !in listeners) listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    /** Asks for [mode]. */
    fun request(mode: Int) {
        handler.removeCallbacks(relax)
        if (mode >= current) {
            applyNow(mode)
        } else {
            pending = mode
            handler.postDelayed(relax, RELAX_DELAY_MS)
        }
    }

    private fun applyNow(mode: Int) {
        if (mode == current) return
        current = mode
        for (l in listeners.toList()) l.run()
    }
}
