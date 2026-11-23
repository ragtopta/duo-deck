package com.duodeck.player.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import com.duodeck.player.EqState
import com.duodeck.player.PlayerUiState
import com.duodeck.player.RepeatMode
import com.duodeck.player.data.Track
import com.duodeck.player.skin.ButtonShape
import com.duodeck.player.skin.Skin
import com.duodeck.player.skin.SkinFont
import com.duodeck.player.skin.SkinGenerator
import com.duodeck.player.skin.SkinRepository
import com.duodeck.player.skin.Slot
import com.duodeck.player.skin.Texture
import com.duodeck.player.skin.TitleStyle
import com.duodeck.player.skin.VisStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val SAMPLE_TRACKS = listOf(
    Track("s1", Uri.EMPTY, "Third Shift Groove", "The Night Crew", "", 241_000, 7_700_000),
    Track("s2", Uri.EMPTY, "Conveyor Belt Blues", "Lineworks", "", 198_000, 6_300_000),
    Track("s3", Uri.EMPTY, "Forklift Serenade", "Pallet Jack", "", 263_000, 8_400_000),
    Track("s4", Uri.EMPTY, "Safety Glasses On", "Hi-Vis Club", "", 225_000, 7_200_000),
    Track("s5", Uri.EMPTY, "Overtime (Extended Mix)", "Punch Clock", "", 412_000, 13_100_000),
    Track("s6", Uri.EMPTY, "Hinge Theory", "Twin Screens", "", 187_000, 6_000_000),
    Track("s7", Uri.EMPTY, "Breakroom Disco", "Vending Machine", "", 205_000, 6_600_000),
    Track("s8", Uri.EMPTY, "Lunch Whistle", "The Foremen", "", 176_000, 5_600_000),
)

private val SAMPLE_STATE = PlayerUiState(
    playlist = SAMPLE_TRACKS, currentIndex = 5, isPlaying = true, positionMs = 72_000, durationMs = 187_000,
    shuffle = false, repeat = RepeatMode.ALL, volume = 0.7f, balance = 0f, sampleRate = 44100, channels = 2,
    libraryLoaded = true, connected = true,
)
private val SAMPLE_EQ = EqState(bands = listOf(4f, 3f, 1f, -1f, -2f, 0f, 2f, 4f, 5f, 5f), presetName = "Preview")

private enum class StudioTab(val label: String) { QUICK("QUICK START"), COLORS("COLOURS"), SHAPE("SHAPES & FONT"), TEXTURE("TEXTURE & PICTURE"), VIS("VISUALIZER"), FILE("SAVE & SHARE") }

@Composable
fun SkinStudioScreen(start: Skin, fold: FoldingFeature?, onClose: () -> Unit) {
    // Editing a built-in skin always works on a copy, so the originals stay intact.
    var draft by remember {
        mutableStateOf(
            if (start.builtIn) start.copy(id = UUID.randomUUID().toString(), builtIn = false, name = "My ${start.name}", author = "")
            else start
        )
    }
    var dirty by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    fun edit(s: Skin) { draft = s; dirty = true }

    val close: () -> Unit = { if (dirty) { confirmClose = true } else { onClose() } }
    // The studio's own buttons and menus use the skin you're currently using, so they stay readable while you experiment.
    val chrome by SkinRepository.current.collectAsState()

    CompositionLocalProvider(LocalSkin provides chrome) {
        Box(Modifier.fillMaxSize().background(lerpColor(chrome.c(Slot.FRAME_DARK), Color.Black, 0.6f)).padding(4.dp)) {
            AdaptiveLayout(
                fold = fold,
                first = { Editor(draft, ::edit, onSaved = { draft = it; dirty = false }, onClose = close) },
                second = { Preview(draft, Modifier.fillMaxSize()) },
                single = {
                    Column(Modifier.fillMaxSize()) {
                        Preview(draft, Modifier.fillMaxWidth().height(280.dp))
                        Spacer(Modifier.height(4.dp))
                        Editor(draft, ::edit, onSaved = { draft = it; dirty = false }, onClose = close, modifier = Modifier.weight(1f))
                    }
                }
            )
        }

        if (confirmClose) {
            SkinDialog(
                title = "Unsaved changes",
                onDismiss = { confirmClose = false },
                maxWidth = 400.dp,
                buttons = {
                    TextButtonSkin("DISCARD") { confirmClose = false; onClose() }
                    TextButtonSkin("SAVE & USE") {
                        val saved = SkinRepository.save(draft)
                        SkinRepository.apply(saved)
                        confirmClose = false; onClose()
                    }
                },
            ) {
                BodyText("Leave without saving?", bold = true)
                BodyText("Your changes to \"${draft.name}\" haven't been saved yet.")
            }
        }
    }
}

// ------------------------------------------------------------------ preview

