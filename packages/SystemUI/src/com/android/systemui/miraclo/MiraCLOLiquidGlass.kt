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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * One place that knows whether the MiraCLO Liquid Glass look is on (the same switch the
 * Liquid Glass QS uses), so notifications and the volume panel follow it without each of
 * them watching the setting. Main thread only.
 */
object MiraCLOLiquidGlass {

    const val KEY = "miraclo_liquid_glass"

    @Volatile private var enabled = false
    private var appContext: Context? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val views: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val listeners = ArrayList<(Boolean) -> Unit>()

    /** Whether Liquid Glass is on. Starts watching the setting on first use. */
    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        ensureWatching(context)
        return enabled
    }

    /** Redraws [view] whenever the setting changes, until [detach] or the view is gone. */
    @JvmStatic
    fun attach(view: View) {
        ensureWatching(view.context)
        views.add(view)
    }

    @JvmStatic
    fun detach(view: View) {
        views.remove(view)
    }

    @JvmStatic
    fun addListener(context: Context, listener: (Boolean) -> Unit) {
        ensureWatching(context)
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun ensureWatching(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        try {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY), false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
                },
                UserHandle.USER_ALL,
            )
            app.registerReceiverAsUser(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) = refresh()
                },
                UserHandle.ALL, IntentFilter(Intent.ACTION_USER_SWITCHED), null, handler,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
            // Without the observer the value is still read once below.
        }
        enabled = read(app)
    }

    private fun read(context: Context): Boolean =
        try {
            Settings.Secure.getIntForUser(
                context.contentResolver, KEY, 0, ActivityManager.getCurrentUser()) != 0
        } catch (_: Exception) {
            false
        }

    private fun refresh() {
        val context = appContext ?: return
        val now = read(context)
        if (now == enabled) return
        enabled = now
        for (view in views.toList()) view.invalidate()
        for (listener in listeners.toList()) {
            try {
                listener(now)
            } catch (_: Exception) {
            }
        }
    }
}
