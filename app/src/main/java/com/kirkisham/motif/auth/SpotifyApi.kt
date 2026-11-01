package com.kirkisham.motif.auth

import android.util.Log
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
 * Endpoints removed for new developer apps (audio-features, recommendations) are not used.
 */
class SpotifyApi(private val auth: SpotifyAuth) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    @Serializable
    data class Me(val display_name: String? = null, val id: String, val product: String? = null)

    suspend fun me(): Me? = get("$BASE/me")

    // ---- Library ----

    /** Every playlist the user owns or follows, in Spotify's order. */
    suspend fun allPlaylists(): List<SpPlaylist>? = pageAll("$BASE/me/playlists?limit=50")

    suspend fun playlist(id: String): SpPlaylist? =
        get("$BASE/playlists/$id?fields=id,name,uri,images,tracks(total),owner(id,display_name),snapshot_id,description")

    /** All tracks of a playlist. Episodes inside playlists come back with type "episode" and are kept. */
    suspend fun playlistTracks(id: String): List<SpPlaylistItem>? {
        val fields = URLEncoder.encode(
            "items(added_at,track(id,name,uri,type,is_local,duration_ms,artists(name,uri,id),album(name,uri,images))),next,total,offset",
            "UTF-8",
        )
        return pageAll("$BASE/playlists/$id/tracks?limit=100&additional_types=track,episode&fields=$fields")
    }

    suspend fun savedShows(): List<SpSavedShow>? = pageAll("$BASE/me/shows?limit=50")

    /** Most recent episodes of a show, newest first. resume_point needs the user-read-playback-position scope. */
    suspend fun showEpisodes(showId: String, limit: Int = 5): List<SpEpisode>? =
        get<SpPage<SpEpisode>>("$BASE/shows/$showId/episodes?limit=$limit")?.items

    suspend fun episode(id: String): SpEpisode? = get("$BASE/episodes/$id")

    /** What Spotify is playing on any device right now, episodes included. Null when nothing is. */
    suspend fun currentlyPlaying(): SpCurrentlyPlaying? = get("$BASE/me/player?additional_types=episode")

    suspend fun searchTracks(query: String, limit: Int = 25): List<SpTrack>? {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        return get<SpSearchResult>("$BASE/search?type=track&limit=$limit&q=$q")?.tracks?.items
    }

    suspend fun tracks(ids: List<String>): List<SpTrack>? {
        if (ids.isEmpty()) return emptyList()
        return get<TracksWrap>("$BASE/tracks?ids=${ids.take(50).joinToString(",")}")?.tracks?.filterNotNull()
    }

    // ---- Playlist edits ----

    suspend fun createPlaylist(userId: String, name: String): SpPlaylist? {
        val body = buildJsonObject { put("name", name); put("public", false) }
        return send("POST", "$BASE/users/$userId/playlists", body)
    }

    suspend fun renamePlaylist(id: String, name: String): Boolean =
        sendOk("PUT", "$BASE/playlists/$id", buildJsonObject { put("name", name) })

    /** "Delete" in Motif: Spotify has no delete, unfollowing removes it from the library. */
    suspend fun unfollowPlaylist(id: String): Boolean = sendOk("DELETE", "$BASE/playlists/$id/followers", null)

    suspend fun addToPlaylist(id: String, uris: List<String>): Boolean =
        sendOk("POST", "$BASE/playlists/$id/tracks", buildJsonObject { put("uris", JsonArray(uris.map { JsonPrimitive(it) })) })

    suspend fun removeFromPlaylist(id: String, uris: List<String>): Boolean {
        val body = buildJsonObject {
            put("tracks", buildJsonArray { uris.forEach { add(buildJsonObject { put("uri", it) }) } })
        }
        return sendOk("DELETE", "$BASE/playlists/$id/tracks", body)
    }

    /** Moves [rangeLength] items starting at [rangeStart] so they land before [insertBefore]. Returns the new snapshot. */
    suspend fun reorderPlaylist(id: String, rangeStart: Int, insertBefore: Int, rangeLength: Int = 1, snapshotId: String? = null): String? {
        val body = buildJsonObject {
            put("range_start", rangeStart)
            put("insert_before", insertBefore)
            put("range_length", rangeLength)
            if (snapshotId != null) put("snapshot_id", snapshotId)
        }
        return send<SpSnapshot>("PUT", "$BASE/playlists/$id/tracks", body)?.snapshot_id
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

    @Serializable
    private data class TracksWrap(val tracks: List<SpTrack?> = emptyList())

    companion object {
        private const val BASE = "https://api.spotify.com/v1"
        private const val TAG = "MotifApi"
    }
}
