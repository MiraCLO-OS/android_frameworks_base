package com.android.systemui.volume.dialog.appvolume.ui.viewmodel

import com.android.systemui.volume.dialog.appvolume.domain.AppVolumeModel
import com.android.systemui.volume.dialog.appvolume.domain.VolumeDialogAppVolumeButtonInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Models the app volume button and the per-app volume pills. */
class VolumeDialogAppVolumeButtonViewModel
@Inject
constructor(private val interactor: VolumeDialogAppVolumeButtonInteractor) {
    val isVisible: StateFlow<Boolean> = interactor.isVisible
    val isExpanded: StateFlow<Boolean> = interactor.isExpanded
    val activeAppVolumes: StateFlow<List<AppVolumeModel>> = interactor.activeAppVolumes

    fun onButtonClicked() {
        interactor.onButtonClicked()
    }

    fun setAppVolume(packageName: String, volume: Float) {
        interactor.setAppVolume(packageName, volume)
    }

    /** Called while the user touches a pill, so the dialog doesn't time out under their finger. */
    fun onUserInteraction() {
        interactor.onUserInteraction()
    }
}
