package com.duodeck.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import com.duodeck.player.EqPreset
import com.duodeck.player.PlayerViewModel
import com.duodeck.player.data.Track
import com.duodeck.player.skin.Skin
import com.duodeck.player.skin.SkinRepository
import com.duodeck.player.skin.Slot

@Composable
fun DuoDeckApp(
    vm: PlayerViewModel,
    fold: FoldingFeature?,
    rotation: Int,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPickFiles: () -> Unit,
) {
    val chosenSkin by SkinRepository.current.collectAsState()
    val perf by com.duodeck.player.PerfSettings.values.collectAsState()
    // Performance options can switch off textures and gloss without changing the saved skin.
    val skin = remember(chosenSkin, perf.textures, perf.gloss) {
        chosenSkin.copy(
            texture = if (perf.textures) chosenSkin.texture else com.duodeck.player.skin.Texture.NONE,
            shine = if (perf.gloss) chosenSkin.shine else 0f,
        )
    }
    var studioOpen by rememberSaveable { mutableStateOf(false) }
    var studioStart by remember { mutableStateOf<Skin?>(null) }

    var showSplash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1800)
        showSplash = false
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
      CompositionLocalProvider(LocalFold provides fold) {
      Box(Modifier.fillMaxSize().background(Color.Black)) {
      RotatedBox(rotation) {
        if (studioOpen) {
            BackHandler { studioOpen = false }
            SkinStudioScreen(
                start = studioStart ?: chosenSkin,
                fold = fold,
                onClose = { studioOpen = false },
            )
        } else {
            CompositionLocalProvider(LocalSkin provides skin) {
                PlayerScreen(
                    vm = vm, fold = fold, hasPermission = hasPermission,
                    onRequestPermission = onRequestPermission, onPickFiles = onPickFiles,
                    onOpenStudio = { s -> studioStart = s; studioOpen = true },
                )
            }
        }
        // Splash screen artwork, fades out after launch
        androidx.compose.animation.AnimatedVisibility(
            visible = showSplash,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(600)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.duodeck.player.R.drawable.splash_full),
                    contentDescription = "Duo-Deck",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
      }
      }
      }
    }
}

