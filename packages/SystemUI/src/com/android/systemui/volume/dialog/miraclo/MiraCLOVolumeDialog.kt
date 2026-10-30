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

package com.android.systemui.volume.dialog.miraclo

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.VibrationEffect
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.dialog.MediaOutputDialogManager
import com.android.systemui.miraclo.MiraCLOLiquidGlass
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.panel.domain.interactor.VolumePanelGlobalStateInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Window for [MiraCLOVolumePanelView], used in place of the stock volume dialog when the
 * MiraCLO volume panel is on. It follows the same visibility as the stock one: shown when
 * the volume dialog becomes visible, and dismissed (after its exit animation) when that
 * visibility becomes dismissed. Safety and loudness warnings are untouched.
 */
class MiraCLOVolumeDialog(
    context: Context,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
    private val stateInteractor: VolumeDialogStateInteractor,
    private val controller: VolumeDialogController,
    private val volumePanelGlobalStateInteractor: VolumePanelGlobalStateInteractor,
    private val mediaOutputDialogManager: MediaOutputDialogManager,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val audioManager: AudioManager,
) : Dialog(context, R.style.Theme_SystemUI_Dialog_Volume) {

    class Factory
    @Inject
    constructor(
        @Application private val context: Context,
        private val visibilityInteractor: VolumeDialogVisibilityInteractor,
        private val stateInteractor: VolumeDialogStateInteractor,
        private val controller: VolumeDialogController,
        private val volumePanelGlobalStateInteractor: VolumePanelGlobalStateInteractor,
        private val mediaOutputDialogManager: MediaOutputDialogManager,
        private val deviceProvisionedController: DeviceProvisionedController,
        private val audioManager: AudioManager,
    ) {
        fun create() =
            MiraCLOVolumeDialog(
                context, visibilityInteractor, stateInteractor, controller,
                volumePanelGlobalStateInteractor, mediaOutputDialogManager,
                deviceProvisionedController, audioManager,
            )
    }

    private val panel = MiraCLOVolumePanelView(getContext())
    private var scope: CoroutineScope? = null
    private var leaving = false
    private var lastBluetooth: Boolean? = null
    private val touchable = Rect()

    private val insetsListener =
        ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            panel.panelBounds(touchable)
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(touchable)
        }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        with(window!!) {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setWindowAnimations(-1)
            setBackgroundDrawable(ColorDrawable(0))
            setDecorFitsSystemWindows(false)
            attributes =
                attributes.apply {
                    title = "VolumeDialog"
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    fitInsetsTypes = 0
                    gravity = Gravity.TOP or Gravity.LEFT
                }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setCancelable(false)
        setCanceledOnTouchOutside(false)

        val gravity = context.resources.getInteger(R.integer.volume_dialog_gravity)
        panel.onLeft = (Gravity.getAbsoluteGravity(gravity, 0) and Gravity.LEFT) == Gravity.LEFT
        panel.callbacks =
            object : MiraCLOVolumePanelView.Callbacks {
                override fun onSetVolume(stream: Int, level: Int) {
                    controller.setActiveStream(stream, true)
                    controller.setStreamVolume(stream, level, true)
                    visibilityInteractor.resetDismissTimeout()
                }

                override fun onSetRingerMode(mode: Int) {
                    controller.setRingerMode(mode, false)
                    if (mode == AudioManager.RINGER_MODE_VIBRATE && controller.hasVibrator()) {
                        controller.vibrate(
                            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                    }
                    visibilityInteractor.resetDismissTimeout()
                }

                override fun onSettings() {
                    volumePanelGlobalStateInteractor.setVisible(true)
                    visibilityInteractor.dismissDialog(Events.DISMISS_REASON_SETTINGS_CLICKED)
                }

                override fun onOutput() {
                    visibilityInteractor.dismissDialog(Events.DISMISS_REASON_SETTINGS_CLICKED)
                    try {
                        mediaOutputDialogManager.createAndShowForSystemRouting()
                    } catch (_: Exception) {
                    }
                }

                override fun onUserActivity() {
                    visibilityInteractor.resetDismissTimeout()
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            panel,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onStart() {
        super.onStart()
        leaving = false
        val ctx = context
        val dark =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        panel.setLook(
            dark = dark,
            liquid = MiraCLOLiquidGlass.isEnabled(ctx, MiraCLOLiquidGlass.VOLUME),
            fill = ctx.getColor(com.android.internal.R.color.materialColorPrimary),
            onFill = ctx.getColor(com.android.internal.R.color.materialColorOnPrimary),
        )
        panel.setHasVibrator(controller.hasVibrator())
        panel.viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        stateInteractor.volumeDialogState
            .onEach { model ->
                panel.setState(model)
                val bt = model.streamModels[AudioManager.STREAM_MUSIC]?.routedToBluetooth == true
                if (bt != lastBluetooth) {
                    lastBluetooth = bt
                    updateOutput()
                }
            }
            .launchIn(s)
        visibilityInteractor.dialogVisibility
            .onEach { model ->
                when (model) {
                    is VolumeDialogVisibilityModel.Visible -> {
                        panel.setShowSettings(
                            deviceProvisionedController.isCurrentUserSetup &&
                                model.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE)
                        panel.animateIn()
                    }
                    is VolumeDialogVisibilityModel.Dismissed -> leave()
                    else -> {}
                }
            }
            .launchIn(s)
    }

    override fun onStop() {
        try {
            panel.viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
        } catch (_: Exception) {
        }
        scope?.cancel()
        scope = null
        super.onStop()
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        panel.animateOut {
            if (isShowing) {
                try {
                    dismiss()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** The media output row: this phone's speaker, or the device media is playing on. */
    private fun updateOutput() {
        var title = context.getString(R.string.miraclo_volume_phone_speaker)
        var subtitle = context.getString(R.string.miraclo_volume_playing_here)
        var bluetooth = false
        try {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            val device = audioManager.getDevicesForAttributes(media).firstOrNull()
            if (device != null && device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                val name = device.name
                if (!name.isNullOrBlank()) {
                    title = name
                    subtitle = context.getString(R.string.miraclo_volume_media_output)
                    bluetooth = true
                }
            }
        } catch (_: Exception) {
        }
        panel.setOutput(title, subtitle, bluetooth)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isShowing && event.action == MotionEvent.ACTION_OUTSIDE) {
            visibilityInteractor.dismissDialog(Events.DISMISS_REASON_TOUCH_OUTSIDE)
            return true
        }
        return false
    }

    companion object {
        /** Secure setting: 1 (default) for the MiraCLO volume panel, 0 for the stock one. */
        const val KEY = "miraclo_volume_panel"

        @JvmStatic
        fun isEnabled(context: Context): Boolean =
            try {
                Settings.Secure.getIntForUser(
                    context.contentResolver, KEY, 1, ActivityManager.getCurrentUser()) != 0
            } catch (_: Exception) {
                true
            }
    }
}
