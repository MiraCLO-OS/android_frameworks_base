/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.os.Handler
import android.os.Looper

/**
 * Room the status bar keeps free in its middle for the Dynamic Island pills.
 *
 * The status bar already lays its two halves out around the camera cutout; [PhoneStatusBarView]
 * widens that gap to [width] while pills show, so the clock and battery stay and the icons that
 * would collide with a pill drop into the usual overflow dot instead of sitting under it.
 *
 * Growing applies at once (icons make room before the pill reaches them); shrinking waits until
 * the pill has finished shrinking, so icons never appear under it. Main thread only.
 */
object IslandStatusBarSpace {
    private const val SHRINK_DELAY_MS = 240L

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Runnable>()
    private var current = 0
    private var pending = 0
    private val shrink = Runnable { applyNow(pending) }

    /** Width in pixels to keep free around the camera, or 0. */
    @JvmStatic fun width(): Int = current

    @JvmStatic
    fun addListener(listener: Runnable) {
        if (listener !in listeners) listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    /** Asks for [px] of room (0 = none). */
    fun request(px: Int) {
        val w = px.coerceAtLeast(0)
        handler.removeCallbacks(shrink)
        if (w >= current) {
            applyNow(w)
        } else {
            pending = w
            handler.postDelayed(shrink, SHRINK_DELAY_MS)
        }
    }

    private fun applyNow(px: Int) {
        if (px == current) return
        current = px
        for (l in listeners.toList()) l.run()
    }
}
