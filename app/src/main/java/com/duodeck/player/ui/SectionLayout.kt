package com.duodeck.player.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duodeck.player.skin.Slot
import kotlin.math.roundToInt

/** The four movable sections of the player. */
enum class Section(val label: String) { MAIN("PLAYER"), EQ("EQUALIZER"), VIS("VISUALIZER"), PLAYLIST("PLAYLIST") }

/** Which sections are on which screen, top to bottom. panes[0] = first screen, panes[1] = second screen. */
data class SectionLayout(val panes: List<List<Section>>) {

    fun move(s: Section, pane: Int, index: Int): SectionLayout {
        val removed = panes.map { it.filter { x -> x != s } }
        val target = removed[pane].toMutableList()
        target.add(index.coerceIn(0, target.size), s)
        return SectionLayout(removed.mapIndexed { i, l -> if (i == pane) target else l })
    }

    /** Everything on screen 1 goes to screen 2 and the other way round. */
    fun swapped() = SectionLayout(listOf(panes[1], panes[0]))

    fun encode(): String = panes.joinToString("|") { p -> p.joinToString(",") { it.name } }

    companion object {
        val DEFAULT = SectionLayout(listOf(listOf(Section.MAIN, Section.EQ, Section.VIS), listOf(Section.PLAYLIST)))

        fun decode(s: String?): SectionLayout {
            if (s.isNullOrBlank()) return DEFAULT
            val parts = s.split("|")
            if (parts.size != 2) return DEFAULT
            val panes = parts.map { p -> p.split(",").mapNotNull { n -> Section.entries.firstOrNull { it.name == n } } }
            val all = panes.flatten()
            // every section exactly once, otherwise fall back
            if (all.size != Section.entries.size || all.toSet().size != Section.entries.size) return DEFAULT
            return SectionLayout(panes)
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE)
        fun load(ctx: Context) = decode(prefs(ctx).getString("sections", null))
        fun save(ctx: Context, l: SectionLayout) = prefs(ctx).edit().putString("sections", l.encode()).apply()
    }
}

/** Where a dragged section would land. */
data class DropTarget(val pane: Int, val index: Int, val line: Rect)

/**
 * Tracks a section being dragged by its title bar. Everything is measured relative to the
 * player area ([container]), so it also works when the screen is turned for laptop mode.
 */
class SectionDragState {
    var container: LayoutCoordinates? = null
    val titleCoords = HashMap<Section, LayoutCoordinates>()
    val sectionCoords = HashMap<Section, LayoutCoordinates>()
    /** 0 / 1 = the two screens when spanned; the single-screen column uses 0 only. */
    val paneCoords = arrayOfNulls<LayoutCoordinates>(2)
    var split = false

    var dragging by mutableStateOf<Section?>(null)
    var pointer by mutableStateOf<Offset?>(null)
    var target by mutableStateOf<DropTarget?>(null)

    private fun rectOf(c: LayoutCoordinates?): Rect? {
        val root = container ?: return null
        if (c == null || !c.isAttached || !root.isAttached) return null
        return root.localBoundingBoxOf(c, clipBounds = false)
    }

    fun toContainer(s: Section, local: Offset): Offset? {
        val root = container ?: return null
        val t = titleCoords[s] ?: return null
        if (!t.isAttached || !root.isAttached) return null
        return root.localPositionOf(t, local)
    }

