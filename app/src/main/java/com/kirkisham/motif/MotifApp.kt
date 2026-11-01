package com.kirkisham.motif

import android.app.Application
import com.kirkisham.motif.auth.SpotifyAuth
import com.kirkisham.motif.auth.TokenStore

class MotifApp : Application() {
    lateinit var tokenStore: TokenStore
        private set
    lateinit var spotifyAuth: SpotifyAuth
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        spotifyAuth = SpotifyAuth(this, tokenStore)
    }
}
