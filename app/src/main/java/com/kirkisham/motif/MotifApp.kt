package com.kirkisham.motif

import android.app.Application
import com.kirkisham.motif.auth.SpotifyApi
import com.kirkisham.motif.auth.SpotifyAuth
import com.kirkisham.motif.auth.TokenStore
import com.kirkisham.motif.data.Library
import com.kirkisham.motif.data.Lyrics
import com.kirkisham.motif.data.PlaybackController
import com.kirkisham.motif.data.Prefs
import com.kirkisham.motif.data.SpotifyRemote

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
    lateinit var prefs: Prefs
        private set
    lateinit var remote: SpotifyRemote
        private set
    lateinit var player: PlaybackController
        private set
    lateinit var lyrics: Lyrics
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        spotifyAuth = SpotifyAuth(this, tokenStore)
        api = SpotifyApi(spotifyAuth)
        library = Library(api)
        prefs = Prefs(this)
        remote = SpotifyRemote(this)
        player = PlaybackController(remote, prefs, library)
        lyrics = Lyrics()
    }

    companion object {
        fun get(context: android.content.Context): MotifApp = context.applicationContext as MotifApp
    }
}
