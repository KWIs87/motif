package com.kirkisham.motif.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Which of the user's playlists each song is in (0.11, item 4). Spotify only hands over a playlist's
 * rows when it is opened, so this keeps every playlist's URIs it has ever seen (plus the snapshot id
 * they came from), derives track URI → playlist ids from them, and persists the lot as one JSON file.
 * [Library] feeds it: when a playlist loads, when the background pass finds a changed snapshot, and
 * at once when Motif adds or removes songs or a playlist.
 */
class PlaylistIndex(dir: File) {
    @Serializable
    private class Disk(val snapshots: Map<String, String> = emptyMap(), val contents: Map<String, List<String>> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(dir, "playlist_index.json")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val contents = HashMap<String, List<String>>()
    private val snapshots = HashMap<String, String>()
    private var loaded = false
    private var flush: Job? = null
    @Volatile private var dirty = false

    private val _state = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Track URI → ids of the playlists it is in. Empty sets never appear. */
    val state: StateFlow<Map<String, Set<String>>> = _state.asStateFlow()

    /** Reads the file once; playlists already set in memory win over the disk copy. */
    suspend fun load() {
        if (loaded) return
        val disk = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) json.decodeFromString<Disk>(file.readText()) else null }
                .onFailure { Log.w(TAG, "index unreadable: ${it.javaClass.simpleName}") }.getOrNull()
        }
        synchronized(lock) {
            if (loaded) return
            disk?.contents?.forEach { (id, uris) -> if (id !in contents) contents[id] = uris }
            disk?.snapshots?.forEach { (id, snap) -> if (id !in snapshots) snapshots[id] = snap }
            loaded = true
            rebuild()
        }
    }

    /** The snapshot id the indexed contents of [playlistId] came from, or null when never indexed. */
    fun snapshotOf(playlistId: String): String? = synchronized(lock) { snapshots[playlistId] }
    fun has(playlistId: String): Boolean = synchronized(lock) { playlistId in contents }

    /** Replaces a playlist's contents (a fresh load). */
    fun setPlaylist(playlistId: String, uris: List<String>, snapshot: String?) = synchronized(lock) {
        contents[playlistId] = uris.filter { it.isNotBlank() }
        if (snapshot != null) snapshots[playlistId] = snapshot else snapshots.remove(playlistId)
        rebuild()
    }

    /** Songs Motif just added. The snapshot is forgotten so the next pass re-reads the true contents. */
    fun add(playlistId: String, uris: List<String>) = synchronized(lock) {
        contents[playlistId] = (contents[playlistId] ?: emptyList()) + uris.filter { it.isNotBlank() }
        snapshots.remove(playlistId)
        rebuild()
    }

    /** Songs Motif just removed (every copy, as Spotify does). */
    fun remove(playlistId: String, uris: List<String>) = synchronized(lock) {
        val set = uris.toSet()
        contents[playlistId]?.let { contents[playlistId] = it.filterNot { u -> u in set } }
        snapshots.remove(playlistId)
        rebuild()
    }

    /** Forgets a playlist (deleted / unfollowed); returns what it held so an undo can put it back. */
    fun removePlaylist(playlistId: String): List<String>? = synchronized(lock) {
        snapshots.remove(playlistId)
        val gone = contents.remove(playlistId)
        rebuild()
        gone
    }

    fun restorePlaylist(playlistId: String, uris: List<String>) = synchronized(lock) {
        contents[playlistId] = uris
        rebuild()
    }

    /** Drops playlists that are no longer in the library. */
    fun retainPlaylists(ids: Set<String>) = synchronized(lock) {
        val gone = contents.keys.filterNot { it in ids }
        if (gone.isEmpty()) return
        gone.forEach { contents.remove(it); snapshots.remove(it) }
        rebuild()
    }

    // Caller holds [lock].
    private fun rebuild() {
        val map = HashMap<String, MutableSet<String>>()
        for ((id, uris) in contents) for (u in uris) map.getOrPut(u) { LinkedHashSet() }.add(id)
        _state.value = map
        if (loaded) scheduleFlush()
    }

    private fun scheduleFlush() {
        dirty = true
        if (flush?.isActive == true) return
        flush = ioScope.launch {
            while (dirty) {
                dirty = false
                delay(1_500)
                val text = synchronized(lock) { json.encodeToString(Disk(HashMap(snapshots), HashMap(contents))) }
                runCatching { file.parentFile?.mkdirs(); file.writeText(text) }
                    .onFailure { Log.w(TAG, "index not written: ${it.javaClass.simpleName}") }
            }
        }
    }

    companion object {
        private const val TAG = "MotifIndex"
    }
}