@Composable
private fun Preview(skin: Skin, modifier: Modifier) {
    CompositionLocalProvider(LocalSkin provides skin) {
        Column(
            modifier
                .background(lerpColor(skin.c(Slot.FRAME_DARK), Color.Black, 0.6f))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MainPanel(SAMPLE_STATE, NoActions, eqVisible = true, playlistVisible = true, Modifier.fillMaxWidth(), demoVis = true)
            VisualizerPanel(true, Modifier.fillMaxWidth().height(110.dp), demo = true)
            EqPanel(SAMPLE_EQ, NoActions, Modifier.fillMaxWidth())
            PlaylistPanel(SAMPLE_STATE, NoActions, hasPermission = true, modifier = Modifier.fillMaxWidth().height(240.dp))
        }
    }
}

// ------------------------------------------------------------------ editor

@Composable
private fun Editor(
    draft: Skin,
    onEdit: (Skin) -> Unit,
    onSaved: (Skin) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    var tab by remember { mutableIntStateOf(0) }
    SkinPanel("Skin Studio", modifier, onClose = onClose) {
        Row(verticalAlignment = Alignment.Bottom) {
            SkinTextField(draft.name, { onEdit(draft.copy(name = it.take(40))) }, "Skin name", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            TextButtonSkin("SAVE & USE", Modifier.height(36.dp)) {
                val saved = SkinRepository.save(draft)
                SkinRepository.apply(saved)
                onSaved(saved)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioTab.entries.forEachIndexed { i, t ->
                TextButtonSkin(t.label, lit = tab == i, size = 8.sp) { tab = i }
            }
        }
        Spacer(Modifier.height(6.dp))
        DisplayBox(Modifier.fillMaxWidth().weight(1f), bg = LocalSkin.current.c(Slot.FRAME).let { darken(it, 0.15f) }) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (StudioTab.entries[tab]) {
                    StudioTab.QUICK -> QuickTab(draft, onEdit)
                    StudioTab.COLORS -> ColorsTab(draft, onEdit, Slot.entries.filter { it.group != "Visualizer" })
                    StudioTab.SHAPE -> ShapeTab(draft, onEdit)
                    StudioTab.TEXTURE -> TextureTab(draft, onEdit)
                    StudioTab.VIS -> VisTab(draft, onEdit)
                    StudioTab.FILE -> FileTab(draft, onEdit, onSaved)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        options.forEach { o ->
            TextButtonSkin(label(o).uppercase(), lit = o == selected, size = 8.sp) { onPick(o) }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    BodyText(label)
    SkinSlider(value, onChange, Modifier.fillMaxWidth())
}

@Composable
private fun ColorRow(label: String, argb: Long, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val shape = skin.shape(true)
        Box(
            Modifier
                .size(width = 44.dp, height = 30.dp)
                .clip(shape)
                .background(Color(argb))
                .bevel(skin, shape, sunken = true, width = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        BodyText(label, Modifier.weight(1f), maxLines = 1)
        BodyText(Skin.toHex(argb).replace("#FF", "#"), size = 13.sp, color = skin.c(Slot.TITLE_TEXT).copy(alpha = 0.7f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickTab(draft: Skin, onEdit: (Skin) -> Unit) {
    val skins by SkinRepository.skins.collectAsState()
    var panel by remember { mutableStateOf(draft.color(Slot.FRAME)) }
    var glow by remember { mutableStateOf(draft.color(Slot.DISPLAY_TEXT)) }
    var picking by remember { mutableStateOf<Int?>(null) }

    SectionHeader("1. Start from a skin")
    HintText("Copies that skin's look into this one. Your skin's name stays the same.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        skins.forEach { s ->
            Column(
                Modifier.clickable {
                    onEdit(s.copy(id = draft.id, name = draft.name, builtIn = false, author = draft.author))
                    panel = s.color(Slot.FRAME); glow = s.color(Slot.DISPLAY_TEXT)
                }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SkinSwatch(s, Modifier.size(width = 72.dp, height = 40.dp))
                BodyText(s.name, size = 12.sp, maxLines = 1)
            }
        }
    }
    SectionHeader("2. Or build from two colours")
    HintText("Pick a panel colour and a glow colour (for the display and lights). Duo-Deck works out the rest.")
    ColorRow("Panel colour", panel) { picking = 0 }
    ColorRow("Glow colour", glow) { picking = 1 }
    TextButtonSkin("GENERATE COLOURS") { onEdit(SkinGenerator.generate(draft, panel, glow)) }
    HintText("Then fine-tune any single colour on the Colours tab.")

    picking?.let { which ->
        ColorPickerDialog(
            title = if (which == 0) "Panel colour" else "Glow colour",
            initial = if (which == 0) panel else glow,
            onDismiss = { picking = null },
            onPick = { c -> if (which == 0) panel = c else glow = c; picking = null }
        )
    }
}

@Composable
private fun ColorsTab(draft: Skin, onEdit: (Skin) -> Unit, slots: List<Slot>) {
    var editing by remember { mutableStateOf<Slot?>(null) }
    slots.groupBy { it.group }.forEach { (group, items) ->
        SectionHeader(group)
        items.forEach { s -> ColorRow(s.label, draft.color(s)) { editing = s } }
    }
    editing?.let { s ->
        ColorPickerDialog(s.label, draft.color(s), onDismiss = { editing = null }, onPick = { onEdit(draft.withColor(s, it)); editing = null })
    }
}

@Composable
private fun ShapeTab(draft: Skin, onEdit: (Skin) -> Unit) {
    SectionHeader("Button shape")
    ChipRow(ButtonShape.entries, draft.buttonShape, { it.label }) { onEdit(draft.copy(buttonShape = it)) }
    LabeledSlider("Corner size: ${draft.cornerDp}", draft.cornerDp / 20f) { onEdit(draft.copy(cornerDp = (it * 20).toInt())) }
    SectionHeader("3D look")
    LabeledSlider("Bevel depth: ${draft.bevelDp}  (0 = flat, 4 = chunky)", draft.bevelDp / 4f) { onEdit(draft.copy(bevelDp = (it * 4 + 0.5f).toInt().coerceIn(0, 4))) }
    LabeledSlider("Shine: ${(draft.shine * 100).toInt()}%  (metal & gloss)", draft.shine) { onEdit(draft.copy(shine = it)) }
    SectionHeader("Font")
    val skin = LocalSkin.current
    SkinFont.entries.forEach { f ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (draft.font == f) skin.c(Slot.LIST_SELECT_BG) else Color.Transparent)
                .clickable { onEdit(draft.copy(font = f)) }
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BodyText(f.label, Modifier.width(90.dp), color = if (draft.font == f) skin.c(Slot.LIST_CURRENT) else null)
            Text(
                "PLAY 12:34 SHUFFLE",
                color = if (draft.font == f) skin.c(Slot.LIST_CURRENT) else skin.c(Slot.TITLE_TEXT),
                style = TextStyle(fontFamily = draft.copy(font = f).fontFamily(), fontSize = 15.sp)
            )
        }
    }
    SectionHeader("Title bars")
    ChipRow(TitleStyle.entries, draft.titleStyle, { it.label }) { onEdit(draft.copy(titleStyle = it)) }
}

@Composable
private fun TextureTab(draft: Skin, onEdit: (Skin) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { SkinRepository.importImage(uri) }
            if (path != null) onEdit(draft.copy(bgImage = path))
            else Toast.makeText(ctx, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
        }
    }
    SectionHeader("Background picture")
    HintText("Use any photo as the panel background: your car, your team logo, wood grain, anything.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButtonSkin(if (draft.bgImage == null) "PICK PICTURE" else "CHANGE PICTURE") {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (draft.bgImage != null) TextButtonSkin("REMOVE") { onEdit(draft.copy(bgImage = null)) }
    }
    if (draft.bgImage != null) {
        LabeledSlider("Picture strength: ${(draft.bgImageAlpha * 100).toInt()}%", draft.bgImageAlpha) { onEdit(draft.copy(bgImageAlpha = it)) }
    }
    SectionHeader("Panel texture")
    ChipRow(Texture.entries, draft.texture, { it.label }) { onEdit(draft.copy(texture = it)) }
    LabeledSlider("Texture strength: ${(draft.textureStrength * 100).toInt()}%", draft.textureStrength) { onEdit(draft.copy(textureStrength = it)) }
}

@Composable
private fun VisTab(draft: Skin, onEdit: (Skin) -> Unit) {
    SectionHeader("Visualizer style")
    ChipRow(VisStyle.entries, draft.visStyle, { it.label }) { onEdit(draft.copy(visStyle = it)) }
    ColorsTab(draft, onEdit, Slot.entries.filter { it.group == "Visualizer" })
}

@Composable
private fun FileTab(draft: Skin, onEdit: (Skin) -> Unit, onSaved: (Skin) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val skin = withContext(Dispatchers.IO) { SkinRepository.importSkin(uri) }
            if (skin != null) {
                onSaved(skin)
                Toast.makeText(ctx, "Imported \"${skin.name}\"", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(ctx, "That file isn't a Duo-Deck skin", Toast.LENGTH_LONG).show()
        }
    }
    SkinTextField(draft.author, { onEdit(draft.copy(author = it.take(40))) }, "Made by (optional)", Modifier.fillMaxWidth())
    SectionHeader("Save")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButtonSkin("SAVE & USE") {
            val s = SkinRepository.save(draft); SkinRepository.apply(s); onSaved(s)
            Toast.makeText(ctx, "Saved and applied", Toast.LENGTH_SHORT).show()
        }
        TextButtonSkin("SAVE AS COPY") {
            val copy = draft.copy(id = UUID.randomUUID().toString(), name = draft.name + " copy")
            val s = SkinRepository.save(copy); onSaved(s)
            Toast.makeText(ctx, "Saved a copy", Toast.LENGTH_SHORT).show()
        }
    }
    SectionHeader("Share")
    HintText("Sends your skin as one small file (picture included). Anyone with Duo-Deck can import it.")
    TextButtonSkin("SHARE SKIN FILE") {
        val s = SkinRepository.save(draft); onSaved(s)
        ctx.startActivity(SkinRepository.shareIntent(s))
    }
    SectionHeader("Import")
    HintText("Open a .duodeck.json skin file someone sent you.")
    TextButtonSkin("IMPORT SKIN FILE") { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
}
