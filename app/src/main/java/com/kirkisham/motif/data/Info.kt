package com.kirkisham.motif.data

import android.util.Log
import com.kirkisham.motif.BuildConfig
import com.kirkisham.motif.auth.SpotifyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One credit line: "Producer name" / "Producer". */
data class Credit(val name: String, val role: String)

/** Facts for the Artist section; every field optional, the section hides when all are empty. */
data class ArtistFacts(
    val origin: String? = null,
    /** "2011 to now", "1965 to 1970", "Born 1987". */
    val active: String? = null,
    val members: Int? = null,
    val genres: List<String> = emptyList(),
    val bio: String? = null,
    val wikipediaUrl: String? = null,
)

data class Concert(val venue: String, val city: String, val date: String, val url: String?)

/** An artist's releases grouped by Spotify's `album_group`, newest first; empty groups are hidden. */
data class Discography(
    val albums: List<SpAlbum> = emptyList(),
    val singles: List<SpAlbum> = emptyList(),
    val compilations: List<SpAlbum> = emptyList(),
    val appearsOn: List<SpAlbum> = emptyList(),
) {
    val isEmpty: Boolean get() = albums.isEmpty() && singles.isEmpty() && compilations.isEmpty() && appearsOn.isEmpty()
    /** Label and rows for each non-empty group, in display order. */
    val groups: List<Pair<String, List<SpAlbum>>> get() = listOf(
        "Albums" to albums, "Singles & EPs" to singles, "Compilations" to compilations, "Appears on" to appearsOn,
    ).filter { it.second.isNotEmpty() }
}

/** Everything the Song and artist info screen shows. */
data class SongInfo(
    val credits: List<Credit> = emptyList(),
    val tags: List<String> = emptyList(),
    val similar: List<SpTrack> = emptyList(),
    val facts: ArtistFacts? = null,
    val concerts: List<Concert>? = null,
)

/**
 * Outside data for Song and artist info (spec §4): Genius or Discogs for credits, Last.fm for
 * tags and similar songs, MusicBrainz → Wikidata → Wikipedia for artist facts, Ticketmaster for
 * concerts. Every lookup is independent, runs off the main thread, fails silently (the section
 * hides) and is cached for 24 hours per track or artist so free APIs are not hammered.
 */
class Info(private val api: SpotifyApi) {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val cache = HashMap<String, Pair<Long, Any?>>()

    private suspend fun <T> cached(key: String, load: suspend () -> T): T {
        val now = System.currentTimeMillis()
        lock.withLock { cache[key]?.let { (at, v) -> if (now - at < TTL_MS) @Suppress("UNCHECKED_CAST") return v as T } }
        val v = load()
        lock.withLock { cache[key] = now to v }
        return v
    }

    // ---- Per track ----

    suspend fun credits(track: SpTrack): List<Credit> = cached("credits:${track.uri}") {
        val artist = track.artists.firstOrNull()?.name ?: return@cached emptyList()
        (if (BuildConfig.GENIUS_ACCESS_TOKEN.isNotBlank()) geniusCredits(track.name, artist) else null)
            ?: discogsCredits(track.name, artist, track.album?.name)
            ?: emptyList()
    }

    suspend fun tags(track: SpTrack): List<String> = cached("tags:${track.uri}") {
        val artist = track.artists.firstOrNull()?.name ?: return@cached emptyList()
        val fromTrack = lastfm("track.getTopTags", "artist" to artist, "track" to track.name)
            ?.obj("toptags")?.arr("tag")?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
        val tags = if (fromTrack.size >= 3) fromTrack else fromTrack + artistTags(artist)
        tags.map { it.trim() }.filter { it.isNotBlank() && !it.equals(artist, true) && it.length <= 24 }
            .distinctBy { it.lowercase() }.take(5)
    }

    suspend fun similar(track: SpTrack): List<SpTrack> = cached("similar:${track.uri}") {
        val artist = track.artists.firstOrNull()?.name ?: return@cached emptyList()
        val pairs = ArrayList<Pair<String, String>>() // artist to title
        lastfm("track.getSimilar", "artist" to artist, "track" to track.name, "limit" to "8")
            ?.obj("similartracks")?.arr("track")?.forEach { t ->
                val o = t.jsonObject
                val a = o.obj("artist")?.str("name") ?: return@forEach
                val n = o.str("name") ?: return@forEach
                pairs += a to n
            }
        if (pairs.size < 5) {
            // Pad with the top track of each similar artist.
            val artists = lastfm("artist.getSimilar", "artist" to artist, "limit" to "6")
                ?.obj("similarartists")?.arr("artist")?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
            for (a in artists) {
                if (pairs.size >= 8) break
                val top = lastfm("artist.getTopTracks", "artist" to a, "limit" to "1")
                    ?.obj("toptracks")?.arr("track")?.firstOrNull()?.jsonObject?.str("name") ?: continue
                pairs += a to top
            }
        }
        coroutineScope {
            pairs.take(8).map { (a, n) -> async { matchOnSpotify(n, a) } }.mapNotNull { it.await() }
        }.filter { it.uri != track.uri }.distinctBy { it.uri }.take(6)
    }

