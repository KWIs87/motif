package com.kirkisham.motif.auth

import android.util.Log
import com.kirkisham.motif.data.ApiLog
import com.kirkisham.motif.data.SpAlbum
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpCurrentlyPlaying
import com.kirkisham.motif.data.SpDevice
import com.kirkisham.motif.data.SpDevices
import com.kirkisham.motif.data.SpEpisode
import com.kirkisham.motif.data.SpPage
import com.kirkisham.motif.data.SpPlaylist
import com.kirkisham.motif.data.SpPlaylistItem
import com.kirkisham.motif.data.SpSavedShow
import com.kirkisham.motif.data.SpSearchResult
import com.kirkisham.motif.data.SpSnapshot
import com.kirkisham.motif.data.SpTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
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
import java.util.concurrent.atomic.AtomicInteger

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
    suspend fun playlistTracks(id: String, background: Boolean = false): List<SpPlaylistItem>? {
        val fields = URLEncoder.encode(
            "items(added_at,item(id,name,uri,type,is_local,duration_ms,artists(name,uri,id),album(id,name,uri,images,release_date))),next,total,offset",
            "UTF-8",
        )
        return pageAllLenient("$BASE/playlists/$id/items?limit=100&additional_types=track,episode&fields=$fields", background)
    }

    suspend fun savedShows(): List<SpSavedShow>? = pageAll("$BASE/me/shows?limit=50")

    /** Most recent episodes of a show, newest first. resume_point needs the user-read-playback-position scope. */
    suspend fun showEpisodes(showId: String, limit: Int = 5): List<SpEpisode>? =
        get<SpPage<SpEpisode>>("$BASE/shows/$showId/episodes?limit=$limit")?.items

    suspend fun episode(id: String): SpEpisode? = get("$BASE/episodes/$id")

    /** What Spotify is playing on any device right now, episodes included. Null when nothing is. */
    suspend fun currentlyPlaying(): SpCurrentlyPlaying? = get("$BASE/me/player?additional_types=episode")

    // ---- Spotify Connect ----

    /** Every device the account can play on right now (GET /me/player/devices). Null on failure. */
    suspend fun devices(): List<SpDevice>? = get<SpDevices>("$BASE/me/player/devices")?.devices

    /** Moves playback to [deviceId] and keeps playing (PUT /me/player with device_ids). */
    suspend fun transferPlayback(deviceId: String, play: Boolean = true): Boolean =
        sendOk("PUT", "$BASE/me/player", buildJsonObject {
            put("device_ids", JsonArray(listOf(JsonPrimitive(deviceId))))
            put("play", play)
        })

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

    // ---- Albums ----

    /**
     * Every release that credits the artist: own albums, singles and EPs, compilations, and other
     * artists' releases they appear on (`album_group` says which). Pages of 50 up to [ARTIST_ALBUMS_MAX];
     * market=from_token keeps it to what the account can play. Null when the first page fails (the
     * status is logged once by [get]; under Development Mode this may be 403 or 404).
     */
    suspend fun artistAlbums(id: String): List<SpAlbum>? =
        pageAll("$BASE/artists/$id/albums?include_groups=album,single,compilation,appears_on&limit=50&market=from_token", ARTIST_ALBUMS_MAX)

    /** One album with its artists, cover, release date and track count. */
    suspend fun album(id: String): SpAlbum? = get("$BASE/albums/$id?market=from_token")

    /** The album's songs in album order (simplified tracks: no `album` field, so callers attach it). */
    suspend fun albumTracks(id: String): List<SpTrack>? = pageAll("$BASE/albums/$id/tracks?limit=50&market=from_token")

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

    /** Why the last call failed, for one plain line in the UI. */
    sealed interface ApiError {
        /** Spotify answered 429; [untilMs] is when its Retry-After runs out. */
        data class RateLimited(val untilMs: Long) : ApiError
        /** Two 401s in a row: the refresh did not help; the user must sign in again. */
        object SignInAgain : ApiError
        object SignedOut : ApiError
        data class Http(val code: Int) : ApiError
        data class Network(val kind: String) : ApiError
    }

    private val _lastError = MutableStateFlow<ApiError?>(null)
    /** The most recent failure (cleared by the next success); screens read it to word their one line. */
    val lastError: StateFlow<ApiError?> = _lastError.asStateFlow()
    private val _rateLimitedUntil = MutableStateFlow(0L)
    /** Epoch ms until which Spotify asked us to wait (0 when not rate-limited). */
    val rateLimitedUntil: StateFlow<Long> = _rateLimitedUntil.asStateFlow()

    /** User-initiated calls in flight; background work waits while this is above zero. */
    private val userInFlight = AtomicInteger(0)
    /** Background calls (index warm pass) run one at a time, at least [BACKGROUND_GAP_MS] apart. */
    private val backgroundGate = Mutex()
    private var lastBackgroundAt = 0L
    /** After any 429 the background pass stays off until this time (60 s), whatever Retry-After said. */
    @Volatile private var backgroundPausedUntil = 0L

    /** True while a background call should hold off: a user load is running or Spotify is rate-limiting. */
    fun backgroundBlocked(): Boolean = userInFlight.get() > 0 || System.currentTimeMillis() < maxOf(backgroundPausedUntil, _rateLimitedUntil.value)

    /** Follows `next` until the list ends or [max] items are in hand. */
    private suspend inline fun <reified T> pageAll(firstUrl: String, max: Int = Int.MAX_VALUE): List<T>? {
        val out = ArrayList<T>()
        var url: String? = firstUrl
        var guard = 0
        while (url != null && guard++ < 100 && out.size < max) {
            val page = get<SpPage<T>>(url) ?: return if (out.isEmpty()) null else out
            out += page.items
            url = page.next
        }
        return if (out.size > max) out.take(max) else out
    }

    /**
     * Like [pageAll] but decodes each row on its own, so one row Spotify shapes unexpectedly (a new
     * field type, a null where none was expected) is skipped and counted instead of emptying the whole
     * page (0.12.1). The skip count goes to the API log.
     */
    private suspend inline fun <reified T> pageAllLenient(firstUrl: String, background: Boolean): List<T>? {
        val out = ArrayList<T>()
        var url: String? = firstUrl
        var guard = 0
        var skipped = 0
        while (url != null && guard++ < 100) {
            val page = get<SpPage<JsonElement>>(url, background) ?: return if (out.isEmpty()) null else out
            for (el in page.items) {
                runCatching { json.decodeFromJsonElement<T>(el) }.onSuccess { out += it }.onFailure { skipped++ }
            }
            url = page.next
        }
        if (skipped > 0) ApiLog.call("DECODE", firstUrl.substringBefore('?').removePrefix(BASE), 0, 0, "$skipped rows skipped")
        return out
    }

    private suspend inline fun <reified T> get(url: String, background: Boolean = false): T? {
        val text = execute("GET", url, null, background) ?: return null
        if (text.isBlank()) return null
        return try { json.decodeFromString<T>(text) } catch (e: Exception) {
            ApiLog.call("DECODE", url.substringBefore('?').removePrefix(BASE), 0, 0, e.javaClass.simpleName)
            null
        }
    }

    private suspend inline fun <reified T> send(method: String, url: String, body: JsonObject?): T? {
        val text = execute(method, url, body, false) ?: return null
        if (text.isBlank()) return null
        return try { json.decodeFromString<T>(text) } catch (e: Exception) {
            ApiLog.call("DECODE", url.substringBefore('?').removePrefix(BASE), 0, 0, e.javaClass.simpleName)
            null
        }
    }

    private suspend fun sendOk(method: String, url: String, body: JsonObject?): Boolean = execute(method, url, body, false) != null

    /**
     * One HTTP call with the 0.12.1 rules. Returns the body text on 2xx, null on any failure (the reason
     * lands in [lastError] and the API log). 401: refresh the token once and retry; a second 401 is
     * [ApiError.SignInAgain]. 429: remember Retry-After; a user call waits for it once when it is short
     * ([RETRY_WAIT_MAX_MS]) and retries, otherwise gives up with [ApiError.RateLimited] so the screen can
     * say so and retry when the time is up. Background calls never wait inside; they are gated before.
     * Any exception (network, token refresh) is caught here, never thrown to a screen.
     */
    private suspend fun execute(method: String, url: String, body: JsonObject?, background: Boolean): String? = withContext(Dispatchers.IO) {
        val path = url.substringBefore('?').removePrefix(BASE)
        if (background) {
            while (backgroundBlocked()) delay(250)
            backgroundGate.lock()
        } else userInFlight.incrementAndGet()
        try {
            if (background) {
                val gap = BACKGROUND_GAP_MS - (System.currentTimeMillis() - lastBackgroundAt)
                if (gap > 0) delay(gap)
            }
            var refreshed = false
            var waited = false
            var t0 = System.currentTimeMillis()
            var token = try { auth.accessToken() } catch (e: Exception) { null }
            while (true) {
                if (token == null) {
                    val signedIn = auth.snapshot().signedIn
                    _lastError.value = if (signedIn) ApiError.SignInAgain else ApiError.SignedOut
                    ApiLog.call(method, path, 0, 0, if (signedIn) "no token after refresh" else "signed out")
                    return@withContext null
                }
                val rb = if (method == "GET") null else (body?.let { json.encodeToString(it) } ?: "").toRequestBody(jsonType)
                val req = Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, rb).build()
                t0 = System.currentTimeMillis()
                val result: Pair<Int, String?> = try {
                    http.newCall(req).execute().use { resp ->
                        val code = resp.code
                        when {
                            resp.isSuccessful -> code to (resp.body?.string().orEmpty())
                            code == 429 -> {
                                val secs = resp.header("Retry-After")?.trim()?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_S
                                val until = System.currentTimeMillis() + secs * 1000
                                _rateLimitedUntil.value = maxOf(_rateLimitedUntil.value, until)
                                backgroundPausedUntil = maxOf(backgroundPausedUntil, System.currentTimeMillis() + BACKGROUND_PAUSE_MS)
                                ApiLog.call(method, path, code, System.currentTimeMillis() - t0, "Retry-After $secs s")
                                code to null
                            }
                            else -> { ApiLog.call(method, path, code, System.currentTimeMillis() - t0); code to null }
                        }
                    }
                } catch (e: Exception) {
                    ApiLog.call(method, path, 0, System.currentTimeMillis() - t0, e.javaClass.simpleName)
                    _lastError.value = ApiError.Network(e.javaClass.simpleName)
                    return@withContext null
                }
                val (code, text) = result
                if (text != null) {
                    ApiLog.call(method, path, code, System.currentTimeMillis() - t0)
                    if (System.currentTimeMillis() >= _rateLimitedUntil.value) _rateLimitedUntil.value = 0
                    // A success clears the last error, except a rate limit that is still running on another path.
                    if (_rateLimitedUntil.value == 0L || _lastError.value !is ApiError.RateLimited) _lastError.value = null
                    return@withContext text
                }
                when {
                    code == 401 && !refreshed -> {
                        refreshed = true
                        token = try { auth.accessToken(force = true, staleSince = t0) } catch (e: Exception) { null }
                        continue
                    }
                    code == 401 -> { _lastError.value = ApiError.SignInAgain; return@withContext null }
                    code == 429 -> {
                        val wait = _rateLimitedUntil.value - System.currentTimeMillis()
                        if (!background && !waited && wait in 1..RETRY_WAIT_MAX_MS) {
                            waited = true
                            delay(wait + 200)
                            continue
                        }
                        _lastError.value = ApiError.RateLimited(_rateLimitedUntil.value)
                        return@withContext null
                    }
                    else -> { _lastError.value = ApiError.Http(code); return@withContext null }
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } finally {
            if (background) { lastBackgroundAt = System.currentTimeMillis(); backgroundGate.unlock() } else userInFlight.decrementAndGet()
        }
    }

    /**
     * Starts [contextUri] at the exact item [itemUri] on the user's active device (Web API player endpoint).
     * Exact by URI, unlike App Remote's skipToIndex, whose index drifts past items Spotify can no longer play.
     * Returns false when there is no active device (Spotify app asleep) or the call fails.
     */
    suspend fun playContextAt(contextUri: String, itemUri: String): Boolean =
        sendOk("PUT", "$BASE/me/player/play", buildJsonObject {
            put("context_uri", contextUri)
            put("offset", buildJsonObject { put("uri", itemUri) })
        })

    companion object {
        private const val BASE = "https://api.spotify.com/v1"
        private const val TAG = "MotifApi"
        /** Spotify's search page cap since February 2026 (was 50). */
        const val SEARCH_MAX = 10
        /** URIs per /me/library request. */
        private const val LIBRARY_MAX = 40
        /** Releases fetched per artist for the discography (four pages of 50). */
        private const val ARTIST_ALBUMS_MAX = 200
        /** A user call waits out a 429 once when Retry-After is at most this long; longer waits are shown instead. */
        private const val RETRY_WAIT_MAX_MS = 15_000L
        /** When a 429 comes without Retry-After. */
        private const val DEFAULT_RETRY_AFTER_S = 30L
        /** The background pass stays off this long after any 429. */
        private const val BACKGROUND_PAUSE_MS = 60_000L
        /** Spacing between background calls. */
        private const val BACKGROUND_GAP_MS = 600L
    }
}
