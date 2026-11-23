package com.duodeck.player.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duodeck.player.R
import com.duodeck.player.skin.ButtonShape
import com.duodeck.player.skin.Skin
import com.duodeck.player.skin.SkinFont
import com.duodeck.player.skin.Slot
import com.duodeck.player.skin.Texture
import com.duodeck.player.skin.TitleStyle
import java.io.File
import kotlin.random.Random

val LocalSkin = staticCompositionLocalOf { com.duodeck.player.skin.BuiltInSkins.NIGHT_SHIFT }

/** Read a colour from the active skin. */
@Composable
fun sc(slot: Slot): Color = Color(LocalSkin.current.color(slot))

fun Skin.c(slot: Slot): Color = Color(color(slot))

val PixelFamily = FontFamily(Font(R.font.silkscreen))
val TerminalFamily = FontFamily(Font(R.font.vt323))

fun Skin.fontFamily(): FontFamily = when (font) {
    SkinFont.PIXEL -> PixelFamily
    SkinFont.TERMINAL -> TerminalFamily
    SkinFont.MONO -> FontFamily.Monospace
    SkinFont.SANS -> FontFamily.SansSerif
    SkinFont.SERIF -> FontFamily.Serif
}

/** Font used for the playlist / scrolling title: pixel fonts are hard to read in long lists, so use the terminal face. */
fun Skin.listFamily(): FontFamily = when (font) {
    SkinFont.PIXEL, SkinFont.TERMINAL -> TerminalFamily
    else -> fontFamily()
}

/** Terminal font renders small, so scale it up a bit. */
fun Skin.listScale(): Float = when (font) {
    SkinFont.PIXEL, SkinFont.TERMINAL -> 1.3f
    else -> 1f
}

fun Skin.labelScale(): Float = when (font) {
    SkinFont.PIXEL -> 0.85f
    SkinFont.TERMINAL -> 1.3f
    else -> 1f
}

fun Skin.shape(small: Boolean = false): Shape {
    val r = (if (small) cornerDp.coerceAtMost(8) else cornerDp).dp
    return when (buttonShape) {
        ButtonShape.SQUARE -> if (cornerDp <= 0) RectangleShape else RoundedCornerShape(r.coerceAtMost(4.dp))
        ButtonShape.ROUNDED -> RoundedCornerShape(r)
        ButtonShape.PILL -> RoundedCornerShape(50)
        ButtonShape.CHAMFER -> CutCornerShape(r.coerceAtLeast(3.dp))
    }
}

fun Skin.panelShape(): Shape = when (buttonShape) {
    ButtonShape.CHAMFER -> CutCornerShape(cornerDp.coerceIn(3, 14).dp)
    ButtonShape.SQUARE -> if (cornerDp <= 0) RectangleShape else RoundedCornerShape(cornerDp.coerceAtMost(6).dp)
    else -> RoundedCornerShape(cornerDp.coerceAtMost(16).dp)
}

@Composable
fun SkinText(
    text: String,
    color: Color,
    size: TextUnit = 11.sp,
    modifier: Modifier = Modifier,
    family: FontFamily? = null,
    weight: FontWeight? = null,
    maxLines: Int = 1,
) {
    val skin = LocalSkin.current
    val fam = family ?: skin.fontFamily()
    val scale = if (family == null) skin.labelScale() else 1f
    Text(
        text = text,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        softWrap = maxLines > 1,
        style = TextStyle(fontFamily = fam, fontSize = size * scale, fontWeight = weight, lineHeight = size * scale * 1.1f),
    )
}

// ------------------------------------------------------------------ textures

private val noise = Random(7).let { r -> FloatArray(400) { r.nextFloat() } }

private data class TexKey(val t: Texture, val s: Float, val light: Int, val dark: Int)
private val tileCache = HashMap<TexKey, androidx.compose.ui.graphics.ShaderBrush>()

/**
 * Builds the texture ONCE as a small repeating tile and reuses it. Drawing thousands of
 * shapes every frame (the old way) froze the app when the visualizer was running.
 */
