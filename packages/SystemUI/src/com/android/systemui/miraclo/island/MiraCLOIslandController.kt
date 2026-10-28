/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.miraclo.island

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.PendingIntent
import android.app.StatusBarManager
import android.app.WindowConfiguration
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.StatusBarState
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * MiraCLO Dynamic Island.
 *
 * A small pill around the front camera that shows what is playing, and expands into a card when
 * tapped. It is off by default and controlled from Settings > Notifications > Dynamic Island
 * (Settings.Secure keys below).
 *
 * The island is its own overlay window. It shows only while the music app is minimised: it hides
 * while that app is on screen (picture-in-picture counts as minimised), while the status bar is
 * hidden (full-screen apps), while the shade is open (QS has its own player), on the lock screen
 * and while dozing, and in landscape.
 */
@SysUISingleton
class MiraCLOIslandController
@Inject
constructor(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mediaDataManager: MediaDataManager,
    private val commandQueue: CommandQueue,
    private val shadeExpansionStateManager: ShadeExpansionStateManager,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    @Main private val mainHandler: Handler,
    @Background private val bgExecutor: Executor,
) : CoreStartable, MediaDataManager.Listener {

    private val sessions = LinkedHashMap<String, MediaData>()

    private var islandEnabled = false
    private var mediaEnabled = true
    private var statusBarShowing = true
    private var shadeOpen = false
    private var dozing = false
    private var barState = StatusBarState.SHADE

    /** Packages with a visible, non picture-in-picture task (what is on screen). */
    private var foregroundPackages: Set<String> = emptySet()

    /**
     * The app just opened from the island: treated as on screen right away, for a moment, so the
     * island does not flash back before the app's window appears.
     */
    private var launchingPackage: String? = null
    private var launchingUntil = 0L

    private var view: MiraCLOIslandView? = null

    /**
     * The song that was swiped away (and its app). The island stays hidden until the next song,
     * or until that app has been opened again.
     */
    private var dismissedKey: String? = null
    private var dismissedPackage: String? = null

    private val updateRunnable = Runnable { update() }

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                readSettings()
                clearDismissal("settings changed")
                update()
            }
        }

    private val taskStackListener =
        object : TaskStackChangeListener {
            override fun onTaskStackChanged() = refreshForeground()

            override fun onActivityUnpinned() = refreshForeground()
        }

    override fun start() {
        val resolver = context.contentResolver
        for (key in listOf(KEY_ENABLED, KEY_MEDIA)) {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                settingsObserver,
                UserHandle.USER_ALL,
            )
        }
        readSettings()

        mediaDataManager.addListener(this)

        commandQueue.addCallback(
            object : CommandQueue.Callbacks {
                override fun setWindowState(displayId: Int, window: Int, state: Int) {
                    if (displayId != context.displayId ||
                        window != StatusBarManager.WINDOW_STATUS_BAR
                    ) {
                        return
                    }
                    statusBarShowing = state == StatusBarManager.WINDOW_STATE_SHOWING
                    update()
                }
            }
        )

        shadeExpansionStateManager.addExpansionListener { event ->
            val open = event.fraction > 0f
            if (open != shadeOpen) {
                shadeOpen = open
                update()
            }
        }

        dozing = statusBarStateController.isDozing
        barState = statusBarStateController.state
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onDozingChanged(isDozing: Boolean) {
                    dozing = isDozing
                    update()
                }

                override fun onStateChanged(newState: Int) {
                    barState = newState
                    update()
                }
            }
        )

        TaskStackChangeListeners.getInstance().registerTaskStackListener(taskStackListener)
        refreshForeground()

        context.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    view?.onHostConfigurationChanged()
                    update()
                }

                @Deprecated("Deprecated in Java") override fun onLowMemory() {}
            }
        )
    }

    private fun readSettings() {
        val user = ActivityManager.getCurrentUser()
        val resolver = context.contentResolver
        islandEnabled = Settings.Secure.getIntForUser(resolver, KEY_ENABLED, 0, user) != 0
        mediaEnabled = Settings.Secure.getIntForUser(resolver, KEY_MEDIA, 1, user) != 0
    }

    // What is on screen ---------------------------------------------------------------------------

    /** Reads the visible tasks off the main thread, then updates on the main thread. */
    private fun refreshForeground() {
        bgExecutor.execute {
            val packages =
                try {
                    ActivityTaskManager.getInstance()
                        .getTasks(MAX_TASKS)
                        .filter {
                            it.isVisible() &&
                                it.windowingMode != WindowConfiguration.WINDOWING_MODE_PINNED
                        }
                        .mapNotNull { it.topActivity?.packageName }
                        .toSet()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Could not read the visible tasks", e)
                    return@execute
                }
            mainHandler.post {
                if (packages == foregroundPackages) return@post
                foregroundPackages = packages
                // Opening the app again brings a swiped-away island back (once it is minimised).
                if (dismissedPackage != null && dismissedPackage in packages) {
                    clearDismissal("app opened")
                }
                if (launchingPackage != null && launchingPackage in packages) {
                    launchingPackage = null
                }
                update()
            }
        }
    }

    private fun isOnScreen(packageName: String): Boolean =
        packageName in foregroundPackages ||
            (packageName == launchingPackage && SystemClock.uptimeMillis() < launchingUntil)

    // MediaDataManager.Listener ----------------------------------------------------------------

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
    ) {
        mainHandler.post {
            if (oldKey != null && oldKey != key) sessions.remove(oldKey)
            // Re-insert so the most recently updated session is last.
            sessions.remove(key)
            sessions[key] = data
            update()
        }
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        mainHandler.post {
            sessions.remove(key)
            update()
        }
    }

    // State --------------------------------------------------------------------------------------

    /** The session to show: a playing one first, otherwise the latest active one. */
    private fun currentSession(): MediaData? {
        val active = sessions.values.filter { it.active && !it.resumption }
        return active.lastOrNull { it.isPlaying == true } ?: active.lastOrNull()
    }

    /** Other recent players (newest first) that can be resumed from the expanded island. */
    private fun recentSessions(current: MediaData): List<MediaData> =
        sessions.values
            .filter { it !== current && it.artwork != null && it.packageName != current.packageName }
            .reversed()
            .distinctBy { it.packageName }
            .take(MAX_RECENTS)

    private fun keyOf(data: MediaData) = "${data.packageName}|${data.song}"

    private fun clearDismissal(reason: String) {
        if (dismissedKey == null) return
        Log.d(TAG, "Island back: $reason")
        dismissedKey = null
        dismissedPackage = null
    }

    private fun isPortrait() =
        context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun update() {
        val current = currentSession()
        // A swiped-away island comes back with the next song, or once the music app has gone.
        val dismissed = dismissedKey
        if (dismissed != null) {
            when {
                current == null -> clearDismissal("session ended")
                keyOf(current) != dismissed -> clearDismissal("next song")
            }
        }
        val show =
            islandEnabled &&
                mediaEnabled &&
                current != null &&
                dismissedKey == null &&
                !isOnScreen(current.packageName) &&
                statusBarShowing &&
                !shadeOpen &&
                !dozing &&
                barState == StatusBarState.SHADE &&
                isPortrait()
        if (!show) {
            removeWindow()
            return
        }
        val v = view ?: addWindow() ?: return
        v.bind(current, recentSessions(current))
    }

    // Window -------------------------------------------------------------------------------------

    private fun addWindow(): MiraCLOIslandView? {
        val v =
            MiraCLOIslandView(
                context,
                object : MiraCLOIslandView.Host {
                    override fun openApp(intent: PendingIntent) {
                        val pkg = currentSession()?.packageName ?: intent.creatorPackage
                        activityStarter.postStartActivityDismissingKeyguard(intent)
                        // The app comes to the front: the island vanishes and stays away while
                        // the app is on screen.
                        launchingPackage = pkg
                        launchingUntil = SystemClock.uptimeMillis() + LAUNCH_GRACE_MS
                        mainHandler.removeCallbacks(updateRunnable)
                        mainHandler.postDelayed(updateRunnable, LAUNCH_GRACE_MS + 50)
                        vanishWindow()
                    }

                    override fun mainHandler(): Handler = mainHandler

                    override fun dismiss() {
                        val current = currentSession()
                        dismissedKey = current?.let { keyOf(it) }
                        dismissedPackage = current?.packageName
                        Log.d(TAG, "Island swiped away")
                        removeWindow()
                    }
                },
            )
        val lp =
            // Full width so the pill can be swiped off the screen edge; only the pill or the card
            // itself takes touches (see MiraCLOIslandView).
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 0
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    title = "MiraCLOIsland"
                    setFitInsetsTypes(0)
                }
        return try {
            windowManager.addView(v, lp)
            view = v
            v
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not add the island window", e)
            null
        }
    }

    /** Quick fade, then remove (used when the app is opened from the island). */
    private fun vanishWindow() {
        val v = view ?: return
        v.vanish { if (view === v) removeWindow() }
    }

    private fun removeWindow() {
        val v = view ?: return
        view = null
        v.release()
        try {
            windowManager.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Island window already gone", e)
        }
    }

    companion object {
        private const val TAG = "MiraCLOIsland"
        private const val MAX_RECENTS = 4
        private const val MAX_TASKS = 8
        private const val LAUNCH_GRACE_MS = 1500L

        /** Settings.Secure: 1 = Dynamic Island on (default 0). */
        const val KEY_ENABLED = "miraclo_island_enabled"
        /** Settings.Secure: 1 = show media playback in the island (default 1). */
        const val KEY_MEDIA = "miraclo_island_media"
    }
}
