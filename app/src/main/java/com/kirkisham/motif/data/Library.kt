package com.kirkisham.motif.data

import com.kirkisham.motif.auth.SpotifyApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache of the user's library on top of the Web API. Screens read the StateFlows and
 * call refresh when they appear; edits update the cache first so the UI never waits on Spotify.
 */
class Library(private val api: SpotifyApi) {

    private val _playlists = MutableStateFlow<List<SpPlaylist>?>(null)
    val playlists: StateFlow<List<SpPlaylist>?> = _playlists.asStateFlow()

    private val _shows = MutableStateFlow<List<SpShow>?>(null)
    val shows: StateFlow<List<SpShow>?> = _shows.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** One plain line for the UI when the library could not load. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val tracksCache = mutableMapOf<String, MutableStateFlow<List<SpPlaylistItem>?>>()
    private val lock = Mutex()
    private var userId: String? = null

    suspend fun me(): SpotifyApi.Me? = api.me()?.also { userId = it.id }
    suspend fun userId(): String? = userId ?: me()?.id

    suspend fun refreshPlaylists() {
        val list = api.allPlaylists()
        if (list == null) {
            if (_playlists.value == null) _status.value = "Could not load playlists. Check the connection."
        } else {
            _status.value = null
            _playlists.value = list
        }
    }

    suspend fun refreshShows() {
        api.savedShows()?.let { _shows.value = it.map { s -> s.show } }
    }

    fun tracksFlow(playlistId: String): StateFlow<List<SpPlaylistItem>?> =
        tracksCache.getOrPut(playlistId) { MutableStateFlow(null) }.asStateFlow()

    suspend fun refreshTracks(playlistId: String) {
        val items = api.playlistTracks(playlistId) ?: return
        tracksCache.getOrPut(playlistId) { MutableStateFlow(null) }.value = items.filter { it.track != null }
    }

    fun playlistById(id: String): SpPlaylist? = _playlists.value?.firstOrNull { it.id == id }

    suspend fun rename(id: String, name: String): Boolean {
        val ok = api.renamePlaylist(id, name)
        if (ok) _playlists.value = _playlists.value?.map { if (it.id == id) it.copy(name = name) else it }
        return ok
    }

    suspend fun unfollow(id: String): Boolean {
        val ok = api.unfollowPlaylist(id)
        if (ok) _playlists.value = _playlists.value?.filterNot { it.id == id }
        return ok
    }

    suspend fun create(name: String): SpPlaylist? {
        val uid = userId() ?: return null
        val p = api.createPlaylist(uid, name) ?: return null
        _playlists.value = listOf(p) + (_playlists.value ?: emptyList())
        return p
    }

    suspend fun addTrack(playlistId: String, trackUri: String): Boolean {
        val ok = api.addToPlaylist(playlistId, listOf(trackUri))
        if (ok) {
            _playlists.value = _playlists.value?.map {
                if (it.id == playlistId) it.copy(tracks = SpTracksRef((it.tracks?.total ?: 0) + 1)) else it
            }
            tracksCache[playlistId]?.value = null // reload next time it is opened
        }
        return ok
    }

    suspend fun removeTrack(playlistId: String, trackUri: String): Boolean {
        val ok = api.removeFromPlaylist(playlistId, listOf(trackUri))
        if (ok) {
            tracksCache[playlistId]?.let { f -> f.value = f.value?.filterNot { it.track?.uri == trackUri } }
            _playlists.value = _playlists.value?.map {
                if (it.id == playlistId) it.copy(tracks = SpTracksRef(maxOf(0, (it.tracks?.total ?: 1) - 1))) else it
            }
        }
        return ok
    }

    /** Moves the item at [from] to [to] (indices in Spotify's order) locally and on Spotify. */
    suspend fun move(playlistId: String, from: Int, to: Int): Boolean = lock.withLock {
        val flow = tracksCache[playlistId] ?: return false
        val cur = flow.value?.toMutableList() ?: return false
        if (from == to || from !in cur.indices || to !in cur.indices) return true
        val item = cur.removeAt(from)
        cur.add(to, item)
        flow.value = cur
        val insertBefore = if (to > from) to + 1 else to
        val snap = api.reorderPlaylist(playlistId, from, insertBefore)
        if (snap == null) refreshTracks(playlistId)
        return snap != null
    }
}
