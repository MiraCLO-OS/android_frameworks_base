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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
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
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.StatusBarState
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * MiraCLO Dynamic Island.
 *
 * Collects live activities (media, ongoing calls, Clock timer and stopwatch), decides which are
 * shown and where, and hands that to [MiraCLOIslandView], which draws and animates it.
 *
 * Layout rules:
 * - Up to three activities show at once. [order] is newest first: index 0 is the main pill on the
 *   camera, 1 the round pill on its right, 2 the round pill on its left. Swiping the main pill
 *   sideways swaps it with the round pill on that side.
 * - Tapping a pill opens it as a card under the status bar; cards stack newest-opened on top
 *   ([cards]); the others stay pills. Tapping outside folds every card back into its pill.
 * - The main pill closes by swiping it up (until the activity becomes something new), or when the
 *   activity ends.
 * - An activity hides while its app is on screen (picture-in-picture counts as minimised).
 *
 * The whole island fades out (its window stays, so nothing re-grows afterwards) on the lock
 * screen, while dozing, while the shade opens and while the status bar is hidden (full-screen
 * apps); it is removed in landscape and once nothing is left to show. Off by default; settings
 * are the Settings.Secure keys below.
 *
 * Music that stays paused for [PAUSED_TIMEOUT_MS] leaves the island, and comes back on play. A
 * timer that goes off opens its card by itself and folds it back once answered.
 */