private fun textureBrush(texture: Texture, s: Float, light: Color, dark: Color): androidx.compose.ui.graphics.ShaderBrush? {
    val key = TexKey(texture, s, light.toArgb(), dark.toArgb())
    tileCache[key]?.let { return it }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    fun col(c: Color, a: Float) = c.copy(alpha = a.coerceIn(0f, 1f)).toArgb()
    val bmp: android.graphics.Bitmap = when (texture) {
        Texture.BRUSHED -> {
            val b = android.graphics.Bitmap.createBitmap(8, 600, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b)
            var y = 0f; var i = 0
            while (y < 600f) {
                val n = noise[i % noise.size]
                paint.color = col(if (n > 0.5f) light else dark, s * 0.35f * n)
                cv.drawRect(0f, y, 8f, y + 1f, paint)
                y += 1.5f + noise[(i * 7) % noise.size] * 1.5f; i++
            }
            b
        }
        Texture.SCANLINES -> {
            val b = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            paint.color = col(dark, s * 0.55f)
            android.graphics.Canvas(b).drawRect(0f, 0f, 4f, 1.2f, paint)
            b
        }
        Texture.DOTS -> {
            val b = android.graphics.Bitmap.createBitmap(14, 28, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b)
            paint.color = col(light, s * 0.8f)
            cv.drawCircle(7f, 7f, 2.2f, paint)
            cv.drawCircle(0f, 21f, 2.2f, paint); cv.drawCircle(14f, 21f, 2.2f, paint)
            b
        }
        Texture.CHECKER -> {
            val b = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b)
            paint.isAntiAlias = false
            paint.color = col(dark, s * 0.25f)
            cv.drawRect(0f, 0f, 10f, 10f, paint); cv.drawRect(10f, 10f, 20f, 20f, paint)
            b
        }
        Texture.CARBON -> {
            val b = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(b)
            paint.isAntiAlias = false
            val l = col(light, s * 0.3f); val d = col(dark, s * 0.3f)
            for (r in 0..1) for (c0 in 0..1) {
                val c = r + c0
                val x = c0 * 6f; val y = r * 6f
                paint.color = if (c % 2 == 0) l else d; cv.drawRect(x, y, x + 6f, y + 3f, paint)
                paint.color = if (c % 2 == 0) d else l; cv.drawRect(x, y + 3f, x + 6f, y + 6f, paint)
            }
            b
        }
        else -> return null
    }
    val brush = androidx.compose.ui.graphics.ShaderBrush(
        androidx.compose.ui.graphics.ImageShader(
            bmp.asImageBitmap(),
            androidx.compose.ui.graphics.TileMode.Repeated,
            androidx.compose.ui.graphics.TileMode.Repeated,
        )
    )
    if (tileCache.size > 64) tileCache.clear()
    tileCache[key] = brush
    return brush
}

fun DrawScope.drawTexture(texture: Texture, strength: Float, base: Color, light: Color, dark: Color) {
    val s = strength.coerceIn(0f, 1f)
    if (s <= 0f) return
    when (texture) {
        Texture.NONE -> Unit
        Texture.GRADIENT -> {
            drawRect(Brush.verticalGradient(listOf(light.copy(alpha = s * 0.7f), Color.Transparent, dark.copy(alpha = s * 0.7f))))
        }
        Texture.HAZARD -> {
            val w = 22f
            var x = -size.height
            clipRect {
                while (x < size.width + size.height) {
                    val p = Path().apply {
                        moveTo(x, size.height); lineTo(x + w, size.height)
                        lineTo(x + w + size.height, 0f); lineTo(x + size.height, 0f); close()
                    }
                    drawPath(p, Color.Black.copy(alpha = s))
                    x += w * 2
                }
            }
        }
        else -> textureBrush(texture, s, light, dark)?.let { drawRect(it) }
    }
}

// ------------------------------------------------------------------ images

// Remembers the last few skin pictures only, so trying lots of photos doesn't use up memory.
private val bitmapCache = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap?>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap?>?) = size > 4
}

