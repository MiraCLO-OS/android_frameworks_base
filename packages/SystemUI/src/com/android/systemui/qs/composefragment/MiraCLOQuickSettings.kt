/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import com.android.systemui.miraclo.LocalMiraCLOGlow
import com.android.systemui.miraclo.miracloCornerGlow
import com.android.systemui.miraclo.LocalMiraCLORefraction
import com.android.systemui.miraclo.miracloRefraction
import android.media.AudioManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalMiraCLOLiquidGlass
import com.android.systemui.qs.panels.ui.compose.infinitegrid.MiraCLOLiquidHighlight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.MiraCLOLiquidRim
import com.android.systemui.qs.panels.ui.compose.infinitegrid.MiraCLOSolidTile
import com.android.systemui.qs.panels.ui.compose.infinitegrid.MiraCLOColorOSGap
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.res.R
import kotlinx.coroutines.launch

/** Tile specs that MiraCLO pins to the fixed wide slots, in order of preference. */
private val MIRACLO_WIFI_SPECS = listOf("internet", "wifi")
private val MIRACLO_DATA_SPECS = listOf("cell", "mobile_data", "mobiledata")
private val MIRACLO_BT_SPECS = listOf("bt", "bluetooth")

private val MiraCLOGap = MiraCLOColorOSGap

/** Sun-icon colour at full brightness (the orange of the 🔆 emoji). */
private val MiraCLOBrightnessFull = Color(0xFFFF9F0A)

/** Slider position treated as "full" (allows for rounding of the brightness value). */
private const val MIRACLO_FULL_THRESHOLD = 0.995f

/** Matches the media card's own corner radius (Media.kt clips cards to 32dp). */
internal val MiraCLOMediaShape = RoundedCornerShape(32.dp)

/**
 * MiraCLO (ColorOS-style) expanded Quick Settings.
 *
 * ```
 * [ Wi-Fi         ][ Mobile data   ]
 * [ Media        ][ ☀ ][ 🔊 ]          tall brightness + media volume sliders
 * [ Bluetooth     ][ 🔔  📳  🔕     ]   ringer switch
 * ( ) ( ) ( ) ( )                       every other tile: round, label below
 * ```
 *
 * The fixed tiles are the real tile view models, so they behave exactly like their stock
 * versions. Anything missing on a device (e.g. no mobile data tile) simply leaves its slot out.
 */
