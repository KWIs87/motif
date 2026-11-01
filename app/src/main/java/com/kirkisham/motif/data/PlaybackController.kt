package com.kirkisham.motif.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Playback intents from screens, combining App Remote commands with the bookkeeping Motif keeps
 * (last played, last episode). Screens call these; they never talk to the remote directly.
 */
class PlaybackController(
    val remote: SpotifyRemote,
    private val prefs: Prefs,
    private val library: Library,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _notice = MutableStateFlow<String?>(null)
    /** Last plain-line notice for the UI ("Spotify is not installed", "Spotify did not answer"). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun playPlaylist(p: SpPlaylist, shuffle: Boolean = false) = scope.launch {
        prefs.markPlayed(p.id)
        if (!ensure()) return@launch
        remote.setShuffle(shuffle)
        report(remote.play(p.uri))
    }

    /** Plays [contextUri] starting at [index] (Spotify's own order). */
    fun playInContext(contextUri: String, index: Int, playlistId: String? = null) = scope.launch {
        playlistId?.let { prefs.markPlayed(it) }
        if (!ensure()) return@launch
        remote.setShuffle(false)
        var ok = remote.skipToIndex(contextUri, index)
        if (!ok) {
            ok = remote.play(contextUri)
            if (ok && index > 0) remote.skipToIndex(contextUri, index)
        }
        report(ok)
    }

    fun playUri(uri: String) = scope.launch { if (ensure()) report(remote.play(uri)) }
    fun queue(uri: String) = scope.launch { if (ensure()) report(remote.queue(uri)) }
    fun togglePlayPause() = scope.launch { if (ensure()) report(remote.togglePlayPause()) }
    fun next() = scope.launch { if (ensure()) report(remote.skipForward()) }
    fun previous() = scope.launch { if (ensure()) report(remote.skipBack()) }
    fun seekTo(ms: Long) = scope.launch { if (ensure()) remote.seekTo(ms) }
    fun seekRelative(ms: Long) = scope.launch { if (ensure()) remote.seekRelative(ms) }
    fun setShuffle(on: Boolean) = scope.launch { if (ensure()) remote.setShuffle(on) }
    fun setPodcastSpeed(speed: Float, showUri: String?) = scope.launch {
        showUri?.let { prefs.setPodcastSpeed(it, speed) }
        if (ensure()) report(remote.setPodcastSpeed(speed))
    }

    fun clearNotice() { _notice.value = null }

    /** True when Spotify is connected, waiting up to a few seconds for a connection in progress. */
    private suspend fun ensure(): Boolean {
        if (!remote.isSpotifyInstalled) {
            _notice.value = "Spotify is not installed on this phone. Motif needs it to play."
            return false
        }
        if (remote.isConnected) return true
        _notice.value = "Connecting to Spotify"
        remote.acquire("controller")
        val connected = withTimeoutOrNull(8_000) {
            remote.connection.first { it == SpotifyRemote.Connection.Connected }
        } != null
        remote.release("controller")
        if (!connected) _notice.value = "Spotify did not answer. Open Spotify once, then try again."
        else _notice.value = null
        return connected
    }

    private fun report(ok: Boolean) {
        _notice.value = if (ok) null else "Spotify did not take that command."
    }
}