fun loadSkinImage(path: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (path == null) return null
    return bitmapCache.getOrPut(path) {
        try {
            if (File(path).exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
        } catch (_: Exception) { null }
    }
}

// ------------------------------------------------------------------ bevel

fun Modifier.bevel(skin: Skin, shape: Shape, sunken: Boolean = false, width: Dp? = null): Modifier {
    val w = width ?: skin.bevelDp.dp
    if (w <= 0.dp) return this
    val light = skin.c(Slot.FRAME_LIGHT)
    val dark = skin.c(Slot.FRAME_DARK)
    val a = if (sunken) dark else light
    val b = if (sunken) light else dark
    // hard split along the diagonal: top/left edges one colour, bottom/right the other (classic 3D bevel)
    val outer = Brush.linearGradient(0f to a, 0.48f to a, 0.52f to b, 1f to b, start = Offset.Zero, end = Offset.Infinite)
    return this.border(w, outer, shape)
}

/** Double bevel: crisp outer edge plus a softer inner edge, for a chunky raised / sunken look. */
fun Modifier.bevel3d(skin: Skin, shape: Shape, sunken: Boolean = false): Modifier {
    val w = skin.bevelDp
    if (w <= 0) return this
    val light = skin.c(Slot.FRAME_LIGHT)
    val dark = skin.c(Slot.FRAME_DARK)
    val a = if (sunken) dark else light
    val b = if (sunken) light else dark
    val outer = Brush.linearGradient(0f to a, 0.48f to a, 0.52f to b, 1f to b, start = Offset.Zero, end = Offset.Infinite)
    val ia = a.copy(alpha = 0.45f)
    val ib = b.copy(alpha = 0.45f)
    val inner = Brush.linearGradient(0f to ia, 0.48f to ia, 0.52f to ib, 1f to ib, start = Offset.Zero, end = Offset.Infinite)
    val outerW = ((w + 1) / 2).coerceAtLeast(1).dp
    val innerW = (w / 2).coerceAtLeast(1).dp
    return this
        .border(outerW, outer, shape)
        .padding(outerW)
        .border(innerW, inner, shape)
}

fun lighten(c: Color, t: Float) = lerpColor(c, Color.White, t.coerceIn(0f, 1f))
fun brighten(c: Color, t: Float) = lerpColor(c, Color.White, t.coerceIn(0f, 1f))
fun darken(c: Color, t: Float) = lerpColor(c, Color.Black, t.coerceIn(0f, 1f))

/** Raised face: vertical metal gradient + a glossy highlight on the top half. */
fun Modifier.raisedFace(skin: Skin, face: Color, shape: Shape, pressed: Boolean = false): Modifier {
    val s = skin.shine
    val top = if (pressed) darken(face, 0.18f * s) else lighten(face, 0.35f * s)
    val bottom = if (pressed) lighten(face, 0.05f * s) else darken(face, 0.30f * s)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, face, bottom)))
        .drawWithContent {
            if (!pressed && s > 0f) {
                // glossy band across the top
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.32f * s),
                        0.45f to Color.White.copy(alpha = 0.08f * s),
                        0.5f to Color.Transparent,
                        endY = size.height
                    )
                )
            }
            drawContent()
        }
}

/** Sunken screen: inner shadow on the top/left and a faint glass reflection. */
fun Modifier.insetScreen(skin: Skin, bg: Color, shape: Shape): Modifier {
    val s = skin.shine
    return this
        .clip(shape)
        .background(bg)
        .drawWithContent {
            drawContent()
            val sh = 7.dp.toPx()
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), endY = sh), size = Size(size.width, sh))
            drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), endX = sh), size = Size(sh, size.height))
            if (s > 0f) {
                // glass reflection
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.10f * s),
                        0.35f to Color.White.copy(alpha = 0.03f * s),
                        0.36f to Color.Transparent,
                        start = Offset.Zero, end = Offset(size.width * 0.6f, size.height)
                    )
                )
            }
        }
}

/** Little round indicator light with glow when lit. */
@Composable
fun Led(lit: Boolean, modifier: Modifier = Modifier.size(7.dp)) {
    val skin = LocalSkin.current
    val on = skin.c(Slot.ACCENT)
    val off = darken(skin.c(Slot.FRAME_DARK), 0.2f)
    Box(modifier.drawBehind {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        if (lit) drawCircle(on.copy(alpha = 0.35f), r * 1.8f, c)
        drawCircle(if (lit) on else off, r, c)
        drawCircle(Color.White.copy(alpha = if (lit) 0.7f else 0.25f), r * 0.35f, Offset(c.x - r * 0.3f, c.y - r * 0.3f))
    })
}

// ------------------------------------------------------------------ panel

