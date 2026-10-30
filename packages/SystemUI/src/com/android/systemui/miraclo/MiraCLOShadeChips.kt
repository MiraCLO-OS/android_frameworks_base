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
 * MiraCLO: Settings > Display > Status bar chips. When off, the clock and status icons at the
 * top of the shade have no chip background behind them. Main thread only.
 */
object MiraCLOShadeChips {
    const val KEY = "miraclo_shade_header_chips"

    /** Whether the chip backgrounds are drawn (default on, as in Android 17). */
    var enabled by mutableStateOf(true)
        private set

    private var appContext: Context? = null

    @JvmStatic
    fun watch(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext ?: context
        appContext = app
        try {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
                },
                UserHandle.USER_ALL,
            )
        } catch (_: Exception) {}
        refresh()
    }

    private fun refresh() {
        val app = appContext ?: return
        enabled =
            try {
                Settings.Secure.getIntForUser(
                    app.contentResolver, KEY, 1, ActivityManager.getCurrentUser()) != 0
            } catch (_: Exception) {
                true
            }
    }
}
