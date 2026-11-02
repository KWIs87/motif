package com.kirkisham.motif

import android.app.Application
import com.kirkisham.motif.alarm.AlarmScheduler
import com.kirkisham.motif.alarm.AlarmStore
import com.kirkisham.motif.auth.SpotifyApi
import com.kirkisham.motif.auth.SpotifyAuth
import com.kirkisham.motif.auth.TokenStore
import com.kirkisham.motif.data.Devices
import com.kirkisham.motif.data.Info
import com.kirkisham.motif.data.Library
import com.kirkisham.motif.data.Lyrics
import com.kirkisham.motif.data.PlaybackController
import com.kirkisham.motif.data.PlaylistIndex
import com.kirkisham.motif.data.Prefs
import com.kirkisham.motif.data.SpotifyRemote
import com.kirkisham.motif.data.UndoManager
import com.kirkisham.motif.data.Updates
import com.kirkisham.motif.widget.MotifWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons. Screens reach them through MotifApp.get(context). */
class MotifApp : Application() {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var spotifyAuth: SpotifyAuth
        private set
    lateinit var api: SpotifyApi
        private set
    lateinit var library: Library
        private set
    lateinit var playlistIndex: PlaylistIndex
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var remote: SpotifyRemote
        private set
    lateinit var player: PlaybackController
        private set
    lateinit var lyrics: Lyrics
        private set
    lateinit var info: Info
        private set
    lateinit var updates: Updates
        private set
    lateinit var devices: Devices
        private set
    lateinit var undo: UndoManager
        private set
    lateinit var alarmStore: AlarmStore
        private set
    lateinit var alarmScheduler: AlarmScheduler
        private set
    /** Fire-and-forget work that must outlive a screen (mass edit save, update check). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        spotifyAuth = SpotifyAuth(this, tokenStore)
        api = SpotifyApi(spotifyAuth)
        playlistIndex = PlaylistIndex(java.io.File(filesDir, "info"))
        library = Library(api, playlistIndex)
        prefs = Prefs(this)
        remote = SpotifyRemote(this)
        player = PlaybackController(remote, prefs, library, api)
        lyrics = Lyrics()
        info = Info(api, java.io.File(filesDir, "info"))
        updates = Updates(this, prefs)
        devices = Devices(api, this)
        undo = UndoManager(appScope)
        alarmStore = AlarmStore(this)
        alarmScheduler = AlarmScheduler(this, alarmStore)

        // "In a playlist" index (0.11): load it, then re-run the background pass whenever the playlist list changes.
        appScope.launch {
            playlistIndex.load()
            library.playlists.filterNotNull().collectLatest { library.warmIndex(it) }
        }

        // Keep the widget in step with playback and with the style settings, whenever this process is alive.
        val scope = appScope
        scope.launch {
            remote.playback
                .map { p -> p?.let { Triple(it.uri, it.isPaused, it.imageUri) } }
                .distinctUntilChanged()
                .collect { if (MotifWidget.hasWidgets(this@MotifApp)) MotifWidget.update(this@MotifApp) }
        }
        scope.launch {
            combine(prefs.appearance, prefs.widgetStyle, prefs.widgetUsesApp) { a, w, u -> Triple(a, w, u) }
                .distinctUntilChanged()
                .collect { if (MotifWidget.hasWidgets(this@MotifApp)) MotifWidget.update(this@MotifApp) }
        }
    }

    companion object {
        fun get(context: android.content.Context): MotifApp = context.applicationContext as MotifApp
    }
}