@SysUISingleton
class MiraCLOIslandController
@Inject
constructor(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mediaDataManager: MediaDataManager,
    private val notificationListener: NotificationListener,
    private val commandQueue: CommandQueue,
    private val shadeExpansionStateManager: ShadeExpansionStateManager,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    @Main private val mainHandler: Handler,
    @Background private val bgExecutor: Executor,
) : CoreStartable, MediaDataManager.Listener, NotificationListener.NotificationHandler {

    private val reader = IslandNotificationReader(context)

    // Settings ------------------------------------------------------------------------------------

    private var islandEnabled = false
    private var showMedia = true
    private var showCalls = true
    private var showTimers = true
    private var hideWhileOpen = true
    private var severalActivities = true
    private var pausedTimeout = true
    private var config = MiraCLOIslandView.Config()

    // Global conditions ---------------------------------------------------------------------------

    private var statusBarShowing = true
    private var shadeOpen = false
    private var shadeFraction = 0f
    private var dozing = false
    private var barState = StatusBarState.SHADE

    // Activities ----------------------------------------------------------------------------------

    /** Media sessions from the media pipeline (one island activity: the current session). */
    private val sessions = LinkedHashMap<String, MediaData>()
    private var mediaArtKey: String? = null
    private var mediaArt: Bitmap? = null
    private var mediaAccent = DEFAULT_ACCENT
    /** When the current music was paused (uptime), or 0 while it plays. */
    private var mediaPausedAt = 0L
    private var mediaPausedPackage: String? = null
    private val pausedTimeoutRunnable = Runnable { update() }

    /** All live activities by key. */
    private val activities = LinkedHashMap<String, IslandActivity>()
    /** Every activity key, newest first (see the class comment). */
    private val order = ArrayList<String>()
    /** Keys opened as cards, top card first. */
    private val cards = ArrayList<String>()
    /** Swiped-away activities: key -> signature at that moment. */
    private val dismissed = HashMap<String, String>()
    /** Notification reads in flight: key -> sequence, so a late read never revives a removed one. */
    private val readSeq = HashMap<String, Int>()
    private var seqCounter = 0

    /** Packages with a visible, non picture-in-picture task. */
    private var foregroundPackages: Set<String> = emptySet()
    private var launchingPackage: String? = null
    private var launchingUntil = 0L

    private var view: MiraCLOIslandView? = null
    private val updateRunnable = Runnable { update() }
    // An app going to the background is still "visible" while its closing animation runs, so the
    // visible tasks are read again once that has finished.
    private val foregroundRecheck = Runnable { refreshForeground() }
    private val foregroundRecheckLate = Runnable { refreshForeground() }

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                readSettings()
                dismissed.clear()
                update()
            }
        }

    private val taskStackListener =
        object : TaskStackChangeListener {
            override fun onTaskStackChanged() = onTasksChanged()

            override fun onActivityUnpinned() = onTasksChanged()
        }

    override fun start() {
        val resolver = context.contentResolver
        for (key in ALL_KEYS) {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                settingsObserver,
                UserHandle.USER_ALL,
            )
        }
        readSettings()

        mediaDataManager.addListener(this)
        notificationListener.addNotificationHandler(this)
        // Notifications already posted before we were listening.
        mainHandler.post {
            try {
                notificationListener.activeNotifications?.forEach { onPosted(it) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not read active notifications", e)
            }
        }

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
            // Every frame of a shade drag: only the fade follows it; the island updates when the
            // shade starts or stops being open.
            shadeFraction = event.fraction
            view?.setShadeFraction(event.fraction)
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
        val r = context.contentResolver
        fun on(key: String, def: Int) = Settings.Secure.getIntForUser(r, key, def, user) != 0
        islandEnabled = on(KEY_ENABLED, 0)
        showMedia = on(KEY_MEDIA, 1)
        showCalls = on(KEY_CALLS, 1)
        showTimers = on(KEY_TIMERS, 1)
        hideWhileOpen = on(KEY_HIDE_WHILE_OPEN, 1)
        severalActivities = on(KEY_SEVERAL, 1)
        pausedTimeout = on(KEY_PAUSED_TIMEOUT, 1)
        config =
            MiraCLOIslandView.Config(
                touchGlow = on(KEY_TOUCH_GLOW, 1),
                ambientGlow = on(KEY_AMBIENT_GLOW, 1),
                haptics = on(KEY_HAPTICS, 1),
            )
        view?.config = config
    }

    // What is on screen ----------------------------------------------------------------------------

    private fun onTasksChanged() {
        refreshForeground()
        mainHandler.removeCallbacks(foregroundRecheck)
        mainHandler.removeCallbacks(foregroundRecheckLate)
        mainHandler.postDelayed(foregroundRecheck, FOREGROUND_RECHECK_MS)
        mainHandler.postDelayed(foregroundRecheckLate, FOREGROUND_RECHECK_LATE_MS)
    }

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
                // Opening the app again brings its swiped-away activity back (once minimised).
                dismissed.keys.removeAll { key -> activities[key]?.packageName in packages }
                if (launchingPackage != null && launchingPackage in packages) launchingPackage = null
                update()
            }
        }
    }

    private fun isOnScreen(pkg: String): Boolean =
        pkg in foregroundPackages ||
            (pkg == launchingPackage && SystemClock.uptimeMillis() < launchingUntil)

    // Media ----------------------------------------------------------------------------------------

    override fun onMediaDataLoaded(key: String, oldKey: String?, data: MediaData, immediately: Boolean) {
        mainHandler.post {
            if (oldKey != null && oldKey != key) sessions.remove(oldKey)
            sessions.remove(key)
            sessions[key] = data
            refreshMedia()
        }
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        mainHandler.post {
            sessions.remove(key)
            refreshMedia()
        }
    }

    /** The session to show: a playing one first, otherwise the latest active one. */
    private fun currentSession(): MediaData? {
        val active = sessions.values.filter { it.active && !it.resumption }
        return active.lastOrNull { it.isPlaying == true } ?: active.lastOrNull()
    }

    private fun refreshMedia() {
        val data = currentSession()
        if (data == null) {
            mediaPausedAt = 0L
            removeActivity(MEDIA_KEY)
            return
        }
        // Paused music leaves after a while (see isShown); playing again brings it back.
        if (data.isPlaying == true) {
            mediaPausedAt = 0L
        } else if (mediaPausedAt == 0L || mediaPausedPackage != data.packageName) {
            mediaPausedAt = SystemClock.uptimeMillis()
        }
        mediaPausedPackage = data.packageName
        mainHandler.removeCallbacks(pausedTimeoutRunnable)
        if (mediaPausedAt != 0L) {
            mainHandler.postAtTime(pausedTimeoutRunnable, mediaPausedAt + PAUSED_TIMEOUT_MS + 50)
        }
        // Decode the art only when the song changes.
        val artKey = "${data.packageName}|${data.song}|${data.artist}|${data.artwork != null}"
        if (artKey != mediaArtKey) {
            mediaArtKey = artKey
            mediaArt = data.artwork?.loadDrawable(context)?.let { toBitmap(it) }
            mediaAccent = mediaArt?.let { vivid(averageColor(it)) } ?: DEFAULT_ACCENT
        }
        putActivity(
            IslandActivity(
                key = MEDIA_KEY,
                kind = IslandKind.MEDIA,
                packageName = data.packageName,
                accent = mediaAccent,
                title = data.song ?: "",
                subtitle = data.artist ?: data.app,
                art = mediaArt,
                clock = null,
                actions = emptyList(),
                contentIntent = data.clickIntent,
                media = data,
                recents = recentSessions(data),
                signature = "${data.packageName}|${data.song}",
            )
        )
    }

    /** Other recent players (newest first) that can be resumed from the music card. */
    private fun recentSessions(current: MediaData): List<MediaData> =
        sessions.values
            .filter { it !== current && it.artwork != null && it.packageName != current.packageName }
            .reversed()
            .distinctBy { it.packageName }
            .take(4)

    // Notifications ----------------------------------------------------------------------------------

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) =
        onPosted(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?) =
        onRemoved(sbn)

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int,
    ) = onRemoved(sbn)

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {}

    override fun onNotificationsInitialized() {}

    private fun onPosted(sbn: StatusBarNotification) {
        if (!reader.isCandidate(sbn)) {
            // It may have been a call or a timer before (e.g. a call turning into a missed call).
            if (activities.containsKey(sbn.key)) removeActivity(sbn.key)
            return
        }
        val seq = ++seqCounter
        readSeq[sbn.key] = seq
        // Inflating the Clock layout is the costly part: off the main thread.
        bgExecutor.execute {
            val activity = reader.read(sbn)
            mainHandler.post {
                if (readSeq[sbn.key] != seq) return@post
                readSeq.remove(sbn.key)
                if (activity == null) removeActivity(sbn.key) else putActivity(activity)
            }
        }
    }

    private fun onRemoved(sbn: StatusBarNotification) {
        // Invalidate any read in flight, then drop it.
        readSeq[sbn.key] = ++seqCounter
        removeActivity(sbn.key)
    }

    // State ------------------------------------------------------------------------------------------

    private fun putActivity(activity: IslandActivity) {
        val old = activities[activity.key]
        val isNew = old == null
        activities[activity.key] = activity
        dismissed[activity.key]?.let { if (it != activity.signature) dismissed.remove(activity.key) }
        if (isNew) {
            order.remove(activity.key)
            // While cards are open, a new activity goes beside the camera and leaves the main pill
            // where the user is looking; otherwise the newest becomes the main pill.
            val at = if (cards.isNotEmpty() && order.isNotEmpty()) 1 else 0
            order.add(at, activity.key)
        }
        if (activity.alert && old?.alert != true) {
            // Time's up: open its card on top, and let it be seen even if it was swiped away.
            dismissed.remove(activity.key)
            cards.remove(activity.key)
            cards.add(0, activity.key)
        } else if (!activity.alert && old?.alert == true) {
            // Answered (+1:00): fold back into its pill.
            cards.remove(activity.key)
        }
        update()
    }

    private fun removeActivity(key: String) {
        if (activities.remove(key) == null) return
        order.remove(key)
        cards.remove(key)
        dismissed.remove(key)
        view?.setExitStyle(key, MiraCLOIslandView.Exit.ENDED)
        update()
    }

    private fun isShown(a: IslandActivity): Boolean =
        when (a.kind) {
                IslandKind.MEDIA -> showMedia
                IslandKind.CALL -> showCalls
                IslandKind.TIMER,
                IslandKind.STOPWATCH -> showTimers
            } &&
            !dismissed.containsKey(a.key) &&
            !(hideWhileOpen && isOnScreen(a.packageName)) &&
            !(a.kind == IslandKind.MEDIA && isPausedTooLong())

    private fun isPausedTooLong(): Boolean =
        pausedTimeout && mediaPausedAt != 0L &&
            SystemClock.uptimeMillis() - mediaPausedAt >= PAUSED_TIMEOUT_MS

    private fun isPortrait() =
        context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun update() {
        // Removed only when it cannot show at all; otherwise faded, so it never re-grows.
        if (!islandEnabled || !isPortrait()) {
            cards.clear()
            removeWindow()
            return
        }
        val visible =
            order
                .mapNotNull { activities[it] }
                .filter { isShown(it) }
                .take(if (severalActivities) MAX_VISIBLE else 1)
        cards.retainAll { key -> visible.any { it.key == key } }

        val shown = statusBarShowing && !dozing && barState == StatusBarState.SHADE
        // Cards fold away while hidden or while the shade comes down over them.
        if (!shown || shadeOpen) cards.clear()

        if (visible.isEmpty() && view == null) return
        val pills = visible.filter { it.key !in cards }
        val openCards = cards.mapNotNull { key -> visible.firstOrNull { it.key == key } }
        val fresh = view == null
        val v = view ?: addWindow() ?: return
        v.setShadeFraction(shadeFraction)
        // A new window starts as it should be; an existing one fades.
        v.setShown(shown, animate = !fresh)
        v.render(MiraCLOIslandView.State(pills = pills, cards = openCards))
    }

    // Window -------------------------------------------------------------------------------------------

    private val host =
        object : MiraCLOIslandView.Host {
            override fun mainHandler(): Handler = mainHandler

            override fun onTapPill(key: String) {
                if (!activities.containsKey(key)) return
                cards.remove(key)
                cards.add(0, key)
                update()
            }

            override fun onSwap(fromKey: String, toKey: String): Boolean {
                val a = order.indexOf(fromKey)
                val b = order.indexOf(toKey)
                if (a < 0 || b < 0) return false
                order[a] = toKey
                order[b] = fromKey
                update()
                return true
            }

            override fun onDismiss(key: String) {
                val a = activities[key] ?: return
                dismissed[key] = a.signature
                cards.remove(key)
                view?.setExitStyle(key, MiraCLOIslandView.Exit.SWIPED_UP)
                update()
            }

            override fun onOpenApp(key: String) {
                val a = activities[key] ?: return
                val intent: PendingIntent = a.contentIntent ?: return
                activityStarter.postStartActivityDismissingKeyguard(intent)
                // The app comes to the front: its activity vanishes and stays away while the app
                // is on screen; the others stay.
                launchingPackage = a.packageName
                launchingUntil = SystemClock.uptimeMillis() + LAUNCH_GRACE_MS
                cards.clear()
                view?.setExitStyle(key, MiraCLOIslandView.Exit.OPENED)
                mainHandler.removeCallbacks(updateRunnable)
                mainHandler.postDelayed(updateRunnable, LAUNCH_GRACE_MS + 50)
                update()
            }

            override fun onCollapseAll() {
                if (cards.isEmpty()) return
                cards.clear()
                update()
            }

            override fun onAction(key: String, action: IslandAction) {
                try {
                    action.intent.send()
                } catch (e: PendingIntent.CanceledException) {
                    Log.w(TAG, "Action no longer available", e)
                }
                // "Time's up" answered: fold back (Stop then ends it, +1:00 runs on as a pill).
                if (activities[key]?.alert == true && cards.remove(key)) update()
            }

            override fun onEmpty() {
                // Everything has finished leaving: drop the window if nothing came back meanwhile.
                mainHandler.post {
                    val v = view ?: return@post
                    if (v.isEmpty) removeWindow()
                }
            }
        }

    private fun addWindow(): MiraCLOIslandView? {
        val v = MiraCLOIslandView(context, host).also { it.config = config }
        val lp =
            // Full width and a fixed height that fits the tallest card stack, so opening a card
            // never resizes the window mid-animation. Only the pills and cards themselves take
            // touches (see MiraCLOIslandView); everywhere else touches go straight through.
            WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * WINDOW_HEIGHT_FRACTION).toInt(),
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
                    // A system overlay: apps under the see-through part never count as obscured.
                    setTrustedOverlay()
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

    // Helpers ----------------------------------------------------------------------------------------

    private fun toBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }

    private fun averageColor(bitmap: Bitmap): Int =
        Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0) or 0xFF000000.toInt()

    /** A brighter, more saturated version of a colour, used for glows and the sound bars. */
    private fun vivid(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = maxOf(hsv[1], 0.55f)
        hsv[2] = maxOf(hsv[2], 0.92f)
        return Color.HSVToColor(hsv)
    }

    companion object {
        private const val TAG = "MiraCLOIsland"
        private const val MEDIA_KEY = "miraclo:media"
        private const val MAX_VISIBLE = 3
        private const val MAX_TASKS = 8
        private const val LAUNCH_GRACE_MS = 1500L
        private const val FOREGROUND_RECHECK_MS = 400L
        private const val FOREGROUND_RECHECK_LATE_MS = 1000L
        private const val PAUSED_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WINDOW_HEIGHT_FRACTION = 0.62f
        private val DEFAULT_ACCENT = 0xFF34D399.toInt()

        /** Settings.Secure: 1 = Dynamic Island on (default 0). */
        const val KEY_ENABLED = "miraclo_island_enabled"
        const val KEY_MEDIA = "miraclo_island_media"
        const val KEY_CALLS = "miraclo_island_calls"
        const val KEY_TIMERS = "miraclo_island_timers"
        const val KEY_HIDE_WHILE_OPEN = "miraclo_island_hide_while_open"
        const val KEY_SEVERAL = "miraclo_island_several"
        const val KEY_TOUCH_GLOW = "miraclo_island_touch_glow"
        const val KEY_AMBIENT_GLOW = "miraclo_island_ambient_glow"
        const val KEY_HAPTICS = "miraclo_island_haptics"
        const val KEY_PAUSED_TIMEOUT = "miraclo_island_paused_timeout"

        private val ALL_KEYS =
            listOf(
                KEY_ENABLED,
                KEY_MEDIA,
                KEY_CALLS,
                KEY_TIMERS,
                KEY_HIDE_WHILE_OPEN,
                KEY_SEVERAL,
                KEY_TOUCH_GLOW,
                KEY_AMBIENT_GLOW,
                KEY_HAPTICS,
                KEY_PAUSED_TIMEOUT,
            )
    }
}
