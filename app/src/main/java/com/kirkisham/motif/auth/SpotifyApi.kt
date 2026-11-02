package com.kirkisham.motif.auth

import android.util.Log
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpCurrentlyPlaying
import com.kirkisham.motif.data.SpEpisode
import com.kirkisham.motif.data.SpPage
import com.kirkisham.motif.data.SpPlaylist
import com.kirkisham.motif.data.SpPlaylistItem
import com.kirkisham.motif.data.SpSavedShow
import com.kirkisham.motif.data.SpSearchResult
import com.kirkisham.motif.data.SpSnapshot
import com.kirkisham.motif.data.SpTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Thin Spotify Web API client. Every call returns null (or false) on failure instead of throwing;
 * screens show a plain line and move on. Nothing about the library is logged, only status codes.
 *
 * Uses the February 2026 Dev Mode endpoints: /playlists/{id}/items (rows are `item`, not `track`),
 * POST /me/playlists, PUT/DELETE /me/library with URIs, single-item GET /tracks/{id} (the batch
 * `ids=` forms are gone) and a search limit of at most 10 per page. Playlist contents are only
 * returned for playlists the user owns or collaborates on. Endpoints removed for new developer
 * apps (audio-features, recommendations, batch fetches, browse) are not used.
 */
class SpotifyApi(private val auth: SpotifyAuth) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    @Serializable
    /** GET /me. `product` is no longer returned to Dev Mode apps and stays null. */
    data class Me(val display_name: String? = null, val id: String, val product: String? = null)

    suspend fun me(): Me? = get("$BASE/me")

    // ---- Library ----

    /** Every playlist the user owns or follows, in Spotify's order. */
    suspend fun allPlaylists(): List<SpPlaylist>? = pageAll("$BASE/me/playlists?limit=50")

    /** One playlist's metadata. `items(total)` is absent when the user neither owns nor collaborates on it. */
    suspend fun playlist(id: String): SpPlaylist? =
        get("$BASE/playlists/$id?fields=id,name,uri,images,items(total),owner(id,display_name),snapshot_id,description")

    /**
     * All rows of a playlist. Episodes inside playlists come back with type "episode" and are kept.
     * Spotify only serves this for playlists the user owns or collaborates on; others fail (null).
     */
    suspend fun playlistTracks(id: String): List<SpPlaylistItem>? {
        val fields = URLEncoder.encode(
            "items(added_at,item(id,name,uri,type,is_local,duration_ms,artists(name,uri,id),album(name,uri,images))),next,total,offset",
            "UTF-8",
        )
        return pageAll("$BASE/playlists/$id/items?limit=100&additional_types=track,episode&fields=$fields")
    }

    suspend fun savedShows(): List<SpSavedShow>? = pageAll("$BASE/me/shows?limit=50")

    /** Most recent episodes of a show, newest first. resume_point needs the user-read-playback-position scope. */
    suspend fun showEpisodes(showId: String, limit: Int = 5): List<SpEpisode>? =
        get<SpPage<SpEpisode>>("$BASE/shows/$showId/episodes?limit=$limit")?.items

    suspend fun episode(id: String): SpEpisode? = get("$BASE/episodes/$id")

    /** What Spotify is playing on any device right now, episodes included. Null when nothing is. */
    suspend fun currentlyPlaying(): SpCurrentlyPlaying? = get("$BASE/me/player?additional_types=episode")

    /** Songs only (alarm song picker). Pages of [SEARCH_MAX] with offset until [limit] is reached. */
    suspend fun searchTracks(query: String, limit: Int = 20): List<SpTrack>? {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<SpTrack>()
        var offset = 0
        while (out.size < limit) {
            val page = search(query, "track", SEARCH_MAX, offset) ?: return if (out.isEmpty()) null else out
            val got = page.trackList
            out += got
            if (got.size < SEARCH_MAX || page.tracks?.next == null) break
            offset += SEARCH_MAX
        }
        return out.take(limit)
    }

    /**
     * One GET /search across [types] (comma-separated Spotify types). Shows and episodes need a
     * market, so from_token is always sent. [limit] is capped at [SEARCH_MAX] (Spotify's maximum
     * since February 2026); use [offset] for more. Null on failure, an empty result for a blank query.
     */
    suspend fun search(query: String, types: String = "track,artist,playlist,show,episode", limit: Int = SEARCH_MAX, offset: Int = 0): SpSearchResult? {
        if (query.isBlank()) return SpSearchResult()
        val q = URLEncoder.encode(query, "UTF-8")
        val n = limit.coerceIn(1, SEARCH_MAX)
        val off = if (offset > 0) "&offset=$offset" else ""
        return get("$BASE/search?type=$types&limit=$n$off&market=from_token&q=$q")
    }

    private val trackCache = java.util.concurrent.ConcurrentHashMap<String, SpTrack>()
    private val fetchGate = Semaphore(4)

    /**
     * Several tracks by id. The batch GET /tracks?ids= is gone, so this is one GET /tracks/{id}
     * per id, at most four in flight, cached for the session. Ids Spotify does not know are skipped.
     */
    suspend fun tracks(ids: List<String>): List<SpTrack>? {
        if (ids.isEmpty()) return emptyList()
        val wanted = ids.distinct()
        val fetched = coroutineScope {
            wanted.map { id -> async { trackCache[id] ?: fetchGate.withPermit { track(id) }?.also { trackCache[id] = it } } }.map { it.await() }
        }
        val found = fetched.filterNotNull()
        return if (found.isEmpty() && wanted.isNotEmpty()) null else found
    }

    suspend fun track(id: String): SpTrack? = get("$BASE/tracks/$id")

    suspend fun artist(id: String): SpArtist? = get("$BASE/artists/$id")

    // ---- Playlist edits ----

    /** New private playlist for the signed-in user (POST /me/playlists; the /users/{id} form is gone). */
    suspend fun createPlaylist(name: String): SpPlaylist? {
        val body = buildJsonObject { put("name", name); put("public", false) }
        return send("POST", "$BASE/me/playlists", body)
    }

    suspend fun renamePlaylist(id: String, name: String): Boolean =
        sendOk("PUT", "$BASE/playlists/$id", buildJsonObject { put("name", name) })

    /**
     * "Delete" in Motif: Spotify has no delete, removing the playlist from the library
     * (DELETE /me/library with its URI) takes it off the user's list. [uri] is spotify:playlist:...
     */
    suspend fun unfollowPlaylist(uri: String): Boolean = removeFromLibrary(listOf(uri))

    /** Saves tracks, albums, artists, shows, playlists... to the user's library by URI (PUT /me/library). */
    suspend fun saveToLibrary(uris: List<String>): Boolean = libraryCall("PUT", uris)

    /** Removes items from the user's library by URI (DELETE /me/library). */
    suspend fun removeFromLibrary(uris: List<String>): Boolean = libraryCall("DELETE", uris)

    /**
     * Spotify's migration guide shows {"uris":[...]} in the body for PUT /me/library but does not
     * say whether DELETE reads the body or a `uris` query parameter, so the body is tried first and
     * the query form second. Only which form was accepted is logged.
     */
    private suspend fun libraryCall(method: String, uris: List<String>): Boolean {
        if (uris.isEmpty()) return true
        for (chunk in uris.chunked(LIBRARY_MAX)) {
            val body = buildJsonObject { put("uris", JsonArray(chunk.map { JsonPrimitive(it) })) }
            if (sendOk(method, "$BASE/me/library", body)) continue
            val q = URLEncoder.encode(chunk.joinToString(","), "UTF-8")
            if (sendOk(method, "$BASE/me/library?uris=$q", null)) {
                Log.i(TAG, "$method /me/library accepted uris as a query parameter, not in the body")
                continue
            }
            return false
        }
        return true
    }

    suspend fun addToPlaylist(id: String, uris: List<String>): Boolean =
        sendOk("POST", "$BASE/playlists/$id/items", buildJsonObject { put("uris", JsonArray(uris.map { JsonPrimitive(it) })) })

    /** Removes every occurrence of each URI. The body field is `items` (was `tracks` before February 2026). */
    suspend fun removeFromPlaylist(id: String, uris: List<String>): Boolean {
        val body = buildJsonObject {
            put("items", buildJsonArray { uris.forEach { add(buildJsonObject { put("uri", it) }) } })
        }
        return sendOk("DELETE", "$BASE/playlists/$id/items", body)
    }

    /** Moves [rangeLength] items starting at [rangeStart] so they land before [insertBefore]. Returns the new snapshot. */
    suspend fun reorderPlaylist(id: String, rangeStart: Int, insertBefore: Int, rangeLength: Int = 1, snapshotId: String? = null): String? {
        val body = buildJsonObject {
            put("range_start", rangeStart)
            put("insert_before", insertBefore)
            put("range_length", rangeLength)
            if (snapshotId != null) put("snapshot_id", snapshotId)
        }
        return send<SpSnapshot>("PUT", "$BASE/playlists/$id/items", body)?.snapshot_id
    }

    // ---- Plumbing ----

    private suspend inline fun <reified T> pageAll(firstUrl: String): List<T>? {
        val out = ArrayList<T>()
        var url: String? = firstUrl
        var guard = 0
        while (url != null && guard++ < 100) {
            val page = get<SpPage<T>>(url) ?: return if (out.isEmpty()) null else out
            out += page.items
            url = page.next
        }
        return out
    }

    private suspend inline fun <reified T> get(url: String): T? = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: return@withContext null
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET ${url.substringBefore('?').removePrefix(BASE)} -> ${resp.code}")
                    return@withContext null
                }
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return@withContext null
                json.decodeFromString<T>(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend inline fun <reified T> send(method: String, url: String, body: JsonObject?): T? = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: return@withContext null
        val rb = (body?.let { json.encodeToString(it) } ?: "").toRequestBody(jsonType)
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, rb).build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "$method ${url.removePrefix(BASE)} -> ${resp.code}")
                    return@withContext null
                }
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return@withContext null
                json.decodeFromString<T>(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun sendOk(method: String, url: String, body: JsonObject?): Boolean = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: return@withContext false
        val rb = (body?.let { json.encodeToString(it) } ?: "").toRequestBody(jsonType)
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, rb).build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) Log.w(TAG, "$method ${url.removePrefix(BASE)} -> ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method failed: ${e.javaClass.simpleName}")
            false
        }
    }

    companion object {
        private const val BASE = "https://api.spotify.com/v1"
        private const val TAG = "MotifApi"
        /** Spotify's search page cap since February 2026 (was 50). */
        const val SEARCH_MAX = 10
        /** URIs per /me/library request. */
        private const val LIBRARY_MAX = 40
    }
}
