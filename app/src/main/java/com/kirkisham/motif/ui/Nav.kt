package com.kirkisham.motif.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.LastEpisode
import com.kirkisham.motif.ui.alarm.AlarmLogScreen
import com.kirkisham.motif.ui.alarm.AlarmScreen
import com.kirkisham.motif.ui.alarm.AlarmsListScreen
import com.kirkisham.motif.ui.album.AlbumScreen
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.DevicesSheet
import com.kirkisham.motif.ui.components.LocalRowServices
import com.kirkisham.motif.ui.components.RowServices
import com.kirkisham.motif.ui.components.MiniPlayer
import com.kirkisham.motif.ui.components.UndoPill
import com.kirkisham.motif.ui.components.dismissUndoOnTouch
import com.kirkisham.motif.ui.genre.GenreScreen
import com.kirkisham.motif.ui.home.HomeScreen
import com.kirkisham.motif.ui.info.InfoKind
import com.kirkisham.motif.ui.info.InfoScreen
import com.kirkisham.motif.ui.nowplaying.NowPlayingScreen
import com.kirkisham.motif.ui.person.PersonScreen
import com.kirkisham.motif.ui.playlist.MassEditScreen
import com.kirkisham.motif.ui.playlist.PlaylistScreen
import com.kirkisham.motif.ui.playlists.AllPlaylistsScreen
import com.kirkisham.motif.ui.search.SearchScreen
import com.kirkisham.motif.ui.settings.SettingsScreen

/** Route names. Screens are added here as the build order reaches them (CLAUDE.md). */
object Routes {
    const val HOME = "home"
    const val ALL = "all"
    const val PLAYLIST = "playlist/{id}"
    const val NOW = "now"
    const val SETTINGS = "settings"
    const val ALARMS = "alarms"
    const val ALARM_EDIT = "alarm/{id}"
    const val ALARM_LOG = "alarmlog"
    const val SEARCH = "search?q={q}"
    const val MASS_EDIT = "massedit/{id}"
    const val INFO = "info/{kind}/{id}"
    const val ALBUM = "album/{id}"
    const val PERSON = "person/{name}?discogs={discogs}&genius={genius}"
    const val GENRE = "genre/{tag}"
    fun playlist(id: String) = "playlist/$id"
    /** Edit screen: a saved alarm's id, or "new" for a blank one. */
    fun alarmEdit(id: String? = null) = "alarm/" + (id ?: "new")
    fun search(query: String = "") = "search?q=" + Uri.encode(query)
    fun massEdit(id: String) = "massedit/$id"
    fun infoTrack(id: String) = "info/track/$id"
    fun infoArtist(id: String) = "info/artist/$id"
    fun album(id: String) = "album/$id"
    fun person(name: String, discogsId: Int? = null, geniusId: Int? = null) =
        "person/" + Uri.encode(name) + "?discogs=" + (discogsId ?: 0) + "&genius=" + (geniusId ?: 0)
    fun genre(tag: String) = "genre/" + Uri.encode(tag)
}

/** Height the shared bottom ribbon takes (card + margin); screens pad their lists by this much. */
val MINI_PLAYER_SPACE = 112.dp