@Composable
fun SkinPanel(
    title: String,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    titleModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val skin = LocalSkin.current
    val shape = skin.panelShape()
    val frame = skin.c(Slot.FRAME)
    val image = loadSkinImage(skin.bgImage)
    val s = skin.shine
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lighten(frame, 0.22f * s), frame, darken(frame, 0.28f * s))))
    ) {
        if (image != null) {
            Image(
                bitmap = image, contentDescription = null, contentScale = ContentScale.Crop,
                alpha = skin.bgImageAlpha, modifier = Modifier.matchParentSize()
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawTexture(skin.texture, skin.textureStrength, frame, skin.c(Slot.FRAME_LIGHT), skin.c(Slot.FRAME_DARK))
                    // soft sheen across the panel
                    if (s > 0f) drawRect(
                        Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0.10f * s), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.10f * s),
                            start = Offset.Zero, end = Offset(size.width, size.height)
                        )
                    )
                }
        )
        Column(
            Modifier
                .bevel3d(skin, shape)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            TitleBar(title, onMenu, onClose, titleModifier)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun TitleBar(title: String, onMenu: (() -> Unit)? = null, onClose: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val stripe = skin.c(Slot.TITLE_BAR)
    val text = skin.c(Slot.TITLE_TEXT)
    Row(
        modifier
            .fillMaxWidth()
            .height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenu != null) {
            TinyButton(onMenu) {
                val c = skin.c(Slot.BUTTON_TEXT)
                for (i in 0..2) drawRect(c, Offset(size.width * 0.2f, size.height * (0.22f + i * 0.22f)), Size(size.width * 0.6f, size.height * 0.12f))
            }
            Spacer(Modifier.width(4.dp))
        }
        TitleFill(skin.titleStyle, stripe, Modifier.weight(1f))
        Text(
            title.uppercase(),
            color = text,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
            style = TextStyle(
                fontFamily = skin.fontFamily(), fontSize = 11.sp * skin.labelScale(), fontWeight = FontWeight.Bold,
                shadow = Shadow(skin.c(Slot.FRAME_DARK).copy(alpha = 0.8f), Offset(1.5f, 1.5f), 1f),
            )
        )
        TitleFill(skin.titleStyle, stripe, Modifier.weight(1f))
        if (onClose != null) {
            Spacer(Modifier.width(4.dp))
            TinyButton(onClose) {
                val c = skin.c(Slot.BUTTON_TEXT)
                val p = size.width * 0.28f
                drawLine(c, Offset(p, p), Offset(size.width - p, size.height - p), 2f)
                drawLine(c, Offset(size.width - p, p), Offset(p, size.height - p), 2f)
            }
        }
    }
}

@Composable
private fun TinyButton(onClick: () -> Unit, draw: DrawScope.() -> Unit) {
    val skin = LocalSkin.current
    val shape = skin.shape(true)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(17.dp)
            .raisedFace(skin, skin.c(Slot.BUTTON), shape, pressed)
            .bevel(skin, shape, sunken = pressed, width = 1.dp)
            .clickable(interactionSource = source, indication = null) { onClick() }
            .drawBehind(draw)
    )
}

@Composable
private fun TitleFill(style: TitleStyle, color: Color, modifier: Modifier) {
    when (style) {
        TitleStyle.STRIPES -> Box(
            modifier
                .height(11.dp)
                .drawBehind {
                    val lines = 4
                    val gap = size.height / lines
                    for (i in 0 until lines) {
                        val h = gap * 0.6f
                        drawRect(color, Offset(0f, i * gap), Size(size.width, h))
                        drawRect(Color.White.copy(alpha = 0.35f), Offset(0f, i * gap), Size(size.width, h * 0.3f))
                        drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, i * gap + h * 0.75f), Size(size.width, h * 0.25f))
                    }
                }
        )
        TitleStyle.SOLID -> Box(
            modifier.height(9.dp).clip(RoundedCornerShape(5.dp))
                .background(Brush.verticalGradient(listOf(lighten(color, 0.4f), color, darken(color, 0.3f))))
        )
        TitleStyle.PLAIN -> Spacer(modifier)
    }
}

// ------------------------------------------------------------------ inset display box

@Composable
fun DisplayBox(modifier: Modifier = Modifier, bg: Color? = null, content: @Composable BoxScope.() -> Unit) {
    val skin = LocalSkin.current
    val shape = if (skin.cornerDp > 0 && skin.buttonShape != ButtonShape.SQUARE) RoundedCornerShape(skin.cornerDp.coerceAtMost(6).dp) else RectangleShape
    Box(
        modifier
            .insetScreen(skin, bg ?: skin.c(Slot.DISPLAY_BG), shape)
            .bevel(skin, shape, sunken = true, width = skin.bevelDp.coerceIn(1, 2).dp)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) { content() }
}

// ------------------------------------------------------------------ buttons