@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    fold: FoldingFeature?,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenStudio: (Skin?) -> Unit,
) {
    val state by vm.state.collectAsState()
    val eq by vm.eq.collectAsState()
    val library by vm.library.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val skin = LocalSkin.current
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    var eqVisible by rememberSaveable { mutableStateOf(Setup.eqShown(ctx0)) }
    var playlistVisible by rememberSaveable { mutableStateOf(Setup.playlistShown(ctx0)) }
    LaunchedEffect(eqVisible, playlistVisible) {
        Setup.setEqShown(ctx0, eqVisible); Setup.setPlaylistShown(ctx0, playlistVisible)
    }
    val setupValues by Setup.values.collectAsState()
    var setupOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var chooserOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var perfOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // keep the playing track in view when it changes
    LaunchedEffect(state.currentIndex) {
        if (state.currentIndex >= 0 && state.playlist.isNotEmpty()) {
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (state.currentIndex !in visible) listState.scrollToItem((state.currentIndex - 3).coerceAtLeast(0))
        }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    var layout by remember { mutableStateOf(SectionLayout.load(ctx)) }
    val pickFiles by androidx.compose.runtime.rememberUpdatedState(onPickFiles)
    val askPermission by androidx.compose.runtime.rememberUpdatedState(onRequestPermission)
    // Built once (not on every redraw) so panels that haven't changed can skip redrawing.
    val actions = remember(vm) { object : PlayerActions {
        override fun play() = vm.play()
        override fun togglePause() = vm.togglePause()
        override fun stop() = vm.stop()
        override fun next() = vm.next()
        override fun previous() = vm.previous()
        override fun seek(fraction: Float) = vm.seekTo(fraction)
        override fun setVolume(v: Float) = vm.setVolume(v)
        override fun setBalance(b: Float) = vm.setBalance(b)
        override fun toggleShuffle() = vm.toggleShuffle()
        override fun cycleRepeat() = vm.cycleRepeat()
        override fun playIndex(i: Int) = vm.playIndex(i)
        override fun addFiles() = pickFiles()
        override fun cycleSort(): String = vm.cycleSort().label
        override fun toggleEqPanel() { eqVisible = !eqVisible }
        override fun togglePlaylist() { playlistVisible = !playlistVisible }
        override fun openMenu() { menuOpen = true }
        override fun setEqEnabled(on: Boolean) = vm.setEqEnabled(on)
        override fun setPreamp(db: Float) = vm.setPreamp(db)
        override fun setBand(i: Int, db: Float) = vm.setBand(i, db)
        override fun applyPreset(p: EqPreset) = vm.applyPreset(p)
        override fun requestPermission() = askPermission()
        override fun playTracks(tracks: List<Track>, start: Int) = vm.playTracks(tracks, start)
        override fun queueTracks(tracks: List<Track>, next: Boolean) = vm.queueTracks(tracks, next)
        override fun removeFromQueue(index: Int) = vm.removeAt(index)
        override fun clearQueue() = vm.clearQueue()
        override fun createPlaylist(name: String, tracks: List<Track>) { vm.createPlaylist(name, tracks) }
        override fun addToPlaylist(id: String, tracks: List<Track>) = vm.addToPlaylist(id, tracks)
        override fun removeFromPlaylist(id: String, index: Int) = vm.removeFromPlaylist(id, index)
        override fun deletePlaylist(id: String) = vm.deletePlaylist(id)
        override fun swapScreens() { layout = layout.swapped(); SectionLayout.save(ctx, layout) }
    } }

    val backdrop = lerpColor(skin.c(Slot.FRAME_DARK), Color.Black, 0.6f)

    // ---- drag-and-drop section layout
    val drag = remember { SectionDragState() }
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }
    fun isShown(s: Section) = when (s) {
        Section.EQ -> eqVisible
        Section.PLAYLIST -> playlistVisible
        else -> true
    }
    val onMove: () -> Unit = { drag.target = drag.computeTarget(layout, ::isShown, gapPx) }
    val onDrop: () -> Unit = {
        val s = drag.dragging
        val t = drag.computeTarget(layout, ::isShown, gapPx)
        if (s != null && t != null) {
            layout = layout.move(s, t.pane, t.index)
            SectionLayout.save(ctx, layout)
        }
    }

    // Each section is "movable": dragging it to the other screen moves it instead of rebuilding it,
    // so lists, tabs and scroll positions are kept.
    val sectionUi = androidx.compose.runtime.rememberUpdatedState<@Composable (Section, Modifier) -> Unit> { sec, m ->
        val handle = Modifier.sectionDragHandle(sec, drag, onMove, onDrop)
        when (sec) {
            Section.MAIN -> MainPanel(state, actions, eqVisible, playlistVisible, m, progressFlow = vm.progress, titleModifier = handle)
            Section.EQ -> EqPanel(eq, actions, m, titleModifier = handle)
            Section.VIS -> VisualizerPanel(state.isPlaying, m, titleModifier = handle, styleKey = "panel")
            Section.PLAYLIST -> PlaylistPanel(state, actions, hasPermission, m, listState, library, playlists, titleModifier = handle)
        }
    }
    val movable = remember {
        Section.entries.associateWith { sec ->
            androidx.compose.runtime.movableContentOf<Modifier> { m -> sectionUi.value(sec, m) }
        }
    }

    @Composable
    fun ColumnScope.sections(list: List<Section>, fillerBars: Int) {
        val shown = list.filter { isShown(it) }
        shown.forEachIndexed { i, sec ->
            if (i > 0) Spacer(Modifier.height(4.dp))
            val size = when (sec) {
                Section.VIS -> Modifier.weight(1f).heightIn(min = 60.dp)
                Section.PLAYLIST -> Modifier.weight(2.5f)
                else -> Modifier
            }
            movable.getValue(sec)(
                Modifier
                    .fillMaxWidth()
                    .then(size)
                    .onGloballyPositioned { drag.sectionCoords[sec] = it }
                    .graphicsLayer { alpha = if (drag.dragging == sec) 0.4f else 1f }
            )
        }
        // nothing on this screen: fill it with a big visualizer
        if (shown.isEmpty() && setupValues.filler) VisualizerPanel(state.isPlaying, Modifier.fillMaxWidth().weight(1f), bars = fillerBars, styleKey = "big")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop)
            .padding(4.dp)
            .onGloballyPositioned { drag.container = it }
    ) {
        AdaptiveLayout(
            fold = fold,
            first = {
                drag.split = true
                Column(Modifier.fillMaxSize().onGloballyPositioned { drag.paneCoords[0] = it }) { sections(layout.panes[0], 32) }
            },
            second = {
                Column(Modifier.fillMaxSize().onGloballyPositioned { drag.paneCoords[1] = it }) { sections(layout.panes[1], 48) }
            },
            single = {
                drag.split = false
                Column(Modifier.fillMaxSize().onGloballyPositioned { drag.paneCoords[0] = it }) {
                    sections(layout.panes[0] + layout.panes[1], 32)
                }
            }
        )
        SectionDragOverlay(drag)

        // ---- main menu (top-left button on the main panel)
        val laptopAuto by com.duodeck.player.LaptopView.auto.collectAsState()
        Box(Modifier.padding(top = 20.dp, start = 8.dp)) {
            SkinMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                items = listOf(
                    SkinMenuItem("Skin Studio") { onOpenStudio(null) },
                    SkinMenuItem("Choose skin") { chooserOpen = true },
                    SkinMenuItem("Add song files") { onPickFiles() },
                    SkinMenuItem("Rescan music library") { vm.loadLibrary() },
                    SkinMenuItem(if (eqVisible) "Hide equalizer" else "Show equalizer") { eqVisible = !eqVisible },
                    SkinMenuItem("Sleep timer") { sleepOpen = true },
                    SkinMenuItem("Setup (my layout)") { setupOpen = true },
                    SkinMenuItem("Performance & screen") { perfOpen = true },
                    *(if (LocalManualRotation.current != 0) arrayOf(SkinMenuItem("Flip laptop view (upside down?)") { com.duodeck.player.LaptopView.flip() }) else emptyArray()),
                    *(if (LocalFold.current?.isSeparating == true) arrayOf(
                        SkinMenuItem(if (laptopAuto) "Laptop auto-turn: ON" else "Laptop auto-turn: OFF") { com.duodeck.player.LaptopView.setAuto(!laptopAuto) }
                    ) else emptyArray()),
                    SkinMenuItem("Reset layout") { layout = SectionLayout.DEFAULT; SectionLayout.save(ctx, layout); VisStyles.clearAll() },
                    SkinMenuItem("About Duo-Deck") { aboutOpen = true },
                ),
            )
        }
    }

    if (chooserOpen) {
        SkinChooserDialog(
            onDismiss = { chooserOpen = false },
            onEdit = { s -> chooserOpen = false; onOpenStudio(s) },
        )
    }
    if (perfOpen) PerformanceDialog { perfOpen = false }
    if (sleepOpen) SleepTimerDialog { sleepOpen = false }
    if (setupOpen) SetupDialog(
        eqVisible = eqVisible,
        playlistVisible = playlistVisible,
        onEq = { eqVisible = it },
        onPlaylist = { playlistVisible = it },
        onSave = { Setup.saveMine(ctx, layout, eqVisible, playlistVisible) },
        onLoad = {
            Setup.loadMine(ctx)?.let { layout = it }
            eqVisible = Setup.eqShown(ctx); playlistVisible = Setup.playlistShown(ctx)
        },
        onFactory = {
            layout = SectionLayout.DEFAULT; SectionLayout.save(ctx, layout); VisStyles.clearAll()
            eqVisible = true; playlistVisible = true; Setup.setFiller(ctx, true)
        },
        onDismiss = { setupOpen = false },
    )
    if (aboutOpen) {
        SkinDialog(
            title = "About Duo-Deck",
            onDismiss = { aboutOpen = false },
            buttons = { TextButtonSkin("OK") { aboutOpen = false } },
        ) {
            BodyText("DUO-DECK 1.0", bold = true, color = skin.c(Slot.ACCENT))
            BodyText("A retro desktop-style music player for the Surface Duo, with a Skin Studio for building your own looks.")
            BodyText("TIPS", bold = true)
            BodyText("- Span the app across both screens: controls on one, playlist on the other.")
            BodyText("- Fold into laptop mode: controls on top, playlist below. Lay it flat again for side by side.")
            BodyText("- Tap the clock to switch elapsed / remaining time.")
            BodyText("- The menu button (top left) opens the Skin Studio.")
            BodyText("- SWAP (next to SHUFFLE) flips what is on the two screens.")
            BodyText("- Tap a visualizer to change its style. Each one remembers its own look.")
            BodyText("- Press and hold any section's title bar, then drag it to move it, even to the other screen. Menu > Reset layout puts everything back.")
            BodyText("- Menu > Setup saves your layout so Duo-Deck always opens that way, in any skin.")
            BodyText("SUPPORT DUO-DECK", bold = true)
            BodyText("Duo-Deck is free. If you enjoy it and want to buy the maker a coffee, thank you!")
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButtonSkin("CASH APP  \$Iamdoubled", size = 9.sp) {
                    runCatching { uri.openUri("https://cash.app/\$Iamdoubled") }
                }
                TextButtonSkin("VENMO  @Iamdoubled", size = 9.sp) {
                    runCatching { uri.openUri("https://venmo.com/u/Iamdoubled") }
                }
            }
            HintText("Donations are optional. Duo-Deck never asks for payment.")
            HintText("Fonts: VT323 and Silkscreen (SIL Open Font License).")
        }
    }
}

