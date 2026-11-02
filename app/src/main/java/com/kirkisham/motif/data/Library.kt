package com.kirkisham.motif.data

import com.kirkisham.motif.auth.SpotifyApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache of the user's library on top of the Web API. Screens read the StateFlows and
 * call refresh when they appear; edits update the cache first so the UI never waits on Spotify.
 */
class Library(private val api: SpotifyApi, val index: PlaylistIndex) {

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
            index.retainPlaylists(list.map { it.id }.toSet())
        }
    }

    /**
     * Background pass for the "in a playlist" index (0.11, item 4): loads the rows of every owned or
     * collaborative playlist whose snapshot the index has not seen, one at a time with a pause between,
     * and fills the tracks cache for playlists not opened yet. Cheap when nothing changed. Cancelled
     * and restarted by the caller when the playlist list changes.
     */
    suspend fun warmIndex(list: List<SpPlaylist>) {
        index.load()
        for (p in list) {
            if (!p.hasItems) continue
            if (index.has(p.id) && (p.snapshot_id == null || index.snapshotOf(p.id) == p.snapshot_id)) continue
            val items = api.playlistTracks(p.id) ?: continue
            val rows = items.filter { it.item != null }
            index.setPlaylist(p.id, rows.mapNotNull { it.item?.uri }, p.snapshot_id)
            val flow = tracksCache.getOrPut(p.id) { MutableStateFlow(null) }
            if (flow.value == null) flow.value = rows
            delay(250)
        }
    }

    suspend fun refreshShows() {
        api.savedShows()?.let { _shows.value = it.map { s -> s.show } }
    }

    fun tracksFlow(playlistId: String): StateFlow<List<SpPlaylistItem>?> =
        tracksCache.getOrPut(playlistId) { MutableStateFlow(null) }.asStateFlow()

    /**
     * Loads a playlist's rows. Spotify only shares the contents of playlists the user owns or
     * collaborates on (February 2026); for the rest the cache is set to an empty list so the screen
     * can show its "not shared" line instead of loading forever. See [contentsShared].
     */
    suspend fun refreshTracks(playlistId: String) {
        val flow = tracksCache.getOrPut(playlistId) { MutableStateFlow(null) }
        val items = api.playlistTracks(playlistId)
        if (items == null) {
            if (contentsShared(playlistId) == false && flow.value == null) flow.value = emptyList()
            return
        }
        flow.value = items.filter { it.item != null }
        index.setPlaylist(playlistId, flow.value.orEmpty().mapNotNull { it.item?.uri }, playlistById(playlistId)?.snapshot_id)
    }

    /**
     * Whether Spotify shares this playlist's contents with Motif: true when the playlist object
     * carried an `items` field, false when it did not, null when the playlist is not known yet.
     */
    fun contentsShared(playlistId: String): Boolean? = (playlistById(playlistId) ?: foreign[playlistId])?.hasItems

    fun playlistById(id: String): SpPlaylist? = _playlists.value?.firstOrNull { it.id == id }

    suspend fun rename(id: String, name: String): Boolean {
        val ok = api.renamePlaylist(id, name)
        if (ok) _playlists.value = _playlists.value?.map { if (it.id == id) it.copy(name = name) else it }
        return ok
    }

    suspend fun unfollow(id: String): Boolean {
        val uri = playlistById(id)?.uri ?: "spotify:playlist:$id"
        val ok = api.unfollowPlaylist(uri)
        if (ok) { _playlists.value = _playlists.value?.filterNot { it.id == id }; index.removePlaylist(id) }
        return ok
    }

    suspend fun create(name: String): SpPlaylist? {
        val p = api.createPlaylist(name) ?: return null
        _playlists.value = listOf(p) + (_playlists.value ?: emptyList())
        return p
    }

    suspend fun addTrack(playlistId: String, trackUri: String): Boolean = addTracks(playlistId, listOf(trackUri))

    /** Adds several URIs (100 per request, Spotify's limit). False if any chunk failed. */
    suspend fun addTracks(playlistId: String, trackUris: List<String>): Boolean {
        if (trackUris.isEmpty()) return true
        var ok = true
        var added = 0
        for (chunk in trackUris.chunked(100)) {
            if (api.addToPlaylist(playlistId, chunk)) { added += chunk.size; index.add(playlistId, chunk) } else ok = false
        }
        if (added > 0) {
            _playlists.value = _playlists.value?.map {
                if (it.id == playlistId) it.withCount((it.itemCount ?: 0) + added) else it
            }
            tracksCache[playlistId]?.value = null // reload next time it is opened
        }
        return ok
    }

    /** Removes every occurrence of each URI (Spotify removes by URI, not position). */
    suspend fun removeTracks(playlistId: String, trackUris: List<String>): Boolean {
        if (trackUris.isEmpty()) return true
        var ok = true
        for (chunk in trackUris.chunked(100)) {
            if (api.removeFromPlaylist(playlistId, chunk)) {
                val set = chunk.toSet()
                index.remove(playlistId, chunk)
                tracksCache[playlistId]?.let { f ->
                    val before = f.value?.size ?: 0
                    f.value = f.value?.filterNot { it.item?.uri in set }
                    val gone = before - (f.value?.size ?: 0)
                    _playlists.value = _playlists.value?.map {
                        if (it.id == playlistId) it.withCount((it.itemCount ?: gone) - gone) else it
                    }
                }
            } else ok = false
        }
        return ok
    }

    /** One mass-edit step, recorded in the order the user made it (indices are Spotify's at that moment). */
    sealed interface EditOp {
        data class Move(val from: Int, val to: Int) : EditOp
        data class Remove(val uris: List<String>) : EditOp
    }

    /**
     * Replays mass-edit steps on Spotify in order, so every Move sees the same list the user saw.
     * Stops at the first failure; the playlist is reloaded either way. Returns true when all applied.
     */
    suspend fun applyEdits(playlistId: String, ops: List<EditOp>): Boolean = lock.withLock {
        var ok = true
        for (op in ops) {
            val stepOk = when (op) {
                is EditOp.Move -> if (op.from == op.to) true
                    else api.reorderPlaylist(playlistId, op.from, if (op.to > op.from) op.to + 1 else op.to) != null
                is EditOp.Remove -> op.uris.chunked(100).all { api.removeFromPlaylist(playlistId, it) }
            }
            if (!stepOk) { ok = false; break }
        }
        if (ops.isNotEmpty()) {
            refreshTracks(playlistId)
            val n = tracksCache[playlistId]?.value?.size
            if (n != null) _playlists.value = _playlists.value?.map { if (it.id == playlistId) it.withCount(n) else it }
        }
        return ok
    }

    /** A playlist outside the library (search result): fetched once and kept for the session. */
    private val foreign = mutableMapOf<String, SpPlaylist>()
    suspend fun playlistOrFetch(id: String): SpPlaylist? =
        playlistById(id) ?: foreign[id] ?: api.playlist(id)?.also { foreign[id] = it }

    // ---- Deferred removals (0.10, item 1: Undo) ----

    /** What [hideTracks] took out, so [restoreTracks] can put it back exactly. */
    class Hidden(val playlistId: String, val before: List<SpPlaylistItem>, val uris: List<String>)

    /**
     * Takes every row with one of [uris] out of the cached list (and the count) without telling
     * Spotify. Call [removeTracksRemote] when the undo window closes, or [restoreTracks] to undo.
     */
    fun hideTracks(playlistId: String, uris: List<String>): Hidden? {
        val flow = tracksCache[playlistId] ?: return null
        val before = flow.value ?: return null
        val set = uris.toSet()
        val after = before.filterNot { it.item?.uri in set }
        flow.value = after
        index.remove(playlistId, uris)
        val gone = before.size - after.size
        _playlists.value = _playlists.value?.map { if (it.id == playlistId) it.withCount((it.itemCount ?: gone) - gone) else it }
        return Hidden(playlistId, before, uris)
    }

    fun restoreTracks(h: Hidden) {
        val flow = tracksCache[h.playlistId] ?: return
        flow.value = h.before
        index.setPlaylist(h.playlistId, h.before.mapNotNull { it.item?.uri }, null)
        _playlists.value = _playlists.value?.map { if (it.id == h.playlistId) it.withCount(h.before.size) else it }
    }

    /** The Spotify side of [hideTracks]; reloads the list when Spotify refused so the cache is honest. */
    suspend fun removeTracksRemote(h: Hidden): Boolean {
        var ok = true
        for (chunk in h.uris.chunked(100)) if (!api.removeFromPlaylist(h.playlistId, chunk)) ok = false
        if (!ok) refreshTracks(h.playlistId)
        return ok
    }

    /** Takes a playlist off the local list without unfollowing it; [restorePlaylist] puts it back where it was. */
    fun hidePlaylist(id: String): Pair<SpPlaylist, Int>? {
        val cur = _playlists.value ?: return null
        val i = cur.indexOfFirst { it.id == id }
        if (i < 0) return null
        _playlists.value = cur.filterIndexed { j, _ -> j != i }
        index.removePlaylist(id)?.let { hiddenIndex[id] = it }
        return cur[i] to i
    }

    /** URIs the index held for playlists hidden by [hidePlaylist], for [restorePlaylist]. */
    private val hiddenIndex = mutableMapOf<String, List<String>>()

    fun restorePlaylist(p: SpPlaylist, at: Int) {
        val cur = _playlists.value?.toMutableList() ?: mutableListOf()
        if (cur.none { it.id == p.id }) cur.add(at.coerceIn(0, cur.size), p)
        _playlists.value = cur
        hiddenIndex.remove(p.id)?.let { index.restorePlaylist(p.id, it) }
    }

    /** The Spotify side of [hidePlaylist]; reloads the list when Spotify refused. */
    suspend fun unfollowRemote(p: SpPlaylist): Boolean {
        val ok = api.unfollowPlaylist(p.uri)
        if (!ok) refreshPlaylists()
        return ok
    }

    suspend fun removeTrack(playlistId: String, trackUri: String): Boolean {
        val ok = api.removeFromPlaylist(playlistId, listOf(trackUri))
        if (ok) {
            index.remove(playlistId, listOf(trackUri))
            tracksCache[playlistId]?.let { f -> f.value = f.value?.filterNot { it.item?.uri == trackUri } }
            _playlists.value = _playlists.value?.map {
                if (it.id == playlistId) it.withCount((it.itemCount ?: 1) - 1) else it
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
