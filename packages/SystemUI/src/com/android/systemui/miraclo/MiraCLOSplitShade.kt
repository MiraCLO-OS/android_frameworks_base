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

package com.android.systemui.miraclo

import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * MiraCLO split shade (Settings > Display > Split shade). Pulling the shade down from the
 * Quick Settings side of the top edge opens Quick Settings straight away; pulling from the
 * other side opens notifications only, with Quick Settings hidden until a pull from the Quick
 * Settings side switches to it. Quick Settings is on the right unless the sides are swapped.
 * Main thread only.
 */
object MiraCLOSplitShade {
    const val KEY = "miraclo_split_shade"
    const val KEY_SWAP = "miraclo_split_shade_swap"

    @Volatile private var enabled = false
    @Volatile private var swapped = false
    private var appContext: Context? = null

    /** True while the shade shows notifications only. Read by Quick Settings to hide itself. */
    var notificationsOnly by mutableStateOf(false)
        private set

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        ensureWatching(context)
        return enabled
    }

    /** Whether [x] (in a view [width] wide) is on the Quick Settings side. */
    @JvmStatic
    fun isQsSide(context: Context, x: Float, width: Int): Boolean {
        ensureWatching(context)
        val right = x >= width / 2f
        return if (swapped) !right else right
    }

    @JvmStatic
    fun isNotificationsOnly(): Boolean = notificationsOnly

    /** Called when a pull starts from the top edge with the shade closed. */
    @JvmStatic
    fun onPullStarted(context: Context, x: Float, width: Int) {
        notificationsOnly = isEnabled(context) && !isQsSide(context, x, width)
    }

    /** Called when the user switches to Quick Settings or the shade closes. */
    @JvmStatic
    fun showQuickSettings() {
        notificationsOnly = false
    }

    private fun ensureWatching(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        try {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
                }
            for (key in listOf(KEY, KEY_SWAP)) {
                app.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL)
            }
        } catch (_: Exception) {
            // Without the observer the values are still read once below.
        }
        refresh()
    }

    private fun refresh() {
        val context = appContext ?: return
        enabled = read(context, KEY)
        swapped = read(context, KEY_SWAP)
        if (!enabled) notificationsOnly = false
    }

    private fun read(context: Context, key: String): Boolean =
        try {
            Settings.Secure.getIntForUser(
                context.contentResolver, key, 0, ActivityManager.getCurrentUser()) != 0
        } catch (_: Exception) {
            false
        }
}
