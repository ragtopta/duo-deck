package com.duodeck.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duodeck.player.PlayerUiState
import com.duodeck.player.data.SavedPlaylist
import com.duodeck.player.data.Track
import com.duodeck.player.skin.Slot
import kotlinx.coroutines.launch

enum class LibView(val label: String) { QUEUE("NOW PLAYING"), ARTISTS("ARTISTS"), ALBUMS("ALBUMS"), SONGS("SONGS"), FOLDERS("FOLDERS"), LISTS("PLAYLISTS"), RADIO("RADIO") }

/** A row in a library list: a song, or a group (artist / album / playlist). */
private class LibItem(
    val key: String,
    val title: String,
    val right: String,
    val tracks: List<Track>,
    val highlight: Boolean = false,
    /** Set on a saved playlist row so the long-press menu can rename/delete it. */
    val playlistId: String? = null,
    val onTap: () -> Unit,
)

/** Something the long-press menu acts on. */
private class MenuTarget(val title: String, val tracks: List<Track>, val queueIndex: Int? = null, val playlistId: String? = null, val playlistIndex: Int? = null, val wholePlaylist: Boolean = false)

private class Drill(val title: String, val tracks: List<Track>, val playlistId: String? = null, val emptyText: String? = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistPanel(
    state: PlayerUiState,
    actions: PlayerActions,
    hasPermission: Boolean,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    library: List<Track> = emptyList(),
    playlists: List<SavedPlaylist> = emptyList(),
    titleModifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    var view by rememberSaveable { mutableStateOf(LibView.QUEUE) }
    var drill by remember { mutableStateOf<Drill?>(null) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var addToList by remember { mutableStateOf<List<Track>?>(null) }
    var namingNew by remember { mutableStateOf<List<Track>?>(null) }
    var sortLabel by remember { mutableStateOf("SORT") }
    val browseState = rememberLazyListState()
    val stats by com.duodeck.player.data.MusicStats.stats.collectAsState()

    // ---- build the rows for the current view
    val items: List<LibItem> = remember(view, drill, state.playlist, state.currentIndex, library, playlists, stats) {
        val d = drill
        when {
            d != null -> d.tracks.mapIndexed { i, t ->
                LibItem("d$i-${t.id}", "${i + 1}. ${t.displayName}", formatLong(t.durationMs), listOf(t)) { actions.playTracks(d.tracks, i) }
            }
            view == LibView.QUEUE -> state.playlist.mapIndexed { i, t ->
                LibItem("q$i-${t.id}", "${i + 1}. ${t.displayName}", formatLong(t.durationMs), listOf(t), highlight = i == state.currentIndex) { actions.playIndex(i) }
            }
            // Group ignoring capitals ("AC/DC" and "Ac/Dc" are one artist) so no songs get dropped.
            view == LibView.ARTISTS -> library.groupBy { (it.artist.takeIf { a -> a.isNotBlank() && a != "<unknown>" } ?: "Unknown artist").trim().lowercase() }
                .toSortedMap().map { (key, songs) ->
                    val artist = songs.first().artist.takeIf { a -> a.isNotBlank() && a != "<unknown>" }?.trim() ?: "Unknown artist"

                    val sorted = songs.sortedWith(compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.album }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                    LibItem("a$key", artist, "${songs.size}", sorted) { drill = Drill(artist, sorted) }
                }
            view == LibView.ALBUMS -> library.groupBy { ((it.album.ifBlank { "Unknown album" }) + "\u0000" + it.artist).trim().lowercase() }
                .toSortedMap().map { (key, songs) ->
                    val album = songs.first().album.ifBlank { "Unknown album" }
                    val artist = songs.first().artist
                    val label = if (artist.isBlank() || artist == "<unknown>") album else "$album - $artist"
                    LibItem("al$key", label, "${songs.size}", songs) { drill = Drill(album, songs) }
                }
            view == LibView.SONGS -> library.mapIndexed { i, t ->
                LibItem("s$i-${t.id}", t.displayName, formatLong(t.durationMs), listOf(t)) { actions.playTracks(library, i) }
            }
            view == LibView.FOLDERS -> library.groupBy { it.folder.ifBlank { "Other" }.trim().lowercase() }
                .toSortedMap().map { (key, songs) ->
                    val name = songs.first().folder.ifBlank { "Other" }.trim().trimEnd('/')
                    LibItem("f$key", name, "${songs.size}", songs) { drill = Drill(name, songs) }
                }
            view == LibView.RADIO -> emptyList()
            else -> {
                // Automatic lists first, then the ones you made.
                val byId = (library + state.playlist).associateBy { it.id }
                val favs = stats.favorites.mapNotNull { byId[it] }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
                val most = stats.plays.entries.filter { it.value > 0 }.sortedByDescending { it.value }.mapNotNull { byId[it.key] }.take(50)
                val recentPlayed = stats.lastPlayed.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }.take(50)
                val recentAdded = library.filter { it.dateAdded > 0 }.sortedByDescending { it.dateAdded }.take(50)
                val auto = listOf(
                    Triple("FAVORITES", favs, "NO FAVORITES YET.\nTAP FAV ON THE PLAYER WHILE A SONG PLAYS,\nOR LONG-PRESS A SONG AND CHOOSE ADD TO FAVORITES."),
                    Triple("MOST PLAYED", most, "NOTHING PLAYED YET.\nYOUR TOP 50 SONGS WILL SHOW UP HERE."),
                    Triple("RECENTLY PLAYED", recentPlayed, "NOTHING PLAYED YET."),
                    Triple("RECENTLY ADDED", recentAdded, "NO NEW SONGS FOUND."),
                ).map { (name, songs, empty) ->
                    LibItem("auto$name", "* $name", "${songs.size}", songs) { drill = Drill(name, songs, emptyText = empty) }
                }
                auto + playlists.map { p ->
                    LibItem("p${p.id}", p.name, "${p.tracks.size}", p.tracks, playlistId = p.id) { drill = Drill(p.name, p.tracks, p.id) }
                }
            }
        }
    }

    SkinPanel("Playlist", modifier, titleModifier = titleModifier) {
        // ---- view switcher / drill-down header
        val d = drill
        if (d == null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                LibView.entries.forEach { v ->
                    TextButtonSkin(v.label, lit = view == v, size = 7.sp) { view = v }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButtonSkin("< BACK", size = 7.sp) { drill = null }
                Spacer(Modifier.width(6.dp))
                BodyText(d.title.uppercase(), Modifier.weight(1f), bold = true, maxLines = 1, size = 14.sp)
                TextButtonSkin("PLAY ALL", size = 7.sp) { actions.playTracks(d.tracks, 0) }
                Spacer(Modifier.width(3.dp))
                TextButtonSkin("+ QUEUE", size = 7.sp) { actions.queueTracks(d.tracks, next = false) }
            }
        }
        Spacer(Modifier.height(4.dp))

        if (view == LibView.RADIO && drill == null) {
            RadioBrowser(state, actions, Modifier.fillMaxWidth().weight(1f))
        } else {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                DisplayBox(Modifier.fillMaxSize(), bg = sc(Slot.LIST_BG)) {
                    val textStyle = TextStyle(fontFamily = skin.listFamily(), fontSize = (13 * skin.listScale()).sp)
                    val emptyMsg = when {
                        !hasPermission -> "TAP HERE TO ALLOW ACCESS TO YOUR MUSIC"
                        !state.libraryLoaded && library.isEmpty() && state.playlist.isEmpty() -> "SCANNING LIBRARY..."
                        items.isNotEmpty() -> null
                        drill?.emptyText != null -> drill?.emptyText
                        drill != null -> "THIS LIST IS EMPTY.\nLONG-PRESS ANY SONG, ARTIST OR ALBUM\nAND CHOOSE ADD TO PLAYLIST."
                        view == LibView.LISTS -> "NO PLAYLISTS YET.\nTAP NEW LIST, OR LONG-PRESS ANY SONG,\nARTIST OR ALBUM AND CHOOSE ADD TO PLAYLIST."
                        view == LibView.QUEUE -> "NOTHING QUEUED.\nPICK SOMETHING FROM ARTISTS, ALBUMS OR SONGS."
                        else -> "NO MUSIC FOUND ON THIS DEVICE.\nTAP ADD TO PICK SONG FILES."
                    }
                    if (emptyMsg != null) {
                        Box(
                            Modifier.fillMaxSize().clickable(enabled = !hasPermission) { actions.requestPermission() },
                            contentAlignment = Alignment.Center
                        ) { Text(emptyMsg, color = sc(Slot.LIST_TEXT), style = textStyle) }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), state = if (view == LibView.QUEUE && drill == null) listState else browseState) {
                            itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                                val color = if (item.highlight) sc(Slot.LIST_CURRENT) else sc(Slot.LIST_TEXT)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (item.highlight) sc(Slot.LIST_SELECT_BG) else Color.Transparent)
                                        .combinedClickable(
                                            onClick = item.onTap,
                                            onLongClick = {
                                                val dd = drill
                                                menu = when {
                                                    dd != null && dd.playlistId != null -> MenuTarget(item.title, item.tracks, playlistId = dd.playlistId, playlistIndex = i)
                                                    dd != null -> MenuTarget(item.title, item.tracks)
                                                    view == LibView.QUEUE -> MenuTarget(item.title, item.tracks, queueIndex = i)
                                                    view == LibView.LISTS && item.playlistId != null -> MenuTarget(item.title, item.tracks, playlistId = item.playlistId, wholePlaylist = true)
                                                    else -> MenuTarget(item.title, item.tracks)
                                                }
                                            }
                                        )
                                        .padding(horizontal = 3.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (drill == null && view in listOf(LibView.ARTISTS, LibView.ALBUMS, LibView.FOLDERS, LibView.LISTS)) {
                                        TransportIcon(Icon.PLAY, sc(Slot.LIST_TEXT).copy(alpha = 0.6f), Modifier.width(6.dp).height(8.dp))
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(item.title.uppercase(), color = color, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(item.right, color = color, style = textStyle, modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
                // long-press menu
                val m = menu
                SkinMenu(
                    expanded = m != null,
                    onDismiss = { menu = null },
                    alignment = Alignment.Center,
                    items = if (m == null) emptyList() else buildList {
                        add(SkinMenuItem("Play now") { actions.playTracks(m.tracks, 0) })
                        if (m.queueIndex == null) {
                            add(SkinMenuItem("Play next") { actions.queueTracks(m.tracks, next = true) })
                            add(SkinMenuItem("Add to queue") { actions.queueTracks(m.tracks, next = false) })
                        }
                        add(SkinMenuItem("Add to playlist...") { addToList = m.tracks })
                        val ids = m.tracks.map { it.id }
                        if (ids.isNotEmpty()) {
                            val allFav = ids.all { it in stats.favorites }
                            add(SkinMenuItem(if (allFav) "Remove from favorites" else "Add to favorites") {
                                com.duodeck.player.data.MusicStats.setFavorite(ids, !allFav)
                                // keep an open Favorites list in step
                                drill = drill?.let { dr -> if (dr.title == "FAVORITES" && allFav) Drill(dr.title, dr.tracks.filter { it.id !in ids }, emptyText = dr.emptyText) else dr }
                            })
                        }
                        if (m.queueIndex != null) add(SkinMenuItem("Remove from queue") { actions.removeFromQueue(m.queueIndex) })
                        if (m.playlistId != null && m.playlistIndex != null) {
                            val pid = m.playlistId; val idx = m.playlistIndex
                            add(SkinMenuItem("Remove from this playlist") {
                                actions.removeFromPlaylist(pid, idx)
                                drill = drill?.let { dr -> Drill(dr.title, dr.tracks.filterIndexed { j, _ -> j != idx }, dr.playlistId) }
                            })
                        }
                        if (m.wholePlaylist && m.playlistId != null) {
                            val pid = m.playlistId
                            add(SkinMenuItem("Delete playlist") { actions.deletePlaylist(pid) })
                        }
                    },
                )
            }

            Spacer(Modifier.height(4.dp))
            // ---- footer
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (view == LibView.QUEUE && drill == null) {
                    TextButtonSkin("ADD", size = 7.sp) { actions.addFiles() }
                    Spacer(Modifier.width(3.dp))
                    TextButtonSkin(sortLabel, size = 7.sp) { sortLabel = actions.cycleSort().ifBlank { "SORT" } }
                    Spacer(Modifier.width(3.dp))
                    TextButtonSkin("NOW", size = 7.sp) {
                        if (state.currentIndex >= 0) scope.launch { listState.animateScrollToItem((state.currentIndex - 3).coerceAtLeast(0)) }
                    }
                    Spacer(Modifier.width(3.dp))
                    TextButtonSkin("SAVE", size = 7.sp) { if (state.playlist.isNotEmpty()) namingNew = state.playlist }
                    Spacer(Modifier.width(3.dp))
                    TextButtonSkin("CLEAR", size = 7.sp) { actions.clearQueue() }
                } else if (view == LibView.LISTS && drill == null) {
                    TextButtonSkin("NEW LIST", size = 7.sp) { namingNew = emptyList() }
                }
                Spacer(Modifier.width(6.dp))
                DisplayBox(Modifier.weight(1f).height(28.dp)) {
                    val shown = drill?.tracks ?: if (view == LibView.QUEUE) state.playlist else library
                    val total = shown.sumOf { it.durationMs }
                    Column {
                        SkinText(if (drill != null) "LIST" else view.label, sc(Slot.DISPLAY_TEXT), 8.sp, weight = FontWeight.Bold)
                        SkinText("${shown.size} TRACKS  ${formatLong(total)}", sc(Slot.DISPLAY_TEXT), 8.sp)
                    }
                }
            }
        }
    }

    // ---- "add to playlist" chooser
    addToList?.let { tracks ->
        SkinDialog(
            title = "Add to playlist",
            onDismiss = { addToList = null },
            maxWidth = 400.dp,
            buttons = {
                TextButtonSkin("NEW PLAYLIST") { namingNew = tracks; addToList = null }
                TextButtonSkin("CANCEL") { addToList = null }
            },
        ) {
            BodyText("${tracks.size} song${if (tracks.size == 1) "" else "s"}", bold = true)
            if (playlists.isEmpty()) HintText("You don't have any playlists yet. Tap NEW PLAYLIST.")
            playlists.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { actions.addToPlaylist(p.id, tracks); addToList = null }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TransportIcon(Icon.PLAY, sc(Slot.ACCENT), Modifier.width(7.dp).height(9.dp))
                    Spacer(Modifier.width(8.dp))
                    BodyText(p.name, Modifier.weight(1f), maxLines = 1)
                    BodyText("${p.tracks.size}", size = 13.sp)
                }
            }
        }
    }

    // ---- name a new playlist
    namingNew?.let { tracks ->
        var name by remember { mutableStateOf("") }
        SkinDialog(
            title = "New playlist",
            onDismiss = { namingNew = null },
            maxWidth = 400.dp,
            buttons = {
                TextButtonSkin("CANCEL") { namingNew = null }
                TextButtonSkin("CREATE") { actions.createPlaylist(name.trim(), tracks); namingNew = null; view = LibView.LISTS; drill = null }
            },
        ) {
            SkinTextField(name, { name = it.take(40) }, "Playlist name", Modifier.fillMaxWidth())
            HintText(if (tracks.isEmpty()) "Starts empty. Long-press songs, artists or albums to add to it." else "${tracks.size} songs will go in it.")
        }
    }
}
