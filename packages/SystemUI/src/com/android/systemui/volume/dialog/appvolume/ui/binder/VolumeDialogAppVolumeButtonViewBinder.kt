package com.android.systemui.volume.dialog.appvolume.ui.binder

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toBitmap
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.compose.theme.PlatformTheme
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.res.R
import com.android.systemui.shared.system.BlurUtils.isVolumeAndPowerBlurEnabled
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.appvolume.domain.AppVolumeModel
import com.android.systemui.volume.dialog.appvolume.ui.viewmodel.VolumeDialogAppVolumeButtonViewModel
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.sliders.ui.VolumeSliderDimensions
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach

private const val PILL_GAP_DP = 8f
private const val APP_ICON_SIZE_DP = 24f
private const val APP_ICON_BITMAP_PX = 64
private const val ANIMATION_MS = 150L

/** Binds the app volume button and the per-app volume pills it toggles. */
@VolumeDialogScope
class VolumeDialogAppVolumeButtonViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogAppVolumeButtonViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
    private val windowRootViewBlurInteractor: WindowRootViewBlurInteractor,
) : ViewBinder {

    private class PillHolder(val frame: FrameLayout, val model: MutableState<AppVolumeModel>)

    override fun CoroutineScope.bind(view: View) {
        val appVolumeButton = view.requireViewById<ImageButton>(R.id.app_volume_icon)
        val host = view as? ConstraintLayout
        val context = view.context
        val density = context.resources.displayMetrics.density
        val resources = context.resources
        val floatingSliderHorizontalPadding =
            resources.getDimensionPixelSize(R.dimen.volume_dialog_floating_sliders_horizontal_padding)
        val floatingSliderVerticalPadding =
            resources.getDimensionPixelSize(R.dimen.volume_dialog_floating_sliders_vertical_padding)
        val sliderWidth = resources.getDimensionPixelSize(R.dimen.volume_dialog_slider_width)

        val pillsContainer =
            LinearLayout(context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                visibility = View.INVISIBLE
                alpha = 0f
                clipChildren = false
                clipToPadding = false
            }

        var blurSupported = windowRootViewBlurInteractor.isBlurCurrentlySupported.value
        val holders = LinkedHashMap<String, PillHolder>()

        if (host != null) {
            val background = host.requireViewById<View>(R.id.volume_dialog_background)
            val mainSlider = host.requireViewById<View>(R.id.volume_dialog_main_slider_container)
            val floatingSliders =
                host.requireViewById<ViewGroup>(R.id.volume_dialog_floating_sliders_container)

            val lp =
                ConstraintLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                    )
                    .apply {
                        topToTop = mainSlider.id
                        bottomToBottom = mainSlider.id
                        topMargin = -floatingSliderVerticalPadding
                        bottomMargin = -floatingSliderVerticalPadding
                        endToStart = background.id
                        marginEnd = (PILL_GAP_DP * density).toInt()
                    }
            host.addView(pillsContainer, lp)

            val floatingListener =
                View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    val anchor =
                        if (floatingSliders.childCount > 0 && floatingSliders.isShown) {
                            floatingSliders.id
                        } else {
                            background.id
                        }
                    val params = pillsContainer.layoutParams as ConstraintLayout.LayoutParams
                    if (params.endToStart != anchor) {
                        params.endToStart = anchor
                        pillsContainer.layoutParams = params
                    }
                }
            floatingSliders.addOnLayoutChangeListener(floatingListener)
        }

        launchTraced("VDAVBVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(appVolumeButton, pillsContainer)
        }

        viewModel.isVisible
            .onEach { isVisible ->
                appVolumeButton.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
            .launchInTraced("VDAVBVB#isVisible", this)

        fun createPill(app: AppVolumeModel): PillHolder {
            val model = mutableStateOf(app)
            val compose =
                ComposeView(context).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                    )
                    setContent {
                        PlatformTheme {
                            AppVolumeSlider(
                                app = model.value,
                                onVolumeChanged = { viewModel.setAppVolume(model.value.packageName, it) },
                                onInteraction = viewModel::onUserInteraction,
                            )
                        }
                    }
                }
            val frame =
                FrameLayout(context).apply {
                    setPadding(
                        floatingSliderHorizontalPadding,
                        floatingSliderVerticalPadding,
                        floatingSliderHorizontalPadding,
                        floatingSliderVerticalPadding,
                    )
                    addView(
                        compose,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            val params =
                LinearLayout.LayoutParams(
                        sliderWidth + 2 * floatingSliderHorizontalPadding,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    .apply {
                        if (pillsContainer.childCount > 0) {
                            marginStart = resources.getDimensionPixelSize(
                                R.dimen.volume_dialog_floating_sliders_spacing
                            )
                        }
                    }
            pillsContainer.addView(frame, params)
            frame.background = createPillBackground(frame, blurSupported)
            return PillHolder(frame, model)
        }

        fun showPills() {
            pillsContainer.animate().cancel()
            if (pillsContainer.visibility != View.VISIBLE) {
                pillsContainer.visibility = View.VISIBLE
                pillsContainer.translationX = -12f * density
            }
            pillsContainer
                .animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(ANIMATION_MS)
                .start()
        }

        fun hidePills() {
            if (holders.isEmpty() && pillsContainer.visibility != View.VISIBLE) return
            pillsContainer.animate().cancel()
            pillsContainer
                .animate()
                .alpha(0f)
                .setDuration(ANIMATION_MS)
                .withEndAction {
                    holders.clear()
                    pillsContainer.removeAllViews()
                    pillsContainer.visibility = View.INVISIBLE
                }
                .start()
        }

        combine(viewModel.isExpanded, viewModel.activeAppVolumes) { expanded, apps ->
                expanded to apps
            }
            .onEach { (expanded, apps) ->
                if (host == null || !expanded || apps.isEmpty()) {
                    hidePills()
                    return@onEach
                }
                val activePackages = apps.map { it.packageName }.toSet()
                val iterator = holders.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key !in activePackages) {
                        pillsContainer.removeView(entry.value.frame)
                        iterator.remove()
                    }
                }
                for (app in apps) {
                    val holder = holders[app.packageName]
                    if (holder == null) {
                        holders[app.packageName] = createPill(app)
                    } else {
                        holder.model.value = app
                    }
                }
                showPills()
            }
            .launchInTraced("VDAVBVB#pills", this)

        launchTraced("VDAVBVB#blur") {
            windowRootViewBlurInteractor.isBlurCurrentlySupported.collect { supported ->
                blurSupported = supported
                holders.values.forEach { it.frame.applyBlurSupport(supported) }
            }
        }

        appVolumeButton.setColorFilter(
            appVolumeButton.context.getColor(com.android.internal.R.color.materialColorPrimary)
        )

        appVolumeButton.setOnClickListener {
            viewModel.onButtonClicked()
            Events.writeEvent(Events.EVENT_SETTINGS_CLICK)
        }
    }

    private fun createPillBackground(view: View, blurSupported: Boolean): Drawable {
        val radius =
            view.resources
                .getDimensionPixelSize(R.dimen.volume_dialog_background_corner_radius)
                .toFloat()
        val surface = GradientDrawable().apply { cornerRadius = radius }
        val viewRoot = view.viewRootImpl
        val background: Drawable =
            if (isVolumeAndPowerBlurEnabled() && viewRoot != null) {
                val blur = viewRoot.createBackgroundBlurDrawable()
                blur.setCornerRadius(radius)
                blur.setBlurRadius(0)
                LayerDrawable(arrayOf<Drawable>(blur, surface))
            } else {
                surface
            }
        view.background = background
        view.applyBlurSupport(blurSupported)
        return background
    }

    private fun View.applyBlurSupport(supported: Boolean) {
        val blurOn = supported && isVolumeAndPowerBlurEnabled()
        val color =
            context.getColor(
                if (blurOn) {
                    R.color.volume_dialog_view_background_blur
                } else {
                    R.color.volume_dialog_view_background_blur_fallback
                }
            )
        when (val bg = background) {
            is LayerDrawable -> {
                (bg.getDrawable(0) as BackgroundBlurDrawable).setBlurRadius(
                    if (blurOn) {
                        resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_background_surface_blur_radius
                        )
                    } else {
                        0
                    }
                )
                (bg.getDrawable(1) as GradientDrawable).setColor(color)
            }
            is GradientDrawable -> bg.setColor(color)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppVolumeSlider(
    app: AppVolumeModel,
    onVolumeChanged: (Float) -> Unit,
    onInteraction: () -> Unit,
) {
    val dimensions = VolumeSliderDimensions.Vertical
    val colors =
        SliderDefaults.colors(
            activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            if (it is DragInteraction.Start) onInteraction()
        }
    }

    val isMutedOrZero = app.isMuted || app.volume <= 0f
    val appIcon: @Composable () -> Unit = {
        val icon = app.icon
        if (icon != null) {
            val bitmap = remember(icon) { icon.toBitmap(APP_ICON_BITMAP_PX, APP_ICON_BITMAP_PX).asImageBitmap() }
            val filter =
                remember(isMutedOrZero) {
                    if (isMutedOrZero) {
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                    } else {
                        null
                    }
                }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                colorFilter = filter,
                modifier = Modifier.size(APP_ICON_SIZE_DP.dp).clip(CircleShape),
            )
        }
    }

    Slider(
        value = app.volume.coerceIn(0f, 1f),
        valueRange = 0f..1f,
        onValueChanged = {
            onInteraction()
            onVolumeChanged(it)
        },
        onValueChangeFinished = null,
        isEnabled = true,
        isReverseDirection = true,
        isVertical = true,
        colors = colors,
        interactionSource = interactionSource,
        haptics = Haptics.Disabled,
        stepDistance = 0.05f,
        track = { sliderState ->
            SliderTrack(
                sliderState,
                colors = colors,
                isEnabled = true,
                isVertical = true,
                activeTrackEndIcon = { iconsState ->
                    SliderIcon(
                        icon = { appIcon() },
                        isVisible = !iconsState.isInactiveTrackEndIconVisible,
                    )
                },
                inactiveTrackEndIcon = { iconsState ->
                    SliderIcon(
                        icon = { appIcon() },
                        isVisible = iconsState.isInactiveTrackEndIconVisible,
                    )
                },
                trackSize = dimensions.trackSize,
            )
        },
        thumb = { sliderState, interactions ->
            SliderDefaults.Thumb(
                sliderState = sliderState,
                interactionSource = interactions,
                enabled = true,
                colors = colors,
                thumbSize = DpSize(dimensions.thumbWidth, dimensions.thumbHeight),
            )
        },
        accessibilityParams = AccessibilityParams(contentDescription = app.label),
        modifier = Modifier.fillMaxSize(),
    )
}