@Composable
fun SkinChooserDialog(onDismiss: () -> Unit, onEdit: (Skin) -> Unit) {
    val skins by SkinRepository.skins.collectAsState()
    val current by SkinRepository.current.collectAsState()
    var confirmDelete by remember { mutableStateOf<Skin?>(null) }
    SkinDialog(
        title = "Skins",
        onDismiss = onDismiss,
        buttons = { TextButtonSkin("DONE") { onDismiss() } },
    ) {
        skins.forEach { s ->
            val selected = s.id == current.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) sc(Slot.LIST_SELECT_BG) else Color.Transparent)
                    .clickable { SkinRepository.apply(s) }
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkinSwatch(s, Modifier.size(width = 64.dp, height = 38.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BodyText(s.name, bold = true, maxLines = 1, color = if (selected) sc(Slot.LIST_CURRENT) else null)
                    BodyText(
                        if (selected) "IN USE" else if (s.builtIn) "BUILT-IN" else "YOUR SKIN",
                        size = 12.sp, color = if (selected) sc(Slot.LIST_CURRENT) else sc(Slot.TITLE_TEXT).copy(alpha = 0.7f)
                    )
                }
                TextButtonSkin("EDIT", size = 8.sp) { onEdit(s) }
                if (!s.builtIn) {
                    Spacer(Modifier.width(4.dp))
                    TextButtonSkin("DEL", size = 8.sp) { confirmDelete = s }
                }
            }
        }
    }
    confirmDelete?.let { s ->
        SkinDialog(
            title = "Delete skin",
            onDismiss = { confirmDelete = null },
            maxWidth = 380.dp,
            buttons = {
                TextButtonSkin("CANCEL") { confirmDelete = null }
                TextButtonSkin("DELETE") { SkinRepository.delete(s); confirmDelete = null }
            },
        ) {
            BodyText("Delete \"${s.name}\"?", bold = true)
            BodyText("This removes it from this device. It can't be undone unless you shared a copy.")
        }
    }
}