@Composable
internal fun ContentScope.MiraCLOQuickSettingsLayout(
    tiles: List<TileViewModel>,
    gridLayout: GridLayout,
    infiniteGridLayout: InfiniteGridLayout,
    brightnessViewModel: BrightnessSliderViewModel,
    audioVolumeInteractor: AudioVolumeInteractor,
    showMedia: Boolean,
    media: @Composable (Modifier) -> Unit,
    mediaHost: @Composable (Modifier) -> Unit = {},
    listening: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    fun pick(specs: List<String>): TileViewModel? =
        specs.firstNotNullOfOrNull { spec -> tiles.firstOrNull { it.spec.spec == spec } }

    val wifi = pick(MIRACLO_WIFI_SPECS)
    val data = pick(MIRACLO_DATA_SPECS)
    val bt = pick(MIRACLO_BT_SPECS)
    val fixed = listOfNotNull(wifi, data, bt)
    val rest = remember(tiles, fixed) { tiles.filterNot { it in fixed } }

    // The fixed tiles are not part of a grid, so make them listen ourselves.
    TileListener(fixed, listening)

    // Follows the ColorOS tile height (66dp) set in CommonTileDefaults for this style.
    val tileHeight = CommonTileDefaults.TileHeight
    val glass = miracloGlass()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = spacedBy(MiraCLOGap)) {
        // Row 1: Wi-Fi + mobile data
        if (wifi != null || data != null) {
            Row(horizontalArrangement = spacedBy(MiraCLOGap)) {
                listOfNotNull(wifi, data).forEach { tile ->
                    with(infiniteGridLayout) {
                        MiraCLOWideTile(tile, listening, Modifier.weight(1f))
                    }
                }
                if (wifi == null || data == null) Box(Modifier.weight(1f))
            }
        }

        // Row 2: media + tall brightness + tall volume
        Row(
            horizontalArrangement = spacedBy(MiraCLOGap),
            modifier = Modifier.height(tileHeight * 2 + MiraCLOGap),
        ) {
            Box(
                modifier =
                    Modifier.weight(2f)
                        .fillMaxHeight()
                        // While media plays, the player card is the whole slot: no glass behind
                        // it, same corner radius as the card (32dp) so there is a single outline.
                        .then(
                            if (showMedia) Modifier.clip(MiraCLOMediaShape)
                            else Modifier.glassSurface(glass, MiraCLOMediaShape)
                        )
                        // MiraCLO Liquid Glass glow: a faint light in the card's bottom corner.
                        .miracloCornerGlow(glass.liquid && LocalMiraCLOGlow.current),
                contentAlignment = Alignment.Center,
            ) {
                mediaHost(Modifier.matchParentSize())
                if (showMedia) {
                    media(Modifier.fillMaxSize())
                } else {
                    Text(
                        text = "No media playing",
                        color = glass.secondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            MiraCLOBrightnessSlider(
                viewModel = brightnessViewModel,
                glass = glass,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            MiraCLOVolumeSlider(
                audioVolumeInteractor = audioVolumeInteractor,
                glass = glass,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        // Row 3: Bluetooth + ringer switch
        Row(horizontalArrangement = spacedBy(MiraCLOGap)) {
            if (bt != null) {
                with(infiniteGridLayout) { MiraCLOWideTile(bt, listening, Modifier.weight(1f)) }
            } else {
                Box(Modifier.weight(1f))
            }
            MiraCLORingerSwitch(
                audioVolumeInteractor = audioVolumeInteractor,
                glass = glass,
                height = tileHeight,
                modifier = Modifier.weight(1f),
            )
        }

        // Everything else: round icon tiles with labels (the grid handles paging + listening)
        if (rest.isNotEmpty()) {
            with(gridLayout) {
                TileGrid(
                    tiles = rest,
                    modifier = Modifier.fillMaxWidth(),
                    listening = listening,
                    enableRevealEffect = false,
                )
            }
        }
    }
}

/** Colours shared by the MiraCLO glass cards, matching the tile glass in Tile.kt. */
internal data class MiraCLOGlass(
    val fill: Color,
    val iconOff: Color,
    val accent: Color,
    val secondaryText: Color,
    val dark: Boolean,
    val liquid: Boolean,
)

@Composable
internal fun miracloGlass(): MiraCLOGlass {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = Color(0xFF1677FF)
    val liquid = LocalMiraCLOLiquidGlass.current
    return MiraCLOGlass(
        fill =
            if (dark) (if (liquid) Color.White.copy(alpha = 0.07f) else MiraCLOSolidTile)
            else Color.White.copy(alpha = if (liquid) 0.30f else 0.55f),
        iconOff = onSurface,
        accent = accent,
        secondaryText = onSurface.copy(alpha = 0.70f),
        dark = dark,
        liquid = liquid,
    )
}

/** The glass rim. Created once: building a new Brush per frame showed up during QS expansion. */
private val MiraCLOGlassRim =
    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.04f)))

@Composable
internal fun Modifier.glassSurface(glass: MiraCLOGlass, shape: Shape): Modifier =
    if (glass.liquid && LocalMiraCLORefraction.current) {
        // SurfaceFlinger draws the refracting glass and its rim; only the tint is drawn here.
        this.miracloRefraction(shape, true).clip(shape).background(glass.fill, shape)
    } else if (glass.liquid) {
        this.clip(shape)
            .background(glass.fill, shape)
            .background(MiraCLOLiquidHighlight, shape)
            .border(1.3.dp, MiraCLOLiquidRim, shape)
    } else if (glass.dark) {
        // Dark, no Liquid Glass: flat solid surface, no rim.
        this.clip(shape).background(glass.fill, shape)
    } else {
        this.clip(shape).background(glass.fill, shape).border(1.dp, MiraCLOGlassRim, shape)
    }

/**
 * A tall pill slider: the white fill rises from the bottom. Dragging is relative to where the
 * finger lands, like ColorOS. While dragging, the shade is asked not to steal the gesture.
 */
@Composable
private fun MiraCLOVerticalSlider(
    fraction: Float,
    @DrawableRes iconRes: Int,
    iconTint: Color,
    label: String,
    glass: MiraCLOGlass,
    onChange: (Float) -> Unit,
    onStop: (Float) -> Unit,
    modifier: Modifier = Modifier,
    fullIconTint: Color? = null,
    vertical: Boolean = true,
) {
    val view = LocalView.current
    var local by remember { mutableFloatStateOf(fraction) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(fraction, dragging) { if (!dragging) local = fraction }
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnStop by rememberUpdatedState(onStop)
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier =
            modifier
                .glassSurface(glass, shape)
                .semantics { contentDescription = label }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        dragging = true
                        down.consume()
                        val start = local
                        // MiraCLO: tall sliders fill upwards, wide ones (4x1) left to right.
                        val length =
                            (if (vertical) size.height else size.width).coerceAtLeast(1)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta =
                                if (vertical) (down.position.y - change.position.y) / length
                                else (change.position.x - down.position.x) / length
                            local = (start + delta).coerceIn(0f, 1f)
                            currentOnChange(local)
                            change.consume()
                        }
                        currentOnStop(local)
                        dragging = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
    ) {
        Box(
            modifier =
                if (vertical) {
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(local.coerceIn(0.001f, 1f))
                        .background(Color.White)
                } else {
                    Modifier.align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(local.coerceIn(0.001f, 1f))
                        .background(Color.White)
                }
        )
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint =
                when {
                    fullIconTint != null && local >= MIRACLO_FULL_THRESHOLD -> fullIconTint
                    local > 0.18f -> iconTint
                    else -> glass.iconOff
                },
            modifier =
                if (vertical) {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).size(26.dp)
                } else {
                    Modifier.align(Alignment.CenterStart).padding(start = 20.dp).size(24.dp)
                },
        )
    }
}

