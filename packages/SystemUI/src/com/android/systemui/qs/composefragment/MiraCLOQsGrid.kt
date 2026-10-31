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

import android.media.AudioManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.miraclo.LocalMiraCLOGlow
import com.android.systemui.miraclo.LocalMiraCLORefraction
import com.android.systemui.miraclo.miracloCornerGlow
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalMiraCLOHideTileLabels
import com.android.systemui.qs.panels.ui.compose.infinitegrid.MiraCLOColorOSGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.MiraCLOLook
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.res.R

/**
 * MiraCLO editable Quick Settings.
 *
 * Every control sits on a 4-column grid and has a size in cells: tiles are round (1x1) or wide
 * (2x1), and the music card, brightness, volume, ringer switch and connectivity block have a
 * few sizes each. Controls are packed in order, each into the first free spot that fits, so a
 * round tile fills the hole next to a tall slider, as on iOS. The layout of each look is kept
 * in Settings.Secure "miraclo_qs_layout_<look>"; tiles added later in the stock tile editor are
 * appended as round tiles.
 */
internal data class MiraCLOControl(val kind: String, val spec: String = "", val w: Int, val h: Int) {
    val key: String
        get() = if (kind == KIND_TILE) "tile/$spec" else kind

    companion object {
        const val KIND_TILE = "tile"
        const val KIND_MUSIC = "music"
        const val KIND_BRIGHTNESS = "bright"
        const val KIND_VOLUME = "vol"
        const val KIND_RINGER = "ringer"
        const val KIND_CONNECTIVITY = "conn"

        /** Controls that are not tiles, in the order the "Add a control" list shows them. */
        val WIDGETS =
            listOf(KIND_CONNECTIVITY, KIND_MUSIC, KIND_BRIGHTNESS, KIND_VOLUME, KIND_RINGER)

        /** Sizes each kind can take (width x height in cells); the first is the default. */
        fun sizes(kind: String): List<Pair<Int, Int>> =
            when (kind) {
                KIND_MUSIC -> listOf(2 to 2, 4 to 2)
                KIND_BRIGHTNESS, KIND_VOLUME -> listOf(1 to 2, 4 to 1)
                KIND_RINGER -> listOf(2 to 1, 1 to 1)
                KIND_CONNECTIVITY -> listOf(2 to 2, 4 to 1)
                else -> listOf(1 to 1, 2 to 1)
            }

        fun label(kind: String): String =
            when (kind) {
                KIND_MUSIC -> "Music"
                KIND_BRIGHTNESS -> "Brightness"
                KIND_VOLUME -> "Volume"
                KIND_RINGER -> "Ringer switch"
                KIND_CONNECTIVITY -> "Connectivity"
                else -> kind
            }
    }
}

/** Saved layouts: "music@2x2,tile/internet@2x1,...", with "!spec" for tiles that were removed. */
internal object MiraCLOQsLayouts {
    const val KEY_PREFIX = "miraclo_qs_layout_"

    fun key(look: Int) = KEY_PREFIX + look

    private fun c(kind: String, w: Int, h: Int) = MiraCLOControl(kind, "", w, h)

    private fun t(spec: String, w: Int = 1) = MiraCLOControl(MiraCLOControl.KIND_TILE, spec, w, 1)