/** Small preview tile of a skin's main colours. */
@Composable
fun SkinSwatch(s: Skin, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(4.dp)).background(s.c(Slot.FRAME)).padding(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(s.c(Slot.TITLE_BAR)))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.weight(1f).fillMaxSize().background(s.c(Slot.DISPLAY_BG)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 14.dp, height = 5.dp).background(s.c(Slot.DISPLAY_TEXT)))
            }
            Box(Modifier.weight(1f).fillMaxSize().background(s.c(Slot.LIST_BG))) {
                Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(s.c(Slot.LIST_SELECT_BG)))
            }
        }
        Box(Modifier.fillMaxWidth().height(5.dp).background(s.c(Slot.BUTTON)))
    }
}

/** Lets you switch off eye candy to make Duo-Deck lighter on the battery and processor. */
@Composable
fun PerformanceDialog(onDismiss: () -> Unit) {
    val perf by com.duodeck.player.PerfSettings.values.collectAsState()
    fun set(change: (com.duodeck.player.PerfSettings.Values) -> com.duodeck.player.PerfSettings.Values) =
        com.duodeck.player.PerfSettings.update(change)
    SkinDialog(
        title = "Performance",
        onDismiss = onDismiss,
        maxWidth = 440.dp,
        buttons = {
            TextButtonSkin("BEST LOOK") { set { it.copy(visFps = 60, miniVis = true, scrollTitle = true, textures = true, gloss = true) } }
            TextButtonSkin("BATTERY SAVER") {
                set { it.copy(visFps = 15, miniVis = false, scrollTitle = false, textures = false) }
            }
            TextButtonSkin("DONE") { onDismiss() }
        },
    ) {
        SectionHeader("Visualizer speed")
        HintText("How many times a second the bars redraw. 30 looks smooth and uses half the work of 60. OFF also stops the sound analysis.")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(60 to "60", 30 to "30", 15 to "15", 0 to "OFF").forEach { (v, label) ->
                TextButtonSkin(label, lit = perf.visFps == v, size = 9.sp) { set { it.copy(visFps = v) } }
            }
        }
        SectionHeader("Keep screen on while playing")
        HintText("Stops the screen going dark while music plays. 'Plugged in' only does it when charging, so it won't drain the battery.")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(0 to "NEVER", 1 to "PLUGGED IN", 2 to "ALWAYS").forEach { (v, label) ->
                TextButtonSkin(label, lit = perf.keepAwake == v, size = 9.sp) { set { it.copy(keepAwake = v) } }
            }
        }
        SectionHeader("Moving parts")
        PerfToggle("VU meters under the clock", perf.miniVis) { set { p -> p.copy(miniVis = it) } }
        PerfToggle("Scrolling song title", perf.scrollTitle) { set { p -> p.copy(scrollTitle = it) } }
        SectionHeader("Graphics")
        PerfToggle("Panel textures (carbon, dots, metal...)", perf.textures) { set { p -> p.copy(textures = it) } }
        PerfToggle("Shine and gloss effects", perf.gloss) { set { p -> p.copy(gloss = it) } }
        HintText("These don't change your saved skins. Turn them back on any time.")
        SectionHeader("Always on")
        HintText("- Visualizers stop when the music stops.")
        HintText("- Sound analysis pauses when Duo-Deck is off screen; music keeps playing.")
        HintText("- Only the clock and seek bar update while a song plays.")
    }
}

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val st by com.duodeck.player.SleepTimer.state.collectAsState()
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), st) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    SkinDialog(
        title = "Sleep timer",
        onDismiss = onDismiss,
        maxWidth = 440.dp,
        buttons = {
            if (st.active) TextButtonSkin("TURN OFF") { com.duodeck.player.SleepTimer.cancel() }
            TextButtonSkin("DONE") { onDismiss() }
        },
    ) {
        val status = when {
            st.endOfSong -> "Stops when this song ends."
            st.endAt > 0 -> {
                val left = ((st.endAt - now) / 1000).coerceAtLeast(0)
                "Stops in %d:%02d".format(left / 60, left % 60)
            }
            else -> "Off"
        }
        BodyText(status, bold = true, color = LocalSkin.current.c(Slot.ACCENT))
        HintText("The music fades out over the last 10 seconds, then pauses. It works with the screen off.")
        SectionHeader("Stop after (minutes)")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(15, 30, 45, 60, 90).forEach { m ->
                TextButtonSkin("$m", lit = st.minutes == m && st.endAt > 0, size = 9.sp) { com.duodeck.player.SleepTimer.start(m) }
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButtonSkin("END OF THIS SONG", lit = st.endOfSong, size = 9.sp) { com.duodeck.player.SleepTimer.endOfSong() }
    }
}

