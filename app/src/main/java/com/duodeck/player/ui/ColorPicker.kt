package com.duodeck.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.duodeck.player.skin.Skin
import android.graphics.Color as AColor

private val QUICK_COLORS = listOf(
    0xFF000000, 0xFF1E1E28, 0xFF34364A, 0xFF6E6E70, 0xFFC5C8D6, 0xFFFFFFFF,
    0xFF3DFF6E, 0xFF22C24A, 0xFF00F0FF, 0xFF2A30A6, 0xFF6C7CFF, 0xFF8C1EFF,
    0xFFFF5FD2, 0xFFFF5A1F, 0xFFFF8A1E, 0xFFFFB21E, 0xFFF2C200, 0xFFD9A441,
    0xFFB00020, 0xFF7A4A2A, 0xFF0B4F3C, 0xFF14315C, 0xFFFFC8DD, 0xFF9FFFE0,
)

/** HSV colour picker with hex entry and quick swatches. */
@Composable
fun ColorPickerDialog(title: String, initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val hsv0 = remember { FloatArray(3).also { AColor.colorToHSV(initial.toInt(), it) } }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    fun current(): Long = AColor.HSVToColor(floatArrayOf(hue, sat, value)).toLong() and 0xFFFFFFFFL
    var hex by remember { mutableStateOf(Skin.toHex(initial).takeLast(6)) }
    fun syncHex() { hex = Skin.toHex(current()).takeLast(6) }

    SkinDialog(
        title = title,
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        buttons = {
            TextButtonSkin("CANCEL") { onDismiss() }
            TextButtonSkin("USE COLOUR") { onPick(current()) }
        },
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // saturation / brightness square
                val hueColor = Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f)
                        .clip(RoundedCornerShape(6.dp))
                        .pointerInput(Unit) {
                            fun set(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                syncHex()
                            }
                            awaitEachGesture {
                                val d = awaitFirstDown(); set(d.position)
                                drag(d.id) { set(it.position); it.consume() }
                            }
                        }
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val p = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, 9f, p, style = Stroke(3f))
                    drawCircle(Color.Black, 11f, p, style = Stroke(1.5f))
                }
                // hue bar
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .pointerInput(Unit) {
                            fun set(o: Offset) { hue = (o.x / size.width).coerceIn(0f, 1f) * 359.9f; syncHex() }
                            awaitEachGesture {
                                val d = awaitFirstDown(); set(d.position)
                                drag(d.id) { set(it.position); it.consume() }
                            }
                        }
                ) {
                    drawRect(Brush.horizontalGradient((0..6).map { Color(AColor.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f))) }))
                    val x = hue / 360f * size.width
                    drawRect(Color.White, Offset(x - 3f, 0f), androidx.compose.ui.geometry.Size(6f, size.height), style = Stroke(2.5f))
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Spacer(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color(current())).border(1.dp, Color.Gray, RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    SkinTextField(
                        value = hex,
                        onChange = { t ->
                            hex = t.take(6)
                            Skin.parseHex(hex)?.takeIf { hex.length == 6 }?.let { c ->
                                val h = FloatArray(3); AColor.colorToHSV(c.toInt(), h)
                                hue = h[0]; sat = h[1]; value = h[2]
                            }
                        },
                        label = "Hex #",
                        modifier = Modifier.weight(1f)
                    )
                }
                // quick swatches
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_COLORS.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { c ->
                                Spacer(
                                    Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color(c))
                                        .border(1.dp, Color.Gray, CircleShape)
                                        .clickable {
                                            val h = FloatArray(3); AColor.colorToHSV(c.toInt(), h)
                                            hue = h[0]; sat = h[1]; value = h[2]; syncHex()
                                        }
                                )
                            }
                        }
                    }
                }
            }
    }
}