    /** Artist mode (no track): the top song of each similar artist. */
    suspend fun similarForArtist(artist: SpArtist): List<SpTrack> = cached("similarArtist:${artist.id ?: artist.name}") {
        val artists = lastfm("artist.getSimilar", "artist" to artist.name, "limit" to "8")
            ?.obj("similarartists")?.arr("artist")?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
        val pairs = coroutineScope {
            artists.take(8).map { a ->
                async {
                    lastfm("artist.getTopTracks", "artist" to a, "limit" to "1")
                        ?.obj("toptracks")?.arr("track")?.firstOrNull()?.jsonObject?.str("name")?.let { a to it }
                }
            }.mapNotNull { it.await() }
        }
        coroutineScope { pairs.map { (a, n) -> async { matchOnSpotify(n, a) } }.mapNotNull { it.await() } }
            .distinctBy { it.uri }.take(6)
    }

    // ---- Per artist ----

    suspend fun facts(artist: SpArtist): ArtistFacts? = cached("facts:${artist.id ?: artist.name}") {
        val mbid = musicBrainzLookup(artist.name)
        val entity = mbid?.wikidataId?.let { wikidataEntity(it) }
        val wikiTitle = entity?.obj("sitelinks")?.obj("enwiki")?.str("title")
        val summary = wikiTitle?.let { wikipediaSummary(it) }
        val genres = artistTags(artist.name).take(3).ifEmpty { artist.genres.orEmpty().take(3) }
        val facts = ArtistFacts(
            origin = mbid?.origin,
            active = mbid?.active,
            members = entity?.let { membersFromWikidata(it) },
            genres = genres,
            bio = summary?.first,
            wikipediaUrl = summary?.second,
        )
        if (facts.origin == null && facts.active == null && facts.members == null && facts.genres.isEmpty() && facts.bio == null) null else facts
    }

    /**
     * Every release crediting the artist (GET /artists/{id}/albums, up to 200), grouped by
     * `album_group`, newest first, regional duplicates (same name and year) collapsed. Null when
     * Spotify refused or failed, so the section hides; the status is logged once under MotifApi.
     */
    suspend fun discography(artistId: String): Discography? = cached("discography:$artistId") {
        val all = api.artistAlbums(artistId) ?: return@cached null
        fun group(key: String) = all
            .filter { (it.album_group ?: it.album_type) == key && !it.name.isNullOrBlank() && it.albumId != null }
            .sortedByDescending { it.release_date ?: "" }
            .distinctBy { it.name!!.trim().lowercase() + "|" + (it.year ?: "") }
        Discography(
            albums = group("album"),
            singles = group("single"),
            compilations = group("compilation"),
            appearsOn = group("appears_on"),
        ).takeIf { !it.isEmpty }
    }

    /** Null when the Ticketmaster key is missing or the lookup failed; empty when nothing is near. */
    suspend fun concerts(artistName: String, lat: Double, lng: Double): List<Concert>? {
        if (BuildConfig.TICKETMASTER_API_KEY.isBlank()) return null
        val key = "concerts:$artistName:${"%.1f".format(lat)},${"%.1f".format(lng)}"
        return cached(key) {
            val url = "https://app.ticketmaster.com/discovery/v2/events.json?apikey=${BuildConfig.TICKETMASTER_API_KEY}" +
                "&keyword=${enc(artistName)}&latlong=$lat,$lng&radius=100&unit=miles&classificationName=music&sort=date,asc&size=10"
            val root = getJson(url, "Ticketmaster") ?: return@cached null
            root.obj("_embedded")?.arr("events")?.mapNotNull { e ->
                val o = e.jsonObject
                val name = o.str("name") ?: return@mapNotNull null
                // Keep events that actually name the artist (keyword search is loose).
                val attractions = o.obj("_embedded")?.arr("attractions")?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
                if (attractions.none { it.equals(artistName, true) } && !name.contains(artistName, true)) return@mapNotNull null
                val venue = o.obj("_embedded")?.arr("venues")?.firstOrNull()?.jsonObject
                val city = venue?.obj("city")?.str("name")
                val state = venue?.obj("state")?.str("stateCode")
                val date = o.obj("dates")?.obj("start")?.str("localDate") ?: return@mapNotNull null
                Concert(
                    venue = venue?.str("name") ?: name,
                    city = listOfNotNull(city, state).joinToString(", "),
                    date = date,
                    url = o.str("url"),
                )
            }.orEmpty().distinctBy { it.venue + it.date }.take(5)
        }
    }