@Composable
fun SetupDialog(
    eqVisible: Boolean,
    playlistVisible: Boolean,
    onEq: (Boolean) -> Unit,
    onPlaylist: (Boolean) -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onFactory: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val v by Setup.values.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    SkinDialog(
        title = "Setup",
        onDismiss = onDismiss,
        maxWidth = 440.dp,
        buttons = { TextButtonSkin("DONE") { onDismiss() } },
    ) {
        HintText("Arrange things how you like: drag sections by their title bars, tap the visualizers to pick their styles, and choose what's shown below. Then save it. Your setup works with every skin.")
        SectionHeader("Show")
        PerfToggle("Equalizer", eqVisible, onEq)
        PerfToggle("Playlist", playlistVisible, onPlaylist)
        PerfToggle("Big visualizer on an empty screen", v.filler) { Setup.setFiller(ctx, it) }
        SectionHeader("My setup")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButtonSkin("SAVE AS MY SETUP", size = 9.sp) { onSave(); note = "Saved. This is now your setup." }
            if (v.hasSaved) TextButtonSkin("LOAD IT NOW", size = 9.sp) { onLoad(); note = "Your setup is back." }
        }
        note?.let { BodyText(it, bold = true, color = LocalSkin.current.c(Slot.ACCENT)) }
        if (!v.hasSaved) HintText("Nothing saved yet.")
        SectionHeader("When Duo-Deck opens")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            TextButtonSkin("MY SETUP", lit = v.startWithMine, size = 9.sp) {
                if (!v.hasSaved) { onSave(); note = "Saved what's on screen as your setup." }
                Setup.setStartWithMine(ctx, true)
            }
            TextButtonSkin("AS I LEFT IT", lit = !v.startWithMine, size = 9.sp) { Setup.setStartWithMine(ctx, false) }
        }
        HintText("MY SETUP: any moves you make are undone next time the app opens. AS I LEFT IT: opens the way you last had it.")
        SectionHeader("Start over")
        TextButtonSkin("FACTORY LAYOUT", size = 9.sp) { onFactory(); note = "Back to the original layout (your saved setup is kept)." }
    }
}

@Composable
private fun PerfToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BodyText(label, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextButtonSkin(if (on) "ON" else "OFF", lit = on, size = 9.sp) { onChange(!on) }
    }
}