    /** The starting layout of each look, before any editing. */
    fun defaults(look: Int): List<MiraCLOControl> =
        when (look) {
            MiraCLOLook.IOS ->
                listOf(
                    c(MiraCLOControl.KIND_CONNECTIVITY, 2, 2),
                    c(MiraCLOControl.KIND_MUSIC, 2, 2),
                    t("rotation"),
                    t("dnd"),
                    c(MiraCLOControl.KIND_BRIGHTNESS, 1, 2),
                    c(MiraCLOControl.KIND_VOLUME, 1, 2),
                    t("flashlight"),
                    t("screenrecord"),
                )
            MiraCLOLook.NOTHING ->
                listOf(
                    t("internet", 2),
                    t("bt", 2),
                    c(MiraCLOControl.KIND_BRIGHTNESS, 4, 1),
                    t("cell"),
                    t("flashlight"),
                    t("dnd"),
                    t("rotation"),
                    c(MiraCLOControl.KIND_MUSIC, 4, 2),
                )
            MiraCLOLook.ONEUI ->
                listOf(
                    t("internet", 2),
                    t("bt", 2),
                    t("cell"),
                    t("flashlight"),
                    t("dnd"),
                    t("rotation"),
                    c(MiraCLOControl.KIND_BRIGHTNESS, 4, 1),
                    c(MiraCLOControl.KIND_MUSIC, 4, 2),
                )
            // ColorOS: the classic layout, as a grid.
            else ->
                listOf(
                    t("internet", 2),
                    t("cell", 2),
                    c(MiraCLOControl.KIND_MUSIC, 2, 2),
                    c(MiraCLOControl.KIND_BRIGHTNESS, 1, 2),
                    c(MiraCLOControl.KIND_VOLUME, 1, 2),
                    t("bt", 2),
                    c(MiraCLOControl.KIND_RINGER, 2, 1),
                )
        }

    /** Parses a saved layout, or returns null for "not edited". */
    fun parse(value: String?): Pair<List<MiraCLOControl>, Set<String>>? {
        if (value.isNullOrBlank()) return null
        val items = ArrayList<MiraCLOControl>()
        val hidden = HashSet<String>()
        for (raw in value.split(',')) {
            val token = raw.trim()
            if (token.isEmpty()) continue
            if (token.startsWith("!")) {
                hidden += token.substring(1)
                continue
            }
            val at = token.lastIndexOf('@')
            if (at <= 0) continue
            val name = token.substring(0, at)
            val size = token.substring(at + 1).split('x')
            val w = size.getOrNull(0)?.toIntOrNull() ?: continue
            val h = size.getOrNull(1)?.toIntOrNull() ?: continue
            val control =
                if (name.startsWith("tile/")) {
                    MiraCLOControl(MiraCLOControl.KIND_TILE, name.substring(5), w, h)
                } else {
                    MiraCLOControl(name, "", w, h)
                }
            // Only sizes the control supports, so a bad value can never break the layout.
            if ((control.w to control.h) !in MiraCLOControl.sizes(control.kind)) continue
            if (items.none { it.key == control.key }) items += control
        }
        return items to hidden
    }

    fun serialize(items: List<MiraCLOControl>, hidden: Set<String>): String =
        (items.map { "${it.key}@${it.w}x${it.h}" } + hidden.sorted().map { "!$it" })
            .joinToString(",")
}

/** Tile specs that mean the same thing on different builds. */
private val MIRACLO_TILE_ALIASES =
    listOf(
        listOf("internet", "wifi"),
        listOf("cell", "mobile_data", "mobiledata"),
        listOf("bt", "bluetooth"),
        listOf("screenrecord", "record"),
        listOf("dnd", "modes"),
    )

private fun aliasesOf(spec: String): List<String> =
    MIRACLO_TILE_ALIASES.firstOrNull { spec in it }?.let { listOf(spec) + (it - spec) }
        ?: listOf(spec)

private fun List<TileViewModel>.findTile(spec: String): TileViewModel? =
    aliasesOf(spec).firstNotNullOfOrNull { s -> firstOrNull { it.spec.spec == s } }

/** The four tiles of the connectivity block. */
private fun connectivityTiles(tiles: List<TileViewModel>): List<TileViewModel> =
    listOfNotNull(
        tiles.findTile("internet"),
        tiles.findTile("cell"),
        tiles.findTile("bt"),
        tiles.findTile("airplane") ?: tiles.findTile("hotspot"),
    )

/**
 * The controls to show: the layout's controls whose tiles exist, then every other tile the
 * user has (minus the removed ones) as round tiles. Tile controls are renamed to the spec the
 * device really uses, so saving keeps working across builds.
 */