@Composable
fun MotifNavHost(
    app: MotifApp,
    nav: NavHostController = rememberNavController(),
    pendingRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
) {
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val undo by app.undo.current.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) { nav.navigate(pendingRoute) { launchSingleTop = true }; onRouteConsumed() }
    }
    // Shared hosts (0.10): the plus sheets, the Connect device sheet and the undo pill live here so
    // they survive navigation and every screen gets them.
    var plusTarget by remember { mutableStateOf<AddTarget?>(null) }
    var newPlaylistFor by remember { mutableStateOf<AddTarget?>(null) }
    var showDevices by remember { mutableStateOf(false) }
    val onPlus: () -> Unit = { playback?.let { plusTarget = AddTarget(it.uri, it.title) } }
    val onPlusLong: () -> Unit = { playback?.let { newPlaylistFor = AddTarget(it.uri, it.title) } }
    val onDevices: () -> Unit = { showDevices = true }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    // Remember the last episode Spotify played so Home can offer it again (spec §3.3).
    LaunchedEffect(playback?.uri, playback?.isPaused) {
        val p = playback ?: return@LaunchedEffect
        if (p.isEpisode) app.prefs.setLastEpisode(
            LastEpisode(p.uri, p.title, p.album.ifBlank { p.artist }, p.imageUri, p.durationMs, p.positionNow(), System.currentTimeMillis()),
        )
    }

    // Song rows everywhere read play counts and the playlist index through this (0.11).
    val rowServices = remember(app) { RowServices(app) { id -> nav.navigate(Routes.playlist(id)) } }
    Box(Modifier.fillMaxSize().dismissUndoOnTouch(app.undo)) {
        CompositionLocalProvider(LocalRowServices provides rowServices) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    app = app,
                    onOpenPlaylist = { nav.navigate(Routes.playlist(it)) },
                    onOpenAll = { nav.navigate(Routes.ALL) },
                    onOpenAlarm = { nav.navigate(Routes.ALARMS) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenSearch = { nav.navigate(Routes.search(it)) },
                )
            }
            composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { entry ->
                SearchScreen(
                    app = app,
                    initialQuery = entry.arguments?.getString("q").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenPlaylist = { nav.navigate(Routes.playlist(it)) },
                    onOpenArtist = { a -> a.id?.let { nav.navigate(Routes.infoArtist(it)) } },
                    onOpenAlbum = { nav.navigate(Routes.album(it)) },
                )
            }
            composable(Routes.ALBUM) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                AlbumScreen(
                    app = app,
                    albumId = id,
                    onBack = { nav.popBackStack() },
                    onOpenArtist = { a -> a.id?.let { nav.navigate(Routes.infoArtist(it)) } },
                )
            }
            composable(Routes.MASS_EDIT) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                MassEditScreen(app, id, onClose = { nav.popBackStack() })
            }
            composable(Routes.INFO) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val kind = if (entry.arguments?.getString("kind") == "artist") InfoKind.ARTIST else InfoKind.TRACK
                InfoScreen(
                    app, kind, id,
                    onBack = { nav.popBackStack() },
                    onOpenPerson = { c -> nav.navigate(Routes.person(c.name, c.discogsId, c.geniusId)) },
                    onOpenGenre = { nav.navigate(Routes.genre(it)) },
                    onOpenAlbum = { nav.navigate(Routes.album(it)) },
                )
            }
            composable(
                Routes.PERSON,
                arguments = listOf(
                    navArgument("discogs") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("genius") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                val name = entry.arguments?.getString("name") ?: return@composable
                PersonScreen(
                    app = app,
                    name = name,
                    discogsId = entry.arguments?.getInt("discogs")?.takeIf { it > 0 },
                    geniusId = entry.arguments?.getInt("genius")?.takeIf { it > 0 },
                    onBack = { nav.popBackStack() },
                    onOpenAlbum = { nav.navigate(Routes.album(it)) },
                    onOpenArtist = { a -> a.id?.let { nav.navigate(Routes.infoArtist(it)) } },
                )
            }
            composable(Routes.GENRE) { entry ->
                val tag = entry.arguments?.getString("tag") ?: return@composable
                GenreScreen(
                    app = app,
                    tag = tag,
                    onBack = { nav.popBackStack() },
                    onOpenArtist = { a -> a.id?.let { nav.navigate(Routes.infoArtist(it)) } },
                    onOpenAlbum = { nav.navigate(Routes.album(it)) },
                )
            }
            composable(Routes.ALL) {
                AllPlaylistsScreen(
                    app = app,
                    onBack = { nav.popBackStack() },
                    onOpenPlaylist = { nav.navigate(Routes.playlist(it)) },
                )
            }
            composable(Routes.PLAYLIST) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                PlaylistScreen(
                    app = app,
                    playlistId = id,
                    onBack = { nav.popBackStack() },
                    onDeleted = { nav.popBackStack() },
                    onMassEdit = { nav.navigate(Routes.massEdit(id)) },
                    onOpenArtist = { a -> a.id?.let { nav.navigate(Routes.infoArtist(it)) } },
                    onOpenAlbum = { nav.navigate(Routes.album(it)) },
                )
            }
            composable(Routes.NOW) {
                NowPlayingScreen(
                    app, onBack = { nav.popBackStack() }, onOpenInfo = { nav.navigate(Routes.infoTrack(it)) }, onDevices = onDevices,
                    onOpenAlbum = { nav.navigate(Routes.album(it)) }, onOpenGenre = { nav.navigate(Routes.genre(it)) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(app, onBack = { nav.popBackStack() }) }
            // Alarms (0.12): list, edit (alarm/{id} or alarm/new) and the log.
            composable(Routes.ALARMS) {
                AlarmsListScreen(
                    app,
                    onBack = { nav.popBackStack() },
                    onNew = { nav.navigate(Routes.alarmEdit()) },
                    onEdit = { nav.navigate(Routes.alarmEdit(it)) },
                    onLog = { nav.navigate(Routes.ALARM_LOG) },
                )
            }
            composable(Routes.ALARM_EDIT) { entry ->
                val id = entry.arguments?.getString("id")?.takeIf { it != "new" }
                AlarmScreen(app, alarmId = id, onBack = { nav.popBackStack() })
            }
            composable(Routes.ALARM_LOG) { AlarmLogScreen(onBack = { nav.popBackStack() }) }
        }
        }

        // Bottom overlays: the undo pill sits above the ribbon; neither shows on Now playing.
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UndoPill(app.undo, undo)
            if (route != Routes.NOW) {
                MiniPlayer(
                    remote = app.remote,
                    playback = playback,
                    onToggle = { app.player.togglePlayPause() },
                    onNext = { app.player.next() },
                    onOpen = { nav.navigate(Routes.NOW) { launchSingleTop = true } },
                    onPlus = onPlus,
                    onPlusLong = onPlusLong,
                    onDevices = onDevices,
                )
            }
        }
    }

    AddToHost(app, plusTarget, newPlaylistFor, onDismiss = { plusTarget = null; newPlaylistFor = null })
    if (showDevices) DevicesSheet(app, onDismiss = { showDevices = false })
}
