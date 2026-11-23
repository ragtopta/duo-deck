package com.duodeck.player.skin

import org.json.JSONObject
import java.util.UUID

/** Every colour a skin can set. The label is what the Skin Studio shows. */
enum class Slot(val label: String, val group: String) {
    FRAME("Panel", "Panels"),
    FRAME_LIGHT("Panel highlight", "Panels"),
    FRAME_DARK("Panel shadow", "Panels"),
    TITLE_BAR("Title bar stripes", "Panels"),
    TITLE_TEXT("Title text", "Panels"),
    DISPLAY_BG("Display background", "Display"),
    DISPLAY_TEXT("Display text", "Display"),
    DISPLAY_DIM("Display labels / unlit", "Display"),
    BUTTON("Button face", "Buttons"),
    BUTTON_TEXT("Button icons & text", "Buttons"),
    ACCENT("Accent (lit lights, fills)", "Buttons"),
    SLIDER_TRACK("Slider groove", "Buttons"),
    SLIDER_THUMB("Slider knob", "Buttons"),
    LIST_BG("Playlist background", "Playlist"),
    LIST_TEXT("Playlist text", "Playlist"),
    LIST_CURRENT("Playing track text", "Playlist"),
    LIST_SELECT_BG("Playing track bar", "Playlist"),
    VIS_BG("Visualizer background", "Visualizer"),
    VIS_LOW("Bars - low", "Visualizer"),
    VIS_HIGH("Bars - high", "Visualizer"),
    VIS_PEAK("Peak caps / line", "Visualizer"),
}

enum class ButtonShape(val label: String) { SQUARE("Square"), ROUNDED("Rounded"), PILL("Pill"), CHAMFER("Cut corner") }
enum class SkinFont(val label: String) { PIXEL("Pixel"), TERMINAL("Terminal"), MONO("Mono"), SANS("Sans"), SERIF("Serif") }
enum class Texture(val label: String) { NONE("None"), BRUSHED("Brushed metal"), SCANLINES("Scanlines"), DOTS("Dots"), CHECKER("Checker"), GRADIENT("Gradient"), HAZARD("Hazard stripes"), CARBON("Carbon") }
enum class VisStyle(val label: String) { BARS("LED bars"), BARS_PEAKS("Bars + peaks"), SOLID("Solid bars"), MIRROR("Mirror"), SCOPE("Oscilloscope"), DOTS("Peak dots") }
enum class TitleStyle(val label: String) { STRIPES("Stripes"), SOLID("Solid bar"), PLAIN("Plain") }

data class Skin(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Skin",
    val author: String = "",
    val builtIn: Boolean = false,
    val colors: Map<Slot, Long> = emptyMap(),
    val buttonShape: ButtonShape = ButtonShape.SQUARE,
    val cornerDp: Int = 4,
    val bevelDp: Int = 2,
    val font: SkinFont = SkinFont.PIXEL,
    val texture: Texture = Texture.NONE,
    val textureStrength: Float = 0.5f,
    val visStyle: VisStyle = VisStyle.BARS_PEAKS,
    val titleStyle: TitleStyle = TitleStyle.STRIPES,
    /** absolute path of a background image copied into app storage, or null */
    val bgImage: String? = null,
    val bgImageAlpha: Float = 0.5f,
    /** 0 = flat, 1 = very glossy / metallic */
    val shine: Float = 0.7f,
) {
    fun color(slot: Slot): Long = colors[slot] ?: BuiltInSkins.NIGHT_SHIFT.colors[slot] ?: 0xFFFF00FF

    fun withColor(slot: Slot, argb: Long) = copy(colors = colors + (slot to argb))

    fun toJson(embeddedImageBase64: String? = null): JSONObject {
        val o = JSONObject()
        o.put("format", "duodeck-skin")
        o.put("version", 1)
        o.put("name", name)
        o.put("author", author)
        val c = JSONObject()
        for (s in Slot.entries) c.put(s.name, toHex(color(s)))
        o.put("colors", c)
        o.put("buttonShape", buttonShape.name)
        o.put("cornerDp", cornerDp)
        o.put("bevelDp", bevelDp)
        o.put("font", font.name)
        o.put("texture", texture.name)
        o.put("textureStrength", textureStrength.toDouble())
        o.put("visStyle", visStyle.name)
        o.put("titleStyle", titleStyle.name)
        o.put("bgImageAlpha", bgImageAlpha.toDouble())
        o.put("shine", shine.toDouble())
        if (bgImage != null) o.put("bgImage", bgImage)
        if (embeddedImageBase64 != null) o.put("bgImageData", embeddedImageBase64)
        return o
    }

    companion object {
        fun toHex(argb: Long): String = "#%08X".format(argb and 0xFFFFFFFFL)

        fun parseHex(s: String): Long? {
            val h = s.trim().removePrefix("#")
            val v = h.toLongOrNull(16) ?: return null
            return when (h.length) {
                6 -> 0xFF000000L or v
                8 -> v
                else -> null
            }
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: def

        fun fromJson(o: JSONObject, id: String = UUID.randomUUID().toString()): Skin {
            val cj = o.optJSONObject("colors")
            val colors = mutableMapOf<Slot, Long>()
            if (cj != null) for (s in Slot.entries) {
                parseHex(cj.optString(s.name, ""))?.let { colors[s] = it }
            }
            return Skin(
                id = id,
                name = o.optString("name", "Imported Skin"),
                author = o.optString("author", ""),
                builtIn = false,
                colors = colors,
                buttonShape = enumOr(o.optString("buttonShape"), ButtonShape.SQUARE),
                cornerDp = o.optInt("cornerDp", 4).coerceIn(0, 24),
                bevelDp = o.optInt("bevelDp", 2).coerceIn(0, 4),
                font = enumOr(o.optString("font"), SkinFont.PIXEL),
                texture = enumOr(o.optString("texture"), Texture.NONE),
                textureStrength = o.optDouble("textureStrength", 0.5).toFloat().coerceIn(0f, 1f),
                visStyle = enumOr(o.optString("visStyle"), VisStyle.BARS_PEAKS),
                titleStyle = enumOr(o.optString("titleStyle"), TitleStyle.STRIPES),
                bgImage = o.optString("bgImage", "").ifBlank { null },
                bgImageAlpha = o.optDouble("bgImageAlpha", 0.5).toFloat().coerceIn(0f, 1f),
                shine = o.optDouble("shine", 0.7).toFloat().coerceIn(0f, 1f),
            )
        }
    }
}