internal fun miracloResolve(
    layout: List<MiraCLOControl>,
    hidden: Set<String>,
    tiles: List<TileViewModel>,
): List<MiraCLOControl> {
    val out = ArrayList<MiraCLOControl>()
    val used = HashSet<String>()
    if (layout.any { it.kind == MiraCLOControl.KIND_CONNECTIVITY }) {
        connectivityTiles(tiles).forEach { used += it.spec.spec }
    }
    for (control in layout) {
        if (control.kind == MiraCLOControl.KIND_TILE) {
            val tile = tiles.findTile(control.spec) ?: continue
            val spec = tile.spec.spec
            if (spec in used || spec in hidden) continue
            used += spec
            out += control.copy(spec = spec)
        } else if (control.kind in MiraCLOControl.WIDGETS && out.none { it.key == control.key }) {
            out += control
        }
    }
    for (tile in tiles) {
        val spec = tile.spec.spec
        if (spec in used || spec in hidden) continue
        used += spec
        out += MiraCLOControl(MiraCLOControl.KIND_TILE, spec, 1, 1)
    }
    return out
}

/** Where each control lands on the grid: column, row, width and height in cells. */
private data class MiraCLOCell(val col: Int, val row: Int, val w: Int, val h: Int)

private const val MIRACLO_COLUMNS = 4

private fun miracloPack(items: List<MiraCLOControl>): List<MiraCLOCell> {
    val rows = ArrayList<BooleanArray>()
    fun taken(r: Int, c: Int) = r < rows.size && rows[r][c]
    fun fits(r: Int, c: Int, w: Int, h: Int): Boolean {
        for (rr in r until r + h) for (cc in c until c + w) if (taken(rr, cc)) return false
        return true
    }
    return items.map { item ->
        val w = item.w.coerceIn(1, MIRACLO_COLUMNS)
        val h = item.h.coerceAtLeast(1)
        var r = 0
        var placed: MiraCLOCell? = null
        while (placed == null) {
            for (c in 0..(MIRACLO_COLUMNS - w)) {
                if (fits(r, c, w, h)) {
                    placed = MiraCLOCell(c, r, w, h)
                    break
                }
            }
            if (placed == null) r++
        }
        while (rows.size < placed.row + h) rows += BooleanArray(MIRACLO_COLUMNS)
        for (rr in placed.row until placed.row + h) {
            for (cc in placed.col until placed.col + w) rows[rr][cc] = true
        }
        placed
    }
}

/** Last measured position of each control, read by the drag handlers in edit mode. */
private class MiraCLOGeometry {
    var rects: Map<String, Pair<IntOffset, IntSize>> = emptyMap()

    fun keyAt(point: Offset, except: String): String? =
        rects.entries
            .firstOrNull { (key, rect) ->
                val (pos, size) = rect
                key != except &&
                    point.x >= pos.x + size.width * 0.15f &&
                    point.x <= pos.x + size.width * 0.85f &&
                    point.y >= pos.y + size.height * 0.15f &&
                    point.y <= pos.y + size.height * 0.85f
            }
            ?.key
}

/** Edit mode state: the working copy of the layout and the control being dragged. */
internal class MiraCLOQsEditor {
    var editing by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var items by mutableStateOf<List<MiraCLOControl>>(emptyList())
    var hidden by mutableStateOf<Set<String>>(emptySet())
    var dragKey by mutableStateOf<String?>(null)

    /** Top-left of the dragged control, in grid pixels. */
    var dragPos by mutableStateOf(Offset.Zero)

    fun start(current: List<MiraCLOControl>, removed: Set<String>) {
        items = current
        hidden = removed
        adding = false
        dragKey = null
        editing = true
    }

    fun remove(control: MiraCLOControl) {
        items = items.filterNot { it.key == control.key }
        if (control.kind == MiraCLOControl.KIND_TILE) hidden = hidden + control.spec
    }

    fun resize(control: MiraCLOControl) {
        val sizes = MiraCLOControl.sizes(control.kind)
        val next = sizes[(sizes.indexOf(control.w to control.h) + 1) % sizes.size]
        items = items.map { if (it.key == control.key) it.copy(w = next.first, h = next.second) else it }
    }

