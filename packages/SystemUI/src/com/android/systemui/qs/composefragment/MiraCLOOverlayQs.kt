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

package com.android.systemui.qs.composefragment

import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.dimensionResource
import com.android.systemui.res.R
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalUseCircularQsTileShapes
import com.android.compose.animation.scene.ContentScope
import com.android.compose.theme.PlatformTheme
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.miraclo.LocalMiraCLOGlassAlpha
import com.android.systemui.miraclo.LocalMiraCLOGlow
import com.android.systemui.miraclo.LocalMiraCLORefraction
import com.android.systemui.miraclo.MiraCLORefraction
import com.android.systemui.miraclo.miracloTouchGlow
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.panels.ui.compose.infinitegrid.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalMiraCLOLiquidGlass
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalMiraCLOQsStyle
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import javax.inject.Inject

/**
 * MiraCLO Quick Settings inside the Android 17 Dual Shade (the Quick Settings overlay).
 *
 * Shows the same ColorOS-style layout as the old shade (wide Wi-Fi / mobile data / Bluetooth
 * tiles, music card, tall brightness and volume sliders, ringer switch, round tiles) with the
 * same Liquid Glass, refraction and glow, all following the same Settings keys.
 */
@SysUISingleton
class MiraCLOOverlayQs
@Inject
constructor(
    @Application private val context: Context,
    private val audioVolumeInteractor: AudioVolumeInteractor,
    private val mediaDataManager: MediaDataManager,
    private val activityStarter: ActivityStarter,
    private val infiniteGridLayout: InfiniteGridLayout,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val mediaSessions = MiraCLOMediaSessions()
    private var watching = false

    /** ColorOS-style Quick Settings (Settings > Display > QS style). */
    var qsStyle by mutableStateOf(false)
        private set

    /** Liquid Glass on for Quick Settings. */
    var liquidGlass by mutableStateOf(false)
        private set
    private var refraction by mutableStateOf(false)
    private var glow by mutableStateOf(false)

    // Quick Settings options from the old shade (Settings > Display), kept for the Dual Shade.
    private var circularTiles by mutableStateOf(false)

    /** Settings.System.QS_SHOW_BRIGHTNESS: show the brightness slider in the stock layout. */
    var showBrightness by mutableStateOf(true)
        private set

    /** Settings.System.QS_BRIGHTNESS_POSITION_BOTTOM: brightness slider below the tiles. */
    var brightnessAtBottom by mutableStateOf(false)
        private set

    private fun readSystem(key: String, def: Int): Boolean =
        try {
            Settings.System.getIntForUser(
                context.contentResolver, key, def, ActivityManager.getCurrentUser()) != 0
        } catch (_: Exception) {
            def != 0
        }

    private fun read(key: String, def: Int): Boolean =
        try {
            Settings.Secure.getIntForUser(
                context.contentResolver, key, def, ActivityManager.getCurrentUser()) != 0
        } catch (_: Exception) {
            def != 0
        }

    private fun refresh() {
        qsStyle = read(KEY_QS_STYLE, 0)
        liquidGlass = read(KEY_LIQUID_GLASS, 0) && read(KEY_LIQUID_GLASS_QS, 1)
        refraction = liquidGlass && read(MiraCLORefraction.KEY, 0)
        glow = liquidGlass && read(MiraCLORefraction.KEY_GLOW, 0)
        circularTiles = readSystem(Settings.System.QS_TILES_CIRCULAR, 0)
        showBrightness = readSystem(Settings.System.QS_SHOW_BRIGHTNESS, 1)
        brightnessAtBottom = readSystem(Settings.System.QS_BRIGHTNESS_POSITION_BOTTOM, 0)
    }

    /** Starts following the settings; safe to call many times. */
    fun watch() {
        if (watching) return
        watching = true
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
            }
        for (key in
            listOf(
                KEY_QS_STYLE,
                KEY_LIQUID_GLASS,
                KEY_LIQUID_GLASS_QS,
                MiraCLORefraction.KEY,
                MiraCLORefraction.KEY_GLOW,
            )) {
            try {
                context.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL)
            } catch (_: Exception) {}
        }
        for (key in
            listOf(
                Settings.System.QS_TILES_CIRCULAR,
                Settings.System.QS_SHOW_BRIGHTNESS,
                Settings.System.QS_BRIGHTNESS_POSITION_BOTTOM,
            )) {
            try {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL)
            } catch (_: Exception) {}
        }
        refresh()
    }

    /** Provides the old shade's Quick Settings options (circular tiles) to [content]. */
    @Composable
    fun ProvideOptions(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalUseCircularQsTileShapes provides circularTiles,
            content = content,
        )
    }

    /**
     * The MiraCLO Quick Settings body, the same size as in the old shade. [glassAlpha] follows
     * the panel's open / close progress so the SurfaceFlinger glass fades with it, as in the
     * old shade.
     */
    @Composable
    fun ContentScope.Content(
        container: QuickSettingsContainerViewModel,
        glassAlpha: () -> Float,
        modifier: Modifier = Modifier,
    ) {
        DisposableEffect(Unit) {
            mediaDataManager.addListener(mediaSessions)
            onDispose { mediaDataManager.removeListener(mediaSessions) }
        }
        // The old shade's width: screen minus the stock QS margin and ColorOS's extra 22dp on
        // each side, so every tile keeps exactly its old size.
        val width =
            LocalConfiguration.current.screenWidthDp.dp -
                (dimensionResource(R.dimen.qs_horizontal_margin) + 22.dp) * 2
        val body: @Composable () -> Unit = {
            CompositionLocalProvider(
                LocalMiraCLOQsStyle provides true,
                LocalMiraCLOLiquidGlass provides liquidGlass,
                LocalMiraCLORefraction provides refraction,
                LocalMiraCLOGlow provides glow,
                LocalMiraCLOGlassAlpha provides glassAlpha,
                LocalUseCircularQsTileShapes provides circularTiles,
            ) {
                Box(modifier = modifier.miracloTouchGlow(glow)) {
                    GridAnchor()
                    MiraCLOQuickSettingsLayout(
                        tiles = container.tileGridViewModel.tileViewModels,
                        gridLayout = container.tileGridViewModel.gridLayout,
                        infiniteGridLayout = infiniteGridLayout,
                        brightnessViewModel = container.brightnessSliderViewModel,
                        audioVolumeInteractor = audioVolumeInteractor,
                        showMedia = mediaSessions.current != null,
                        media = { mediaModifier ->
                            mediaSessions.current?.let { data ->
                                MiraCLOMediaCard(
                                    data = data,
                                    onOpen = { intent ->
                                        activityStarter.postStartActivityDismissingKeyguard(intent)
                                    },
                                    modifier = mediaModifier,
                                )
                            }
                        },
                        listening = { true },
                        modifier = Modifier.requiredWidth(width),
                    )
                }
            }
        }
        // With refraction, Quick Settings is always the dark glass.
        if (refraction) PlatformTheme(isDarkTheme = true) { body() } else body()
    }

    companion object {
        const val KEY_QS_STYLE = "miraclo_qs_style"
        const val KEY_LIQUID_GLASS = "miraclo_liquid_glass"
        const val KEY_LIQUID_GLASS_QS = "miraclo_lg_qs"
    }
}