@Composable
fun SkinButton(
    modifier: Modifier = Modifier,
    lit: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val skin = LocalSkin.current
    val shape = skin.shape()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val face = skin.c(Slot.BUTTON)
    Box(
        modifier
            .raisedFace(skin, face, shape, pressed)
            .bevel3d(skin, shape, sunken = pressed)
            .clickable(interactionSource = source, indication = null) { onClick() }
            .offset(x = if (pressed) 1.dp else 0.dp, y = if (pressed) 1.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (lit != null) {
                Led(lit)
                Spacer(Modifier.width(4.dp))
            }
            content()
        }
    }
}

@Composable
fun TextButtonSkin(label: String, modifier: Modifier = Modifier, lit: Boolean? = null, size: TextUnit = 10.sp, onClick: () -> Unit) {
    SkinButton(modifier.height(26.dp), lit = lit, onClick = onClick) {
        val skin = LocalSkin.current
        Text(
            label, color = skin.c(Slot.BUTTON_TEXT), maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp),
            style = TextStyle(
                fontFamily = skin.fontFamily(), fontSize = size * skin.labelScale(), fontWeight = FontWeight.Bold,
                shadow = Shadow(Color.White.copy(alpha = 0.35f * skin.shine), Offset(0.8f, 0.8f), 0f),
            )
        )
    }
}

fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    a.alpha + (b.alpha - a.alpha) * t,
)

// ------------------------------------------------------------------ icons

enum class Icon { PREV, PLAY, PAUSE, STOP, NEXT, EJECT }

@Composable
fun TransportIcon(icon: Icon, color: Color, modifier: Modifier = Modifier.size(14.dp)) {
    Box(modifier.drawBehind {
        // engraved look: light shadow offset under the icon
        translate(1.2f, 1.2f) { drawIcon(icon, Color.White.copy(alpha = 0.35f)) }
        drawIcon(icon, color)
    })
}

fun DrawScope.drawIcon(icon: Icon, color: Color) {
    val w = size.width
    val h = size.height
    fun tri(x0: Float, x1: Float, right: Boolean) {
        val p = Path()
        if (right) { p.moveTo(x0, 0f); p.lineTo(x1, h / 2); p.lineTo(x0, h) } else { p.moveTo(x1, 0f); p.lineTo(x0, h / 2); p.lineTo(x1, h) }
        p.close(); drawPath(p, color)
    }
    when (icon) {
        Icon.PLAY -> tri(w * 0.15f, w * 0.9f, true)
        Icon.PAUSE -> { drawRect(color, Offset(w * 0.15f, 0f), Size(w * 0.25f, h)); drawRect(color, Offset(w * 0.6f, 0f), Size(w * 0.25f, h)) }
        Icon.STOP -> drawRect(color, Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f))
        Icon.PREV -> { drawRect(color, Offset(0f, 0f), Size(w * 0.14f, h)); tri(w * 0.14f, w * 0.57f, false); tri(w * 0.57f, w, false) }
        Icon.NEXT -> { tri(0f, w * 0.43f, true); tri(w * 0.43f, w * 0.86f, true); drawRect(color, Offset(w * 0.86f, 0f), Size(w * 0.14f, h)) }
        Icon.EJECT -> {
            val p = Path().apply { moveTo(w / 2, 0f); lineTo(w, h * 0.6f); lineTo(0f, h * 0.6f); close() }
            drawPath(p, color); drawRect(color, Offset(0f, h * 0.75f), Size(w, h * 0.25f))
        }
    }
}

// ------------------------------------------------------------------ sliders

