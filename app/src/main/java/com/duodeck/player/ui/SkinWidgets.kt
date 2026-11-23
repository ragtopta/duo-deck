package com.duodeck.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.window.layout.FoldingFeature
import com.duodeck.player.skin.Slot

/** The Surface Duo hinge, so pop-ups can avoid it. */
val LocalFold = staticCompositionLocalOf<FoldingFeature?> { null }

/** Readable body text in the skin's list font. */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, color: Color? = null, size: TextUnit = 15.sp, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    val skin = LocalSkin.current
    Text(
        text, modifier = modifier, color = color ?: skin.c(Slot.TITLE_TEXT), maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = skin.listFamily(), fontSize = size * skin.listScale(), fontWeight = if (bold) FontWeight.Bold else null)
    )
}

@Composable
fun HintText(text: String) {
    val skin = LocalSkin.current
    BodyText(text, color = skin.c(Slot.TITLE_TEXT).copy(alpha = 0.7f), size = 13.sp)
}

/** Section header: small stripe bar with a label. */
@Composable
fun SectionHeader(text: String) {
    Spacer(Modifier.height(4.dp))
    TitleBar(text)
}

/** Text input that looks like an LCD readout. */
@Composable
fun SkinTextField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Column(modifier) {
        SkinText(label.uppercase(), skin.c(Slot.TITLE_TEXT), 8.sp)
        Spacer(Modifier.height(2.dp))
        DisplayBox(Modifier.fillMaxWidth().height(36.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = skin.c(Slot.DISPLAY_TEXT), fontFamily = skin.listFamily(), fontSize = 16.sp * skin.listScale()),
                cursorBrush = SolidColor(skin.c(Slot.DISPLAY_TEXT)),
                modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart)
            )
        }
    }
}

// ------------------------------------------------------------------ menu

class SkinMenuItem(val label: String, val onClick: () -> Unit)

/** Drop-down menu drawn as a raised skin panel. */
@Composable
fun SkinMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<SkinMenuItem>,
    offset: IntOffset = IntOffset(0, 0),
    alignment: Alignment = Alignment.TopStart,
) {
    if (!expanded) return
    val skin = LocalSkin.current
    val shape = skin.panelShape()
    val fold = LocalFold.current
    val rotation = LocalManualRotation.current
    @Suppress("UNUSED_EXPRESSION") offset
    @Suppress("UNUSED_EXPRESSION") alignment
    // Shown as a centred pop-up window (works the same when the UI is turned for laptop mode).
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RotatedBox(rotation) {
            val dismissArea = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            val menu: @Composable () -> Unit = {
                Box(dismissArea, contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .width(260.dp)
                            .heightIn(max = 460.dp)
                            .raisedFace(skin, skin.c(Slot.FRAME), shape)
                            .bevel3d(skin, shape)
                            .verticalScroll(rememberScrollState())
                            .padding(5.dp)
                    ) {
                        items.forEach { item -> MenuRow(item.label) { onDismiss(); item.onClick() } }
                    }
                }
            }
            AdaptiveLayout(fold = fold, first = { menu() }, second = { Box(dismissArea) }, single = { menu() })
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(if (pressed) skin.c(Slot.LIST_SELECT_BG) else Color.Transparent)
            .clickable(interactionSource = source, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportIcon(Icon.PLAY, if (pressed) skin.c(Slot.LIST_CURRENT) else skin.c(Slot.ACCENT), Modifier.width(7.dp).height(9.dp))
        Spacer(Modifier.width(8.dp))
        BodyText(label, color = if (pressed) skin.c(Slot.LIST_CURRENT) else skin.c(Slot.TITLE_TEXT), size = 16.sp, maxLines = 1)
    }
}

// ------------------------------------------------------------------ dialog

/**
 * Pop-up window drawn as a skin panel. On a spanned Surface Duo it sits on the
 * first screen instead of straddling the hinge.
 */
@Composable
fun SkinDialog(
    title: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 460.dp,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fold = LocalFold.current
    val skin = LocalSkin.current
    val rotation = LocalManualRotation.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      RotatedBox(rotation) {
        val panel: @Composable () -> Unit = {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                val maxH = maxHeight * 0.92f
                SkinPanel(
                    title,
                    Modifier
                        .padding(10.dp)
                        .widthIn(max = maxWidth)
                        .fillMaxWidth()
                        .heightIn(max = maxH)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                    onClose = onDismiss,
                ) {
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) { content() }
                    if (buttons != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) { buttons() }
                    }
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") skin
        AdaptiveLayout(
            fold = fold,
            first = { panel() },
            second = { Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }) },
            single = { panel() },
        )
      }
    }
}