@Composable
internal fun MiraCLOBrightnessSlider(
    viewModel: BrightnessSliderViewModel,
    glass: MiraCLOGlass,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
) {
    val gamma = viewModel.currentBrightness.value
    val min = viewModel.minBrightness.value
    val max = viewModel.maxBrightness.value
    val scope = rememberCoroutineScope()
    val span = (max - min).coerceAtLeast(1)
    val fraction =
        if (gamma < 0) 0.5f else ((gamma - min).toFloat() / span).coerceIn(0f, 1f)
    fun toGamma(f: Float) = GammaBrightness((min + f * span).toInt().coerceIn(min, max))

    MiraCLOVerticalSlider(
        fraction = fraction,
        iconRes = BrightnessSliderViewModel.getIconForPercentage(fraction * 100f),
        iconTint = Color(0xFF1A1A1A),
        // At full brightness only the sun icon turns orange, like the 🔆 emoji.
        fullIconTint = MiraCLOBrightnessFull,
        label = "Brightness",
        glass = glass,
        onChange = { f -> scope.launch { viewModel.onDrag(Drag.Dragging(toGamma(f))) } },
        onStop = { f -> scope.launch { viewModel.onDrag(Drag.Stopped(toGamma(f))) } },
        modifier = modifier,
        vertical = vertical,
    )
}

@Composable
internal fun MiraCLOVolumeSlider(
    audioVolumeInteractor: AudioVolumeInteractor,
    glass: MiraCLOGlass,
    modifier: Modifier = Modifier,
    vertical: Boolean = true,
) {
    val stream = remember { AudioStream(AudioManager.STREAM_MUSIC) }
    val model by
        remember(audioVolumeInteractor) { audioVolumeInteractor.getAudioStream(stream) }
            .collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val current = model
    val min = current?.minVolume ?: 0
    val max = current?.maxVolume ?: 1
    val span = (max - min).coerceAtLeast(1)
    val fraction = current?.let { ((it.volume - min).toFloat() / span).coerceIn(0f, 1f) } ?: 0f
    fun toVolume(f: Float) = (min + Math.round(f * span)).coerceIn(min, max)

    MiraCLOVerticalSlider(
        fraction = fraction,
        iconRes =
            if (current?.isMuted == true || fraction == 0f) R.drawable.ic_volume_media_mute
            else R.drawable.ic_volume_media,
        iconTint = glass.accent,
        label = "Media volume",
        glass = glass,
        onChange = { f -> scope.launch { audioVolumeInteractor.setVolume(stream, toVolume(f)) } },
        onStop = { f -> scope.launch { audioVolumeInteractor.setVolume(stream, toVolume(f)) } },
        modifier = modifier,
        vertical = vertical,
    )
}

/**
 * ColorOS-style colour for each sound mode: ring = blue, vibrate = orange, silent = red.
 * Light panel: coloured bubble with a white icon. Dark panel: white bubble with a coloured icon,
 * the same pairing the active QS tiles use.
 */
internal fun miracloRingerColor(mode: Int): Color =
    when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> Color(0xFF1677FF)
        AudioManager.RINGER_MODE_VIBRATE -> Color(0xFFFF8A00)
        else -> Color(0xFFF2453D)
    }

@Composable
internal fun MiraCLORingerSwitch(
    audioVolumeInteractor: AudioVolumeInteractor,
    glass: MiraCLOGlass,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val ringerMode by audioVolumeInteractor.ringerMode.collectAsStateWithLifecycle()
    val modes =
        listOf(
            Triple(AudioManager.RINGER_MODE_NORMAL, R.drawable.ic_volume_ringer, "Ring"),
            Triple(AudioManager.RINGER_MODE_VIBRATE, R.drawable.ic_volume_ringer_vibrate, "Vibrate"),
            Triple(AudioManager.RINGER_MODE_SILENT, R.drawable.ic_volume_ringer_mute, "Silent"),
        )
    val shape = RoundedCornerShape(percent = 50)
    val bubble = height - 18.dp

    Row(
        modifier =
            modifier.height(height).glassSurface(glass, shape).padding(horizontal = 9.dp),
        horizontalArrangement = spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { (mode, icon, label) ->
            val selected = ringerMode.value == mode
            val modeColor = miracloRingerColor(mode)
            Box(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier.size(bubble)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !selected -> Color.Transparent
                                    glass.dark -> Color.White
                                    else -> modeColor
                                }
                            )
                            .semantics { contentDescription = label }
                            .pointerInput(mode) {
                                awaitEachGesture {
                                    awaitFirstDown().consume()
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        up.consume()
                                        audioManager?.setRingerModeInternal(mode)
                                    }
                                }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint =
                            when {
                                !selected -> glass.iconOff
                                glass.dark -> modeColor
                                else -> Color.White
                            },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