/** Horizontal skinnable slider. value is 0..1 */
@Composable
fun SkinSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onFinished: ((Float) -> Unit)? = null,
    centered: Boolean = false,
    thumbWidth: Dp = 22.dp,
) {
    val skin = LocalSkin.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    val cb by rememberUpdatedState(onChange)
    val fin by rememberUpdatedState(onFinished)
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .height(20.dp)
            .pointerInput(Unit) {
                val tw = with(density) { thumbWidth.toPx() }
                fun frac(x: Float) = ((x - tw / 2) / (size.width - tw)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var f = frac(down.position.x)
                    dragging = f; cb(f)
                    drag(down.id) { ch ->
                        f = frac(ch.position.x); dragging = f; cb(f); ch.consume()
                    }
                    fin?.invoke(f)
                    dragging = null
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val round = skin.buttonShape == ButtonShape.PILL || skin.buttonShape == ButtonShape.ROUNDED
        val trackShape = RoundedCornerShape(if (round) 4.dp else 0.dp)
        val accent = skin.c(Slot.ACCENT)
        val hot = skin.c(Slot.VIS_HIGH)
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .insetScreen(skin, skin.c(Slot.SLIDER_TRACK), trackShape)
                .bevel(skin, trackShape, sunken = true, width = 1.dp)
                .drawBehind {
                    val tw = thumbWidth.toPx()
                    val usable = size.width - tw
                    val x = tw / 2 + usable * shown
                    val y0 = size.height * 0.28f
                    val hh = size.height * 0.44f
                    val brush = Brush.horizontalGradient(listOf(accent, lerpColor(accent, hot, 0.6f)), startX = 0f, endX = size.width)
                    if (centered) {
                        val mid = size.width / 2
                        drawRect(brush, Offset(minOf(mid, x), y0), Size(kotlin.math.abs(x - mid), hh))
                    } else {
                        drawRect(brush, Offset(0f, y0), Size(x, hh))
                    }
                    drawRect(Color.White.copy(alpha = 0.25f), Offset(0f, y0), Size(if (centered) 0f else x, hh * 0.35f))
                }
        )
        val travel = maxWidth - thumbWidth
        val thumbShape = skin.shape(true)
        Box(
            Modifier
                .offset(x = travel * shown)
                .size(width = thumbWidth, height = 16.dp)
                .raisedFace(skin, skin.c(Slot.SLIDER_THUMB), thumbShape)
                .bevel(skin, thumbShape, width = 1.dp)
                .drawBehind {
                    // grip ridges
                    val d = skin.c(Slot.FRAME_DARK).copy(alpha = 0.7f)
                    val l = Color.White.copy(alpha = 0.6f)
                    for (i in -1..1) {
                        val x = size.width / 2 + i * 3.dp.toPx()
                        drawLine(d, Offset(x, size.height * 0.28f), Offset(x, size.height * 0.72f), 1.2f)
                        drawLine(l, Offset(x + 1f, size.height * 0.28f), Offset(x + 1f, size.height * 0.72f), 1f)
                    }
                }
        )
    }
}

