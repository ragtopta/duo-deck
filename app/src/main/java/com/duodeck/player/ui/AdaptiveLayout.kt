package com.duodeck.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

enum class PaneMode { SINGLE, SIDE_BY_SIDE, TOP_BOTTOM }

/**
 * Lays content out around the Surface Duo hinge.
 *  - Spanned across both screens, hinge vertical  -> first | second
 *  - Spanned with hinge horizontal (laptop pose)  -> first over second
 *  - Big landscape screen with no hinge           -> 50/50 split
 *  - Otherwise (one screen)                       -> single()
 */
@Composable
fun AdaptiveLayout(
    fold: FoldingFeature?,
    first: @Composable (PaneMode) -> Unit,
    second: @Composable (PaneMode) -> Unit,
    single: @Composable () -> Unit,
) {
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    // "Movable" panes: when the layout switches between side-by-side and top/bottom,
    // the panes are moved instead of rebuilt, so lists, tabs and edits are kept.
    val firstNow = androidx.compose.runtime.rememberUpdatedState(first)
    val secondNow = androidx.compose.runtime.rememberUpdatedState(second)
    val firstPane = remember { androidx.compose.runtime.movableContentOf<PaneMode> { m -> firstNow.value(m) } }
    val secondPane = remember { androidx.compose.runtime.movableContentOf<PaneMode> { m -> secondNow.value(m) } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootPos = it.positionInWindow() }
    ) {
        val separating = fold != null && fold.isSeparating
        val manualRotation = LocalManualRotation.current
        when {
            // Laptop pose on a Duo that didn't rotate the screen for us: we rotate the UI ourselves
            // (see RotatedBox), so the vertical hinge becomes a horizontal one in our coordinates.
            separating && manualRotation != 0 && fold!!.orientation == FoldingFeature.Orientation.VERTICAL -> {
                val b = fold.bounds
                val topPx = if (manualRotation < 0) b.left - rootPos.x else rootPos.x - b.right
                val topDp = with(density) { topPx.coerceAtLeast(0f).toDp() }
                val hingeDp = with(density) { b.width().toFloat().toDp() }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.height(topDp).fillMaxWidth()) { firstPane(PaneMode.TOP_BOTTOM) }
                    Spacer(Modifier.height(hingeDp))
                    Box(Modifier.weight(1f).fillMaxWidth()) { secondPane(PaneMode.TOP_BOTTOM) }
                }
            }
            separating && fold!!.orientation == FoldingFeature.Orientation.VERTICAL -> {
                val b = fold.bounds
                val leftDp = with(density) { (b.left - rootPos.x).coerceAtLeast(0f).toDp() }
                val hingeDp = with(density) { b.width().toFloat().toDp() }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(leftDp).fillMaxHeight()) { firstPane(PaneMode.SIDE_BY_SIDE) }
                    Spacer(Modifier.width(hingeDp))
                    Box(Modifier.weight(1f).fillMaxHeight()) { secondPane(PaneMode.SIDE_BY_SIDE) }
                }
            }
            separating && fold!!.orientation == FoldingFeature.Orientation.HORIZONTAL -> {
                val b = fold.bounds
                val topDp = with(density) { (b.top - rootPos.y).coerceAtLeast(0f).toDp() }
                val hingeDp = with(density) { b.height().toFloat().toDp() }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.height(topDp).fillMaxWidth()) { firstPane(PaneMode.TOP_BOTTOM) }
                    Spacer(Modifier.height(hingeDp))
                    Box(Modifier.weight(1f).fillMaxWidth()) { secondPane(PaneMode.TOP_BOTTOM) }
                }
            }
            maxWidth >= 700.dp && maxWidth > maxHeight -> {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { firstPane(PaneMode.SIDE_BY_SIDE) }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) { secondPane(PaneMode.SIDE_BY_SIDE) }
                }
            }
            else -> single()
        }
    }
}

/** Degrees the UI is turned by hand (0, 90 or -90) when the system didn't rotate for laptop pose. */
val LocalManualRotation = androidx.compose.runtime.staticCompositionLocalOf { 0 }

/**
 * Lays its content out as if the screen were turned [degrees] (90 / -90), then rotates it into place.
 * Touches are rotated too, so everything keeps working.
 */
@Composable
fun RotatedBox(degrees: Int, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalManualRotation provides degrees) {
        // Always the same layout node (only the maths changes), so screens keep their state when you fold.
        androidx.compose.ui.layout.Layout(content = { Box { content() } }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            if (degrees == 0) {
                val p = measurables.first().measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(w, h) { p.place(0, 0) }
            } else {
                val p = measurables.first().measure(androidx.compose.ui.unit.Constraints.fixed(h, w))
                layout(w, h) {
                    p.placeWithLayer((w - h) / 2, (h - w) / 2) { rotationZ = degrees.toFloat() }
                }
            }
        }
    }
}