    /** Works out the pane and position under the finger. [visible] = sections currently shown. */
    fun computeTarget(layout: SectionLayout, visible: (Section) -> Boolean, gapPx: Float): DropTarget? {
        val s = dragging ?: return null
        val p = pointer ?: return null
        if (split) {
            val rects = (0..1).map { rectOf(paneCoords[it]) }
            val pane = rects.indices.filter { rects[it] != null }.minByOrNull { i ->
                val r = rects[i]!!
                if (r.contains(p)) -1f else (r.center - p).getDistance()
            } ?: return null
            val paneRect = rects[pane]!!
            val list = layout.panes[pane].filter { it != s && visible(it) }
            val idx = list.count { (rectOf(sectionCoords[it])?.center?.y ?: Float.MAX_VALUE) < p.y }
            val y = lineY(list, idx, paneRect, gapPx)
            return DropTarget(pane, fullIndex(layout.panes[pane], s, list, idx), Rect(paneRect.left, y - gapPx / 2, paneRect.right, y + gapPx / 2))
        } else {
            val area = rectOf(paneCoords[0]) ?: return null
            // one column showing screen 1's sections then screen 2's
            val combined = (layout.panes[0] + layout.panes[1]).filter { it != s && visible(it) }
            val idx = combined.count { (rectOf(sectionCoords[it])?.center?.y ?: Float.MAX_VALUE) < p.y }
            val y = lineY(combined, idx, area, gapPx)
            val first = layout.panes[0].filter { it != s && visible(it) }
            return if (idx <= first.size) {
                DropTarget(0, fullIndex(layout.panes[0], s, first, idx), Rect(area.left, y - gapPx / 2, area.right, y + gapPx / 2))
            } else {
                val second = layout.panes[1].filter { it != s && visible(it) }
                DropTarget(1, fullIndex(layout.panes[1], s, second, idx - first.size), Rect(area.left, y - gapPx / 2, area.right, y + gapPx / 2))
            }
        }
    }

    private fun lineY(list: List<Section>, idx: Int, area: Rect, gap: Float): Float {
        if (list.isEmpty()) return area.top + gap
        return if (idx < list.size) (rectOf(sectionCoords[list[idx]])?.top ?: area.top) - gap
        else (rectOf(sectionCoords[list.last()])?.bottom ?: area.bottom) + gap
    }

    /** Converts a position among the shown sections into a position in the full list (hidden ones included). */
    private fun fullIndex(full: List<Section>, dragged: Section, shown: List<Section>, idx: Int): Int {
        val without = full.filter { it != dragged }
        return if (idx < shown.size) without.indexOf(shown[idx]).coerceAtLeast(0) else without.size
    }
}

/** Press-and-hold a title bar, then drag, to move a section. */
fun Modifier.sectionDragHandle(
    section: Section,
    drag: SectionDragState,
    onMove: () -> Unit,
    onDrop: () -> Unit,
): Modifier = this
    .onGloballyPositioned { drag.titleCoords[section] = it }
    .pointerInput(section) {
        detectDragGesturesAfterLongPress(
            onDragStart = { pos ->
                drag.dragging = section
                drag.pointer = drag.toContainer(section, pos)
                onMove()
            },
            onDrag = { change, _ ->
                change.consume()
                drag.pointer = drag.toContainer(section, change.position) ?: drag.pointer
                onMove()
            },
            onDragEnd = { onDrop(); drag.dragging = null; drag.pointer = null; drag.target = null },
            onDragCancel = { drag.dragging = null; drag.pointer = null; drag.target = null },
        )
    }

/** Drop line and a floating label that follows your finger. Draw this on top of the player area. */
@Composable
fun SectionDragOverlay(drag: SectionDragState) {
    val s = drag.dragging ?: return
    val p = drag.pointer ?: return
    val accent = sc(Slot.ACCENT)
    val density = LocalDensity.current
    drag.target?.let { t ->
        Box(
            Modifier
                .offset { IntOffset(t.line.left.roundToInt(), t.line.top.roundToInt()) }
                .size(with(density) { t.line.width.toDp() }, with(density) { t.line.height.toDp().coerceAtLeast(4.dp) })
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
    }
    Box(
        Modifier.offset {
            IntOffset((p.x - 70.dp.toPx()).roundToInt(), (p.y - 34.dp.toPx()).roundToInt())
        }
    ) {
        TextButtonSkin("MOVE ${s.label}", Modifier.width(140.dp), lit = true, size = 9.sp) { }
    }
}