    fun move(key: String, toKey: String) {
        val from = items.indexOfFirst { it.key == key }
        val to = items.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0 || from == to) return
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        items = list
    }

    fun addWidget(kind: String) {
        val (w, h) = MiraCLOControl.sizes(kind).first()
        items = items + MiraCLOControl(kind, "", w, h)
    }

    fun addTile(spec: String) {
        hidden = hidden - spec
        items = items + MiraCLOControl(MiraCLOControl.KIND_TILE, spec, 1, 1)
    }
}

/** Corner radius of the music card and the connectivity block in each look. */
private fun miracloCardShape(look: Int): Shape =
    when (look) {
        MiraCLOLook.ONEUI -> RoundedCornerShape(26.dp)
        MiraCLOLook.NOTHING -> RoundedCornerShape(28.dp)
        else -> MiraCLOMediaShape
    }

/**
 * The editable grid of controls. [onSave] stores the edited layout, [onReset] goes back to the
 * look's starting layout.
 */
@Composable
internal fun ContentScope.MiraCLOQsGrid(
    look: Int,
    controls: List<MiraCLOControl>,
    hidden: Set<String>,
    editor: MiraCLOQsEditor,
    tiles: List<TileViewModel>,
    infiniteGridLayout: InfiniteGridLayout,
    brightnessViewModel: BrightnessSliderViewModel,
    audioVolumeInteractor: AudioVolumeInteractor,
    showMedia: Boolean,
    media: @Composable (Modifier) -> Unit,
    listening: () -> Boolean,
    onSave: (List<MiraCLOControl>, Set<String>) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = if (editor.editing) editor.items else controls
    val cells = remember(items) { miracloPack(items) }
    val geometry = remember { MiraCLOGeometry() }
    val glass = miracloGlass()
    val gap = MiraCLOColorOSGap
    val tileHeight = CommonTileDefaults.TileHeight
    // Round tiles carry their label underneath, except in the iOS look.
    val labelHeight = if (look == MiraCLOLook.IOS) 0.dp else 36.dp
    val connectivity = remember(tiles) { connectivityTiles(tiles) }

    val shown =
        remember(items, tiles, connectivity) {
            buildList {
                if (items.any { it.kind == MiraCLOControl.KIND_CONNECTIVITY }) addAll(connectivity)
                items.forEach { c ->
                    if (c.kind == MiraCLOControl.KIND_TILE) tiles.findTile(c.spec)?.let { add(it) }
                }
            }
        }
    TileListener(shown, listening)

    val wobble =
        if (editor.editing) {
            rememberInfiniteTransition(label = "MiraCLOQsWobble")
                .animateFloat(
                    initialValue = -1.1f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(tween(140), RepeatMode.Reverse),
                    label = "MiraCLOQsWobbleAngle",
                )
        } else {
            null
        }
    val currentItems by rememberUpdatedState(items)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = spacedBy(gap)) {
        if (editor.editing) MiraCLOEditBar(editor, onSave, onReset)

        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                items.forEachIndexed { index, control ->
                    key(control.key) {
                        val dragging = editor.dragKey == control.key
                        Box(
                            modifier =
                                Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                                    if (dragging) {
                                        val rect = geometry.rects[control.key]
                                        if (rect != null) {
                                            translationX = editor.dragPos.x - rect.first.x
                                            translationY = editor.dragPos.y - rect.first.y
                                        }
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    } else if (wobble != null) {
                                        rotationZ =
                                            if (index % 2 == 0) wobble.value else -wobble.value
                                    }
                                }
                        ) {
                            MiraCLOControlContent(
                                control = control,
                                look = look,
                                glass = glass,
                                tiles = tiles,
                                connectivity = connectivity,
                                infiniteGridLayout = infiniteGridLayout,
                                brightnessViewModel = brightnessViewModel,
                                audioVolumeInteractor = audioVolumeInteractor,
                                showMedia = showMedia,
                                media = media,
                                listening = listening,
                                height = tileHeight,
                            )
                            if (editor.editing) {
                                // Catches every touch so nothing toggles while editing.
                                Box(
                                    Modifier.matchParentSize()
                                        .pointerInput(control.key) { detectTapGestures {} }
                                        .pointerInput(control.key) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    val rect = geometry.rects[control.key]
                                                    editor.dragPos =
                                                        rect?.let {
                                                            Offset(
                                                                it.first.x.toFloat(),
                                                                it.first.y.toFloat(),
                                                            )
                                                        } ?: Offset.Zero
                                                    editor.dragKey = control.key
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    editor.dragPos += amount
                                                    val rect = geometry.rects[control.key]
                                                    if (rect != null) {
                                                        val center =
                                                            editor.dragPos +
                                                                Offset(
                                                                    rect.second.width / 2f,
                                                                    rect.second.height / 2f,
                                                                )
                                                        geometry.keyAt(center, control.key)?.let {
                                                            editor.move(control.key, it)
                                                        }
                                                    }
                                                },
                                                onDragEnd = { editor.dragKey = null },
                                                onDragCancel = { editor.dragKey = null },
                                            )
                                        }
                                )
                                MiraCLOEditBadge(
                                    text = "−",
                                    label = "Remove",
                                    background = Color(0xFF8E8E93),
                                    foreground = Color.White,
                                    modifier =
                                        Modifier.align(Alignment.TopStart).offset((-6).dp, (-6).dp),
                                ) {
                                    editor.remove(control)
                                }
                                if (MiraCLOControl.sizes(control.kind).size > 1) {
                                    MiraCLOEditBadge(
                                        text = "↔",
                                        label = "Resize",
                                        background = Color.White,
                                        foreground = Color.Black,
                                        modifier =
                                            Modifier.align(Alignment.BottomEnd)
                                                .offset(6.dp, 6.dp),
                                    ) {
                                        editor.resize(control)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val gapPx = gap.roundToPx()
            val cell = ((width - gapPx * (MIRACLO_COLUMNS - 1)) / MIRACLO_COLUMNS).coerceAtLeast(1)
            val base = tileHeight.roundToPx()
            val label = labelHeight.roundToPx()
            val rowCount = cells.maxOfOrNull { it.row + it.h } ?: 0
            // A row grows by the label height when it holds a round tile with a label.
            val rowHeights =
                IntArray(rowCount) { r ->
                    val labelled =
                        cells.indices.any { i ->
                            val c = cells[i]
                            c.row == r && c.w == 1 && c.h == 1 &&
                                currentItems.getOrNull(i)?.kind == MiraCLOControl.KIND_TILE
                        }
                    base + if (labelled) label else 0
                }
            val rowTops = IntArray(rowCount)
            for (r in 1 until rowCount) rowTops[r] = rowTops[r - 1] + rowHeights[r - 1] + gapPx
            val total = if (rowCount == 0) 0 else rowTops[rowCount - 1] + rowHeights[rowCount - 1]

            val rects = HashMap<String, Pair<IntOffset, IntSize>>()
            val placeables =
                measurables.mapIndexed { i, m ->
                    val c = cells[i]
                    val w = cell * c.w + gapPx * (c.w - 1)
                    var h = gapPx * (c.h - 1)
                    for (r in c.row until c.row + c.h) h += rowHeights[r]
                    val pos = IntOffset(c.col * (cell + gapPx), rowTops[c.row])
                    currentItems.getOrNull(i)?.let { rects[it.key] = pos to IntSize(w, h) }
                    m.measure(Constraints.fixed(w, h)) to pos
                }
            geometry.rects = rects
            layout(width, total) { placeables.forEach { (p, pos) -> p.place(pos) } }
        }

        if (editor.editing && editor.adding) {
            MiraCLOAddList(controls = items, hidden = editor.hidden, tiles = tiles, editor = editor)
        }
        if (!editor.editing) {
            Text(
                text = "Edit controls",
                color = glass.secondaryText,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(50))
                        .clickable { editor.start(controls, hidden) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MiraCLOEditBadge(
    text: String,
    label: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(background)
                .semantics { contentDescription = label }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiraCLOPill(text: String, strong: Boolean, onClick: () -> Unit) {
    val dark = miracloGlass().dark
    Text(
        text = text,
        color = if (strong) Color.White else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelLarge,
        modifier =
            Modifier.clip(RoundedCornerShape(50))
                .background(
                    when {
                        strong -> Color(0xFF0A84FF)
                        dark -> Color.White.copy(alpha = 0.14f)
                        else -> Color.Black.copy(alpha = 0.08f)
                    }
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun MiraCLOEditBar(
    editor: MiraCLOQsEditor,
    onSave: (List<MiraCLOControl>, Set<String>) -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = spacedBy(8.dp)) {
        Row(horizontalArrangement = spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MiraCLOPill(if (editor.adding) "Close" else "Add a control", strong = false) {
                editor.adding = !editor.adding
            }
            MiraCLOPill("Reset", strong = false) {
                editor.editing = false
                onReset()
            }
            Box(Modifier.weight(1f))
            MiraCLOPill("Done", strong = true) {
                onSave(editor.items, editor.hidden)
                editor.editing = false
            }
        }
        Text(
            text = "Touch and hold to move · ↔ to resize · − to remove",
            color = miracloGlass().secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Controls that are not on the panel: widgets not placed and tiles that were removed. */
@Composable
private fun MiraCLOAddList(
    controls: List<MiraCLOControl>,
    hidden: Set<String>,
    tiles: List<TileViewModel>,
    editor: MiraCLOQsEditor,
) {
    val widgets = MiraCLOControl.WIDGETS.filter { k -> controls.none { it.kind == k } }
    val removed = tiles.filter { it.spec.spec in hidden }
    val glass = miracloGlass()
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .glassSurface(glass, RoundedCornerShape(26.dp))
                .padding(16.dp),
        verticalArrangement = spacedBy(10.dp),
    ) {
        Text("Add a control", style = MaterialTheme.typography.titleMedium)
        if (widgets.isEmpty() && removed.isEmpty()) {
            Text(
                "Everything is already on the panel. New tiles can be added with the pencil " +
                    "button below.",
                color = glass.secondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        widgets.chunked(2).forEach { row ->
            Row(horizontalArrangement = spacedBy(8.dp)) {
                row.forEach { kind ->
                    MiraCLOPill("+ " + MiraCLOControl.label(kind), strong = false) {
                        editor.addWidget(kind)
                    }
                }
            }
        }
        removed.chunked(2).forEach { row ->
            Row(horizontalArrangement = spacedBy(8.dp)) {
                row.forEach { tile ->
                    val label = tile.currentState.label?.toString() ?: tile.spec.spec
                    MiraCLOPill("+ $label", strong = false) { editor.addTile(tile.spec.spec) }
                }
            }
        }
    }
}

@Composable
private fun ContentScope.MiraCLOControlContent(
    control: MiraCLOControl,
    look: Int,
    glass: MiraCLOGlass,
    tiles: List<TileViewModel>,
    connectivity: List<TileViewModel>,
    infiniteGridLayout: InfiniteGridLayout,
    brightnessViewModel: BrightnessSliderViewModel,
    audioVolumeInteractor: AudioVolumeInteractor,
    showMedia: Boolean,
    media: @Composable (Modifier) -> Unit,
    listening: () -> Boolean,
    height: androidx.compose.ui.unit.Dp,
) {
    when (control.kind) {
        MiraCLOControl.KIND_TILE -> {
            val tile = tiles.findTile(control.spec) ?: return
            with(infiniteGridLayout) {
                MiraCLOWideTile(
                    tile = tile,
                    listening = listening,
                    modifier = Modifier.fillMaxWidth(),
                    iconOnly = control.w == 1,
                )
            }
        }
        MiraCLOControl.KIND_MUSIC -> {
            val shape = miracloCardShape(look)
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .then(
                            if (showMedia) Modifier.clip(shape) else Modifier.glassSurface(glass, shape)
                        )
                        .miracloCornerGlow(glass.liquid && LocalMiraCLOGlow.current),
                contentAlignment = Alignment.Center,
            ) {
                if (showMedia) {
                    media(Modifier.fillMaxSize())
                } else {
                    Text(
                        text = if (look == MiraCLOLook.IOS) "Not Playing" else "No media playing",
                        color = glass.secondaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        MiraCLOControl.KIND_BRIGHTNESS ->
            MiraCLOBrightnessSlider(
                viewModel = brightnessViewModel,
                glass = glass,
                modifier = Modifier.fillMaxSize(),
                vertical = control.h > 1,
            )
        MiraCLOControl.KIND_VOLUME ->
            MiraCLOVolumeSlider(
                audioVolumeInteractor = audioVolumeInteractor,
                glass = glass,
                modifier = Modifier.fillMaxSize(),
                vertical = control.h > 1,
            )
        MiraCLOControl.KIND_RINGER ->
            if (control.w > 1) {
                MiraCLORingerSwitch(
                    audioVolumeInteractor = audioVolumeInteractor,
                    glass = glass,
                    height = height,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                MiraCLORingerButton(audioVolumeInteractor, glass, height)
            }
        MiraCLOControl.KIND_CONNECTIVITY ->
            MiraCLOConnectivity(
                look = look,
                glass = glass,
                tiles = connectivity,
                wide = control.h == 1,
                infiniteGridLayout = infiniteGridLayout,
                listening = listening,
                tileHeight = height,
            )
    }
}

/**
 * Wi-Fi, mobile data, Bluetooth and aeroplane mode (or hotspot) as round toggles on one glass
 * block, as on iOS. The block itself carries the glass and refraction; the toggles inside are
 * solid so the effect is not drawn twice.
 */
@Composable
private fun ContentScope.MiraCLOConnectivity(
    look: Int,
    glass: MiraCLOGlass,
    tiles: List<TileViewModel>,
    wide: Boolean,
    infiniteGridLayout: InfiniteGridLayout,
    listening: () -> Boolean,
    tileHeight: androidx.compose.ui.unit.Dp,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().glassSurface(glass, miracloCardShape(look)),
        contentAlignment = Alignment.Center,
    ) {
        val columns = if (wide) 4 else 2
        val rows = if (wide) 1 else 2
        val slot = minOf(maxWidth / columns, maxHeight / rows)
        val scale = ((slot - 12.dp) / tileHeight).coerceIn(0.5f, 1f)
        CompositionLocalProvider(
            LocalMiraCLOHideTileLabels provides true,
            LocalMiraCLORefraction provides false,
        ) {
            Column(Modifier.fillMaxSize()) {
                tiles.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        row.forEach { tile ->
                            Box(
                                Modifier.weight(1f).fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.requiredSize(tileHeight).graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                ) {
                                    with(infiniteGridLayout) {
                                        MiraCLOWideTile(
                                            tile = tile,
                                            listening = listening,
                                            iconOnly = true,
                                        )
                                    }
                                }
                            }
                        }
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** The ringer switch at 1x1: one round button that steps ring, vibrate, silent. */
@Composable
private fun MiraCLORingerButton(
    audioVolumeInteractor: AudioVolumeInteractor,
    glass: MiraCLOGlass,
    height: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val ringerMode by audioVolumeInteractor.ringerMode.collectAsStateWithLifecycle()
    val mode = ringerMode.value
    val next =
        when (mode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
    val icon =
        when (mode) {
            AudioManager.RINGER_MODE_NORMAL -> R.drawable.ic_volume_ringer
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
            else -> R.drawable.ic_volume_ringer_mute
        }
    val color = miracloRingerColor(mode)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier =
                Modifier.size(height)
                    .clip(CircleShape)
                    .background(if (glass.dark) Color.White else color)
                    .semantics { contentDescription = "Sound mode" }
                    .pointerInput(next) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                audioManager?.setRingerModeInternal(next)
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (glass.dark) color else Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