    // ---- Genius ----

    private suspend fun geniusCredits(title: String, artist: String): List<Credit>? {
        val token = BuildConfig.GENIUS_ACCESS_TOKEN
        val search = getJson("https://api.genius.com/search?q=${enc("$title $artist")}", "Genius", "Authorization" to "Bearer $token") ?: return null
        val hits = search.obj("response")?.arr("hits") ?: return null
        val hit = hits.map { it.jsonObject.obj("result") }.firstOrNull { r ->
            r?.obj("primary_artist")?.str("name")?.let { nameMatch(it, artist) } == true
        } ?: hits.firstOrNull()?.jsonObject?.obj("result") ?: return null
        val id = hit.jsonPrimitiveInt("id") ?: return null
        val song = getJson("https://api.genius.com/songs/$id?text_format=plain", "Genius", "Authorization" to "Bearer $token")
            ?.obj("response")?.obj("song") ?: return null
        val out = ArrayList<Credit>()
        song.arr("writer_artists")?.forEach { it.jsonObject.str("name")?.let { n -> out += Credit(n, "Songwriter") } }
        song.arr("producer_artists")?.forEach { it.jsonObject.str("name")?.let { n -> out += Credit(n, "Producer") } }
        song.arr("featured_artists")?.forEach { it.jsonObject.str("name")?.let { n -> out += Credit(n, "Featured") } }
        song.arr("custom_performances")?.forEach { cp ->
            val role = cp.jsonObject.str("label") ?: return@forEach
            cp.jsonObject.arr("artists")?.forEach { a -> a.jsonObject.str("name")?.let { n -> out += Credit(n, role) } }
        }
        return out.distinctBy { it.name.lowercase() + it.role.lowercase() }.take(12).ifEmpty { null }
    }

    // ---- Discogs (no key) ----

    private suspend fun discogsCredits(title: String, artist: String, album: String?): List<Credit>? {
        val q = enc(if (album != null) "$artist $album" else "$artist $title")
        val search = getJson("https://api.discogs.com/database/search?q=$q&type=release&per_page=5", "Discogs") ?: return null
        val results = search.arr("results") ?: return null
        var found: List<Credit>? = null
        for (r in results.take(3)) {
            val id = r.jsonObject.jsonPrimitiveInt("id") ?: continue
            val release = getJson("https://api.discogs.com/releases/$id", "Discogs") ?: continue
            val out = ArrayList<Credit>()
            fun add(arr: JsonArray?) = arr?.forEach { c ->
                val o = c.jsonObject
                val n = o.str("name") ?: return@forEach
                val role = o.str("role")?.substringBefore('[')?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                if (!nameMatch(n, artist)) out += Credit(n.replace(Regex("""\s*\(\d+\)$"""), ""), role)
            }
            add(release.arr("extraartists"))
            // Per-track credits for this title, when the release lists them.
            release.arr("tracklist")?.forEach { t ->
                val o = t.jsonObject
                if (o.str("title")?.let { nameMatch(it, title) } == true) add(o.arr("extraartists"))
            }
            if (out.isNotEmpty()) { found = out.distinctBy { it.name.lowercase() + it.role.lowercase() }.take(12); break }
        }
        return found
    }

    // ---- Last.fm ----

    private suspend fun artistTags(artist: String): List<String> =
        lastfm("artist.getTopTags", "artist" to artist)?.obj("toptags")?.arr("tag")
            ?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
            .filter { !it.equals("seen live", true) && !it.equals(artist, true) }

    private suspend fun lastfm(method: String, vararg params: Pair<String, String>): JsonObject? {
        val key = BuildConfig.LASTFM_API_KEY
        if (key.isBlank()) return null
        val url = buildString {
            append("https://ws.audioscrobbler.com/2.0/?method=").append(method).append("&format=json&autocorrect=1&api_key=").append(key)
            params.forEach { (k, v) -> append('&').append(k).append('=').append(enc(v)) }
        }
        return getJson(url, "Last.fm")
    }