/** Vertical slider for the equalizer. value 0..1 (1 = top) */
@Composable
fun VSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, lit: Boolean = true) {
    val skin = LocalSkin.current
    val cb by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    val thumbH = 13.dp
    BoxWithConstraints(
        modifier
            .width(20.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                val th = with(density) { thumbH.toPx() }
                fun frac(y: Float) = (1f - (y - th / 2) / (size.height - th)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    cb(frac(down.position.y))
                    drag(down.id) { ch -> cb(frac(ch.position.y)); ch.consume() }
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        val low = skin.c(Slot.VIS_LOW)
        val high = skin.c(Slot.VIS_HIGH)
        val lineColor = remember(skin) { knobLineColor(skin) }
        val v = value
        Box(
            Modifier
                .width(7.dp)
                .fillMaxHeight()
                .insetScreen(skin, skin.c(Slot.SLIDER_TRACK), RectangleShape)
                .bevel(skin, RectangleShape, sunken = true, width = 1.dp)
                .drawBehind {
                    val th = thumbH.toPx()
                    val y = th / 2 + (size.height - th) * (1f - v)
                    val mid = size.height / 2
                    // colour runs low -> high as the band is boosted, like an LED meter
                    val col = lerpColor(low, high, v)
                    drawRect(
                        Brush.verticalGradient(listOf(high, col, low), startY = 0f, endY = size.height),
                        Offset(size.width * 0.2f, minOf(mid, y)), Size(size.width * 0.6f, kotlin.math.abs(mid - y))
                    )
                }
        )
        val travel = maxHeight - thumbH
        val shape = skin.shape(true)
        Box(
            Modifier
                .offset(y = travel * (1f - v))
                .size(width = 19.dp, height = thumbH)
                .raisedFace(skin, skin.c(Slot.SLIDER_THUMB), shape)
                .bevel(skin, shape, width = 1.dp)
                .drawWithContent {
                    drawContent()
                    // A bold lit line across the knob, in the theme's main colour (see knobLineColor).
                    val lw = size.width * 0.82f
                    val lh = 3.5.dp.toPx()
                    val tl = Offset((size.width - lw) / 2f, (size.height - lh) / 2f)
                    // dark groove the line sits in
                    drawRect(Color.Black.copy(alpha = 0.6f), tl - Offset(1f, 1f), Size(lw + 2f, lh + 2f))
                    if (lit) {
                        drawRect(lineColor, tl, Size(lw, lh))
                        // thin shine along the top edge
                        drawRect(Color.White.copy(alpha = 0.35f), tl, Size(lw, lh * 0.28f))
                    } else {
                        drawRect(darken(lineColor, 0.65f), tl, Size(lw, lh))
                    }
                }
        )
    }
}

/**
 * Colour for the lit line on the EQ knobs: the most vivid of the theme's main window colours
 * (display / playlist background, display text, accent), so it stands out against the knob.
 */
fun knobLineColor(skin: Skin): Color {
    val thumb = skin.c(Slot.SLIDER_THUMB)
    fun luma(c: Color) = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    fun score(c: Color): Float {
        val mx = maxOf(c.red, c.green, c.blue); val mn = minOf(c.red, c.green, c.blue)
        return (mx - mn) + 0.6f * kotlin.math.abs(luma(c) - luma(thumb))
    }
    return listOf(Slot.DISPLAY_BG, Slot.LIST_BG, Slot.DISPLAY_TEXT, Slot.ACCENT)
        .map { skin.c(it) }
        .maxBy { score(it) }
}

// ------------------------------------------------------------------ 7-segment clock

/** Draws text like "12:34" or "-03:05" as a seven-segment LED readout, with ghosted unlit segments. */
@Composable
fun SevenSegment(text: String, on: Color, off: Color, modifier: Modifier) {
    Box(modifier.drawBehind { drawSevenSegment(text, on, off) })
}

private val SEGMENTS = mapOf(
    '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
    '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg", '-' to "g", ' ' to ""
)

fun DrawScope.drawSevenSegment(text: String, on: Color, off: Color) {
    val digits = text.count { it != ':' }
    val colons = text.count { it == ':' }
    val units = digits * 1f + colons * 0.4f
    val dw = size.width / units
    val dh = size.height
    val digitW = minOf(dw, dh * 0.6f)
    val t = digitW * 0.16f
    var x = 0f
    for (ch in text) {
        if (ch == ':') {
            val cw = dw * 0.4f
            drawRect(on, Offset(x + cw / 2 - t / 2, dh * 0.28f), Size(t, t))
            drawRect(on, Offset(x + cw / 2 - t / 2, dh * 0.66f), Size(t, t))
            x += cw
            continue
        }
        val lit = SEGMENTS[ch] ?: ""
        val left = x + (dw - digitW) / 2 + t * 0.2f
        val w = digitW - t * 0.9f
        val half = dh / 2
        fun seg(name: Char, ox: Float, oy: Float, horizontal: Boolean, len: Float) {
            val col = if (lit.contains(name)) on else off
            val p = Path()
            if (horizontal) {
                p.moveTo(ox, oy); p.lineTo(ox + t / 2, oy - t / 2); p.lineTo(ox + len - t / 2, oy - t / 2)
                p.lineTo(ox + len, oy); p.lineTo(ox + len - t / 2, oy + t / 2); p.lineTo(ox + t / 2, oy + t / 2)
            } else {
                p.moveTo(ox, oy); p.lineTo(ox + t / 2, oy + t / 2); p.lineTo(ox + t / 2, oy + len - t / 2)
                p.lineTo(ox, oy + len); p.lineTo(ox - t / 2, oy + len - t / 2); p.lineTo(ox - t / 2, oy + t / 2)
            }
            p.close()
            if (col == on) drawPath(p, on.copy(alpha = 0.30f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = t * 0.9f))
            drawPath(p, col)
        }
        val gap = t * 0.15f
        seg('a', left + gap, t / 2, true, w - 2 * gap)
        seg('g', left + gap, half, true, w - 2 * gap)
        seg('d', left + gap, dh - t / 2, true, w - 2 * gap)
        seg('f', left, t / 2 + gap, false, half - t / 2 - 2 * gap)
        seg('b', left + w, t / 2 + gap, false, half - t / 2 - 2 * gap)
        seg('e', left, half + gap, false, half - t / 2 - 2 * gap)
        seg('c', left + w, half + gap, false, half - t / 2 - 2 * gap)
        x += dw
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60 % 100, total % 60)
}

fun formatLong(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Suppress("unused")
private fun unusedCorner() = CornerRadius.Zero
