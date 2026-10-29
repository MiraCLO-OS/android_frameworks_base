/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.projection.StopReason
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import com.android.systemui.screenrecord.ScreenRecordUxController

/**
 * The island's own activities, which do not come from an app's notification:
 * - the built-in screen recorder (countdown, recording time, Stop),
 * - short system moments: charging, earbuds connected, ringer mode changed, low battery.
 *
 * Everything runs on the main thread and reports through [Sink].
 */
class IslandSystemSources(
    private val context: Context,
    private val mainHandler: Handler,
    private val screenRecord: ScreenRecordUxController,
    private val sink: Sink,
) {
    interface Sink {
        fun put(activity: IslandActivity)

        fun remove(key: String)
    }

    // Screen recording ----------------------------------------------------------------------------

    private var recordingSince = 0L

    private val recordCallback =
        object : ScreenRecordUxController.StateChangeCallback {
            override fun onCountdown(millisUntilFinished: Long) {
                val secs = ((millisUntilFinished + 999) / 1000).coerceAtLeast(1)
                sink.put(
                    recordActivity(
                        value = secs.toString(),
                        subtitle = "Starting",
                        clock = null,
                        actions = listOf(
                            IslandAction("Cancel", null, null, IslandAction.Style.NEUTRAL) {
                                screenRecord.cancelCountdown()
                            }
                        ),
                    )
                )
            }

            override fun onCountdownEnd() {
                if (!screenRecord.isRecording) sink.remove(KEY_RECORD)
            }

            override fun onRecordingStart() {
                recordingSince = SystemClock.elapsedRealtime()
                sink.put(
                    recordActivity(
                        value = null,
                        subtitle = "Screen recording",
                        clock = IslandClock(recordingSince, false, true, 0L),
                        actions = listOf(
                            IslandAction("Stop", null, null, IslandAction.Style.DANGER) {
                                screenRecord.stopRecording(StopReason.STOP_PRIVACY_CHIP)
                            }
                        ),
                    )
                )
            }

            override fun onRecordingEnd() {
                recordingSince = 0L
                sink.remove(KEY_RECORD)
            }
        }

    private fun recordActivity(
        value: CharSequence?,
        subtitle: CharSequence,
        clock: IslandClock?,
        actions: List<IslandAction>,
    ) =
        IslandActivity(
            key = KEY_RECORD,
            kind = IslandKind.SCREEN_RECORD,
            packageName = SYSTEMUI,
            accent = RECORD_RED,
            title = "Screen recording",
            subtitle = subtitle,
            art = null,
            clock = clock,
            actions = actions,
            contentIntent = null,
            value = value,
            symbol = IslandSymbol.RECORD,
        )

    // System moments ----------------------------------------------------------------------------

    private var level = -1
    private var plugged = false
    private var lowShownAt = 101
    private var lastRinger = -1

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                    Intent.ACTION_POWER_CONNECTED -> mainHandler.postDelayed({ showCharging() }, 300)
                    AudioManager.RINGER_MODE_CHANGED_ACTION ->
                        if (!isInitialStickyBroadcast) onRinger(intent)
                    BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED -> onEarbudsBattery(intent)
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (d != null) connectedAt[d.address] = SystemClock.uptimeMillis()
                    }
                }
            }
        }

    private val connectedAt = HashMap<String, Long>()

    var chargingEnabled = true
    var earbudsEnabled = true
    var ringerEnabled = true
    var lowBatteryEnabled = true

    fun start() {
        screenRecord.addCallback(recordCallback)
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }
        context.registerReceiverAsUser(receiver, UserHandle.ALL, filter, null, mainHandler, Context.RECEIVER_EXPORTED)
    }

    private fun onBattery(intent: Intent) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (plugged) {
            lowShownAt = 101
            sink.remove(KEY_LOW_BATTERY)
            return
        }
        // At 20% and at 10%, once each until the phone is charged again.
        val threshold = LOW_LEVELS.firstOrNull { level in 1..it && lowShownAt > it } ?: return
        lowShownAt = threshold
        if (!lowBatteryEnabled) return
        val power = context.getSystemService(PowerManager::class.java)
        val saverOn = power?.isPowerSaveMode == true
        sink.put(
            IslandActivity(
                key = KEY_LOW_BATTERY,
                kind = IslandKind.SYSTEM,
                packageName = SYSTEMUI,
                accent = LOW_RED,
                title = "Battery low",
                subtitle = if (saverOn) "Battery saver is on" else "Battery low",
                art = null,
                clock = null,
                actions =
                    if (saverOn) emptyList()
                    else listOf(
                        IslandAction("Battery saver", null, null, IslandAction.Style.PRIMARY) {
                            power?.setPowerSaveModeEnabled(true)
                            sink.remove(KEY_LOW_BATTERY)
                        }
                    ),
                contentIntent = null,
                value = "$level%",
                symbol = IslandSymbol.BATTERY_LOW,
            )
        )
    }

    private fun showCharging() {
        if (!chargingEnabled || level < 0) return
        moment(KEY_CHARGING, CHARGE_GREEN, IslandSymbol.BOLT, "Charging", "$level%")
    }

    private fun onRinger(intent: Intent) {
        val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
        if (mode == lastRinger || mode < 0) return
        val first = lastRinger < 0
        lastRinger = mode
        if (first || !ringerEnabled) return
        when (mode) {
            AudioManager.RINGER_MODE_NORMAL -> moment(KEY_RINGER, 0xFFE2E8F0.toInt(), IslandSymbol.BELL, "Ringer", "Ring")
            AudioManager.RINGER_MODE_VIBRATE -> moment(KEY_RINGER, 0xFFC4B5FD.toInt(), IslandSymbol.VIBRATE, "Ringer", "Vibrate")
            else -> moment(KEY_RINGER, 0xFFFCA5A5.toInt(), IslandSymbol.SILENT, "Ringer", "Silent")
        }
    }

    private fun onEarbudsBattery(intent: Intent) {
        if (!earbudsEnabled) return
        val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        // Only right after the earbuds connect, not on every level change.
        val since = connectedAt.remove(d.address) ?: return
        if (SystemClock.uptimeMillis() - since > EARBUDS_WINDOW_MS) return
        val whole = intent.getIntExtra(BluetoothDevice.EXTRA_BATTERY_LEVEL, -1)
        fun meta(key: Int): Int? =
            try {
                d.getMetadata(key)?.let { String(it).trim().toIntOrNull() }?.takeIf { it in 0..100 }
            } catch (e: RuntimeException) {
                null
            }
        val left = meta(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)
        val right = meta(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY)
        val case = meta(BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY)
        val value =
            if (left != null && right != null) {
                "L $left  R $right" + (case?.let { "  Case $it" } ?: "")
            } else if (whole in 0..100) {
                "$whole%"
            } else {
                return
            }
        val name =
            try {
                d.alias ?: d.name
            } catch (e: SecurityException) {
                null
            } ?: "Earbuds"
        moment(KEY_EARBUDS, 0xFFE2E8F0.toInt(), IslandSymbol.EARBUDS, name, value)
    }

    /** A short moment: shows for [MOMENT_MS], then goes back into the camera. */
    private fun moment(key: String, accent: Int, symbol: IslandSymbol, title: String, value: String) {
        val until = SystemClock.uptimeMillis() + MOMENT_MS
        sink.put(
            IslandActivity(
                key = key,
                kind = IslandKind.SYSTEM,
                packageName = SYSTEMUI,
                accent = accent,
                title = title,
                subtitle = value,
                art = null,
                clock = null,
                actions = emptyList(),
                contentIntent = null,
                value = value,
                expiresAt = until,
                symbol = symbol,
                signature = "$key|$until",
            )
        )
        mainHandler.removeCallbacksAndMessages(key)
        mainHandler.postAtTime({ sink.remove(key) }, key, until)
    }

    companion object {
        const val KEY_RECORD = "miraclo:screenrecord"
        const val KEY_CHARGING = "miraclo:charging"
        const val KEY_EARBUDS = "miraclo:earbuds"
        const val KEY_RINGER = "miraclo:ringer"
        const val KEY_LOW_BATTERY = "miraclo:lowbattery"
        private const val SYSTEMUI = "com.android.systemui"
        private const val MOMENT_MS = 3000L
        private const val EARBUDS_WINDOW_MS = 30_000L
        private val LOW_LEVELS = listOf(10, 20)
        val RECORD_RED = 0xFFEF4444.toInt()
        private val LOW_RED = 0xFFF87171.toInt()
        private val CHARGE_GREEN = 0xFF4ADE80.toInt()
    }
}
