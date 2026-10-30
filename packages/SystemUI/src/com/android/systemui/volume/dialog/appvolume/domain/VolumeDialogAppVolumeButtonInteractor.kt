package com.android.systemui.volume.dialog.appvolume.domain

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppVolumeModel(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val volume: Float,
    val isMuted: Boolean
)

/** Exposes [VolumeDialogAppVolumeButtonViewModel]. */
@VolumeDialogScope
class VolumeDialogAppVolumeButtonInteractor
@Inject
constructor(
    @Application private val context: Context,
    @VolumeDialog private val coroutineScope: CoroutineScope,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val packageManager = context.packageManager

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    private val _activeAppVolumes = MutableStateFlow<List<AppVolumeModel>>(emptyList())
    val activeAppVolumes: StateFlow<List<AppVolumeModel>> = _activeAppVolumes.asStateFlow()
    private val sessionActivePackages = mutableSetOf<String>()
    private val appInfoCache = mutableMapOf<String, Pair<String, Drawable?>>()

    private fun isSettingEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.SHOW_APP_VOLUME,
            0,
            UserHandle.USER_CURRENT
        ) == 1
    }

    private fun loadLabelAndIcon(pkg: String): Pair<String, Drawable?>? {
        appInfoCache[pkg]?.let { return it }
        val appInfo =
            try {
                packageManager.getApplicationInfo(pkg, PackageManager.MATCH_ANY_USER)
            } catch (e: PackageManager.NameNotFoundException) {
                return null
            }
        return Pair(
                packageManager.getApplicationLabel(appInfo).toString(),
                packageManager.getApplicationIcon(appInfo),
            )
            .also { appInfoCache[pkg] = it }
    }

    fun refreshAppVolumes(): Boolean {
        if (!isSettingEnabled()) {
            sessionActivePackages.clear()
            _activeAppVolumes.value = emptyList()
            _isExpanded.value = false
            return false
        }
        val list = mutableListOf<AppVolumeModel>()
        val seenPackages = mutableSetOf<String>()

        for (av in audioManager.listAppVolumes()) {
            val pkg = av.packageName
            if (av.isActive) {
                sessionActivePackages.add(pkg)
            }
            if ((av.isActive || sessionActivePackages.contains(pkg)) && seenPackages.add(pkg)) {
                val (label, icon) = loadLabelAndIcon(pkg) ?: continue
                list.add(
                    AppVolumeModel(
                        packageName = pkg,
                        label = label,
                        icon = icon,
                        volume = av.volume,
                        isMuted = av.isMuted
                    )
                )
            }
        }
        _activeAppVolumes.value = list
        if (list.isEmpty()) {
            _isExpanded.value = false
        }
        return list.isNotEmpty()
    }

    val isVisible: StateFlow<Boolean> =
        callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    trySend(refreshAppVolumes())
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SHOW_APP_VOLUME),
                false,
                observer,
                UserHandle.USER_CURRENT
            )
            val job = coroutineScope.launch {
                while (true) {
                    trySend(refreshAppVolumes())
                    delay(1000)
                }
            }
            awaitClose {
                job.cancel()
                context.contentResolver.unregisterContentObserver(observer)
            }
        }
            .stateIn(coroutineScope, SharingStarted.Eagerly, refreshAppVolumes())

    fun onButtonClicked() {
        refreshAppVolumes()
        if (_activeAppVolumes.value.isNotEmpty()) {
            _isExpanded.value = !_isExpanded.value
        }
    }

    fun setAppVolume(packageName: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        audioManager.setAppVolume(packageName, clamped)
        // Update locally instead of re-querying every app on each drag event.
        _activeAppVolumes.update { list ->
            list.map { if (it.packageName == packageName) it.copy(volume = clamped) else it }
        }
    }

    fun onUserInteraction() {
        visibilityInteractor.resetDismissTimeout()
    }
}