    private suspend fun matchOnSpotify(title: String, artist: String): SpTrack? {
        val r = api.search("track:${title} artist:${artist}", "track", 3)?.trackList.orEmpty()
            .ifEmpty { api.search("$title $artist", "track", 3)?.trackList.orEmpty() }
        return r.firstOrNull { t -> t.artists.any { nameMatch(it.name, artist) } } ?: r.firstOrNull()
    }

    // ---- MusicBrainz → Wikidata → Wikipedia ----

    private class MbArtist(val origin: String?, val active: String?, val wikidataId: String?)

    private suspend fun musicBrainzLookup(name: String): MbArtist? {
        val q = enc("artist:\"$name\"")
        val root = getJson("https://musicbrainz.org/ws/2/artist?query=$q&limit=3&fmt=json", "MusicBrainz") ?: return null
        val list = root.arr("artists") ?: return null
        val a = list.map { it.jsonObject }.firstOrNull { nameMatch(it.str("name") ?: "", name) } ?: list.firstOrNull()?.jsonObject ?: return null
        val id = a.str("id") ?: return null
        val detail = getJson("https://musicbrainz.org/ws/2/artist/$id?inc=url-rels&fmt=json", "MusicBrainz") ?: a
        val area = detail.obj("begin-area")?.str("name") ?: detail.obj("area")?.str("name")
        val span = detail.obj("life-span")
        val begin = span?.str("begin")?.take(4)
        val end = span?.str("end")?.take(4)
        val ended = span?.get("ended")?.jsonPrimitive?.booleanOrNull == true
        val isPerson = detail.str("type") == "Person"
        val active = when {
            begin == null -> null
            isPerson && end != null -> "Born $begin, died $end"
            isPerson -> "Born $begin"
            end != null -> "$begin to $end"
            ended -> "$begin, ended"
            else -> "$begin to now"
        }
        val wikidata = detail.arr("relations")?.mapNotNull { it.jsonObject }
            ?.firstOrNull { it.str("type") == "wikidata" }?.obj("url")?.str("resource")?.substringAfterLast('/')
        return MbArtist(area, active, wikidata)
    }

    private suspend fun wikidataEntity(qid: String): JsonObject? =
        getJson("https://www.wikidata.org/wiki/Special:EntityData/$qid.json", "Wikidata")?.obj("entities")?.obj(qid)

    /** Counts "has part" (P527) band members on the entity; null for solo artists or no data. */
    private fun membersFromWikidata(entity: JsonObject): Int? {
        val claims = entity.obj("claims") ?: return null
        val members = claims.arr("P527")?.size ?: 0
        return members.takeIf { it > 1 }
    }

    /** Extract summary and canonical page URL. */
    private suspend fun wikipediaSummary(title: String): Pair<String, String?>? {
        val root = getJson("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_'))}", "Wikipedia") ?: return null
        val extract = root.str("extract")?.takeIf { it.isNotBlank() } ?: return null
        val url = root.obj("content_urls")?.obj("mobile")?.str("page") ?: root.obj("content_urls")?.obj("desktop")?.str("page")
        return extract to url
    }

    // ---- Plumbing ----

    private suspend fun getJson(url: String, tag: String, vararg headers: Pair<String, String>): JsonObject? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        try {
            http.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "$tag -> ${resp.code}"); return@withContext null }
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return@withContext null
                json.parseToJsonElement(text) as? JsonObject
            }
        } catch (e: Exception) {
            Log.w(TAG, "$tag failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): JsonArray? = when (val v = this[k]) {
        is JsonArray -> v
        is JsonObject -> JsonArray(listOf(v)) // Last.fm returns a single object instead of a one-item list
        else -> null
    }
    private fun JsonObject.str(k: String): String? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.jsonPrimitiveInt(k: String): Int? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

    companion object {
        private const val TAG = "MotifInfo"
        private const val TTL_MS = 24 * 60 * 60 * 1000L
        private const val UA = "Motif/0.7 (personal Android client; github.com/KWIs87/motif)"

        /** Loose name match: case-insensitive, ignores punctuation and "The". */
        fun nameMatch(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase().replace(Regex("""^the\s+"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), "")
            val x = norm(a); val y = norm(b)
            return x.isNotEmpty() && (x == y || x.startsWith(y) || y.startsWith(x))
        }
    }
}
