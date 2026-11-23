package com.duodeck.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duodeck.player.PlayerUiState
import com.duodeck.player.data.RadioApi
import com.duodeck.player.data.RadioNow
import com.duodeck.player.data.RadioStation
import com.duodeck.player.data.RadioStore
import com.duodeck.player.skin.Slot
import kotlinx.coroutines.launch
import java.util.Locale

private enum class RadioMode(val label: String) { MINE("MY STATIONS"), POPULAR("POPULAR"), LOCAL("NEAR ME"), GENRES("GENRES"), SEARCH("SEARCH") }

private val GENRES = listOf(
    "rock", "classic rock", "pop", "country", "hip hop", "rnb", "jazz", "blues", "classical",
    "dance", "electronic", "house", "metal", "alternative", "indie", "80s", "90s", "70s", "60s",
    "oldies", "soul", "funk", "reggae", "latin", "christian", "gospel", "chillout", "lounge",
    "ambient", "news", "talk", "sports", "comedy", "kids", "soundtrack",
)

/** The RADIO tab: find and play free internet radio stations. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioBrowser(state: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val saved by RadioStore.saved.collectAsState()
    val song by RadioNow.songTitle.collectAsState()
    val playError by RadioNow.error.collectAsState()

    var mode by rememberSaveable { mutableStateOf(if (RadioStore.saved.value.isEmpty()) RadioMode.POPULAR else RadioMode.MINE) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchFor by rememberSaveable { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<RadioStation?>(null) }
    // a fresh list (scrolled to the top) for each new lookup
    val listState = remember(mode, genre, searchFor) { androidx.compose.foundation.lazy.LazyListState() }

    // Fetch from the directory whenever what we're looking at changes.
    LaunchedEffect(mode, genre, searchFor, retry) {
        error = null
        val job: (suspend () -> List<RadioStation>)? = when (mode) {
            RadioMode.MINE -> null
            RadioMode.POPULAR -> ({ RadioApi.top() })
            RadioMode.LOCAL -> {
                val cc = Locale.getDefault().country.ifBlank { "US" }
                ({ RadioApi.byCountry(cc) })
            }
            RadioMode.GENRES -> genre?.let { g -> { RadioApi.byTag(g) } }
            RadioMode.SEARCH -> searchFor.takeIf { it.isNotBlank() }?.let { q -> { RadioApi.search(q) } }
        }
        if (job == null) { results = emptyList(); loading = false; return@LaunchedEffect }
        loading = true
        results = emptyList()
        try {
            results = job()
        } catch (e: Exception) {
            error = "COULDN'T REACH THE RADIO DIRECTORY.\nCHECK YOUR INTERNET CONNECTION,\nTHEN TAP HERE TO TRY AGAIN."
        }
        loading = false
    }

    val showGenreList = mode == RadioMode.GENRES && genre == null
    val stations = if (mode == RadioMode.MINE) saved else results

    fun playAt(list: List<RadioStation>, i: Int) {
        RadioNow.error.value = null
        actions.playTracks(list.map { it.toTrack() }, i)
        val uuid = list[i].uuid
        scope.launch { RadioApi.click(uuid) }
    }

    Column(modifier) {
        // ---- mode buttons
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            RadioMode.entries.forEach { m ->
                TextButtonSkin(m.label, lit = mode == m, size = 7.sp) {
                    if (m == RadioMode.GENRES && mode == RadioMode.GENRES) genre = null
                    mode = m
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (mode == RadioMode.SEARCH) {
            Row(verticalAlignment = Alignment.Bottom) {
                SkinTextField(query, { query = it.take(40) }, "Station name, like \"jazz\" or \"BBC\"", Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                TextButtonSkin("GO", size = 9.sp) { searchFor = query.trim(); retry++ }
            }
            Spacer(Modifier.height(4.dp))
        } else if (mode == RadioMode.GENRES && genre != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButtonSkin("< GENRES", size = 7.sp) { genre = null }
                Spacer(Modifier.width(6.dp))
                BodyText(genre!!.uppercase(), bold = true, maxLines = 1, size = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
        }

        // ---- the list
        Box(Modifier.fillMaxWidth().weight(1f)) {
            DisplayBox(Modifier.fillMaxSize(), bg = sc(Slot.LIST_BG)) {
                val textStyle = TextStyle(fontFamily = skin.listFamily(), fontSize = (13 * skin.listScale()).sp)
                val msg = when {
                    showGenreList -> null
                    loading -> "TUNING IN...\nLOOKING UP STATIONS"
                    error != null -> error
                    stations.isNotEmpty() -> null
                    mode == RadioMode.MINE -> "NO SAVED STATIONS YET.\nFIND ONE IN POPULAR, NEAR ME, GENRES\nOR SEARCH, THEN LONG-PRESS IT\nAND CHOOSE SAVE TO MY STATIONS."
                    mode == RadioMode.SEARCH && searchFor.isBlank() -> "TYPE A STATION NAME AND TAP GO."
                    else -> "NO STATIONS FOUND."
                }
                if (msg != null) {
                    Box(
                        Modifier.fillMaxSize().combinedClickable(enabled = error != null, onClick = { retry++ }),
                        contentAlignment = Alignment.Center,
                    ) { Text(msg, color = sc(Slot.LIST_TEXT), style = textStyle, textAlign = TextAlign.Center) }
                } else if (showGenreList) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(GENRES, key = { _, g -> "g$g" }) { _, g ->
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = { genre = g }).padding(horizontal = 3.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TransportIcon(Icon.PLAY, sc(Slot.LIST_TEXT).copy(alpha = 0.6f), Modifier.width(6.dp).height(8.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(g.uppercase(), color = sc(Slot.LIST_TEXT), style = textStyle, maxLines = 1)
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        itemsIndexed(stations, key = { _, s -> "r${s.uuid}" }) { i, s ->
                            val playing = state.current?.id == "radio:${s.uuid}"
                            val color = if (playing) sc(Slot.LIST_CURRENT) else sc(Slot.LIST_TEXT)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (playing) sc(Slot.LIST_SELECT_BG) else Color.Transparent)
                                    .combinedClickable(onClick = { playAt(stations, i) }, onLongClick = { menu = s })
                                    .padding(horizontal = 3.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (s.uuid in saved.map { it.uuid }) {
                                    Text("*", color = sc(Slot.ACCENT), style = textStyle)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(s.name.ifBlank { "Station" }.uppercase(), color = color, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(s.info, color = color, style = textStyle, modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
            // long-press menu on a station
            val m = menu
            SkinMenu(
                expanded = m != null,
                onDismiss = { menu = null },
                alignment = Alignment.Center,
                items = if (m == null) emptyList() else buildList {
                    add(SkinMenuItem("Play now") { val i = stations.indexOf(m); if (i >= 0) playAt(stations, i) else playAt(listOf(m), 0) })
                    if (RadioStore.isSaved(m.uuid)) add(SkinMenuItem("Remove from my stations") { RadioStore.remove(m.uuid) })
                    else add(SkinMenuItem("Save to my stations") { RadioStore.save(m) })
                    add(SkinMenuItem("Add to queue") { actions.queueTracks(listOf(m.toTrack()), next = false) })
                },
            )
        }

        Spacer(Modifier.height(4.dp))
        // ---- footer: what's on now
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Sleep timer shortcut: each tap steps 15 > 30 > 45 > 60 > 90 > off.
            val sleep by com.duodeck.player.SleepTimer.state.collectAsState()
            val steps = listOf(15, 30, 45, 60, 90)
            val sleepOn = sleep.endAt > 0L
            TextButtonSkin(if (sleepOn) "SLEEP ${sleep.minutes}" else "SLEEP", lit = sleepOn, size = 7.sp) {
                val next = if (!sleepOn) steps.first() else steps.firstOrNull { it > sleep.minutes }
                if (next == null) com.duodeck.player.SleepTimer.cancel() else com.duodeck.player.SleepTimer.start(next)
            }
            Spacer(Modifier.width(6.dp))
            val cur = state.current?.takeIf { it.isRadio }
            if (cur != null) {
                val uuid = cur.id.removePrefix("radio:")
                val isSaved = saved.any { it.uuid == uuid }
                TextButtonSkin(if (isSaved) "SAVED" else "SAVE", lit = isSaved, size = 7.sp) {
                    if (isSaved) RadioStore.remove(uuid)
                    else (stations.firstOrNull { it.uuid == uuid }
                        ?: RadioStation(uuid, cur.title, cur.uri.toString(), bitrate = cur.kbps)).let { RadioStore.save(it) }
                }
                Spacer(Modifier.width(6.dp))
            }
            DisplayBox(Modifier.weight(1f).height(28.dp)) {
                Column {
                    val top = when {
                        playError != null -> "STATION ERROR"
                        cur != null -> "ON AIR: ${cur.title}"
                        else -> "INTERNET RADIO"
                    }
                    val bottom = when {
                        playError != null -> playError!!
                        cur != null -> song ?: "LIVE"
                        else -> "${stations.size} STATIONS"
                    }
                    SkinText(top.uppercase(), sc(Slot.DISPLAY_TEXT), 8.sp, weight = FontWeight.Bold, maxLines = 1)
                    SkinText(bottom.uppercase(), sc(Slot.DISPLAY_TEXT), 8.sp, maxLines = 1)
                }
            }
        }
    }
}
