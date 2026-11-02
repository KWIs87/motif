package com.kirkisham.motif.data

import android.util.Log
import com.kirkisham.motif.BuildConfig
import com.kirkisham.motif.auth.SpotifyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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

/**
 * One credit row: a person and their roles joined with ", " (0.10, item 8). [discogsId] / [geniusId]
 * carry the source's artist id so the Person screen can list what else they worked on.
 */
data class Credit(val name: String, val role: String, val discogsId: Int? = null, val geniusId: Int? = null)

/** Consolidated credits plus where they came from ("Discogs" or "Genius"). */
data class Credits(val rows: List<Credit>, val source: String)

/** A release or song a person is credited on (Person screen). [track] / [albumId] when Spotify matched it. */
data class CreditedWork(
    val title: String,
    val artist: String,
    val year: String? = null,
    val role: String? = null,
    val track: SpTrack? = null,
    val albumId: String? = null,
)

/** About text for a person: Wikipedia summary first, Discogs profile second. */
data class About(val text: String, val url: String?, val source: String)

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

    /**
     * Credits (0.10, items 7 and 8): Genius when a token is built in and its hit names one of the
     * song's artists; otherwise Discogs, but only a release whose artist matches a Spotify artist.
     * Null when neither source has a trustworthy match, so the section hides.
     */
    suspend fun credits(track: SpTrack): Credits? = cached("credits:${track.uri}") {
        val artists = track.artists.map { it.name }.filter { it.isNotBlank() }
        if (artists.isEmpty()) return@cached null
        val genius = if (BuildConfig.GENIUS_ACCESS_TOKEN.isNotBlank()) geniusCredits(track.name, artists) else null
        if (genius != null) return@cached Credits(consolidate(genius), "Genius")
        val discogs = discogsCredits(track.name, artists, track.album?.name) ?: return@cached null
        Credits(consolidate(discogs), "Discogs")
    }

    suspend fun tags(track: SpTrack): List<String> = cached("tags:${track.uri}") {
        val artist = track.artists.firstOrNull()?.name ?: return@cached emptyList()
        val fromTrack = lastfm("track.getTopTags", "artist" to artist, "track" to track.name)
            ?.obj("toptags")?.arr("tag")?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
        val tags = if (fromTrack.size >= 3) fromTrack else fromTrack + artistTags(artist)
        val names = track.artists.map { it.name.lowercase() } + track.name.lowercase()
        tags.map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 24 && it.lowercase() !in JUNK_TAGS && it.lowercase() !in names }
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

    // ---- Credits: consolidation (item 8) ----

    /** Producer roles first, then writing roles, then the rest alphabetically. */
    private fun roleRank(role: String): Int {
        val r = role.lowercase()
        return when {
            r.contains("produc") -> 0
            r.contains("writ") || r.contains("lyric") || r.contains("compos") || r.contains("songwriter") -> 1
            else -> 2
        }
    }

    /** One row per person (case-insensitive), roles joined with ", " in a stable order; producers lead the list. */
    private fun consolidate(raw: List<Credit>): List<Credit> {
        class Person(val name: String, val roles: MutableList<String>, var discogsId: Int?, var geniusId: Int?)
        val people = LinkedHashMap<String, Person>()
        for (c in raw) {
            val name = c.name.replace(Regex("""\s*\(\d+\)$"""), "").trim()
            if (name.isEmpty()) continue
            val key = name.lowercase()
            val p = people.getOrPut(key) { Person(name, mutableListOf(), c.discogsId, c.geniusId) }
            if (p.discogsId == null) p.discogsId = c.discogsId
            if (p.geniusId == null) p.geniusId = c.geniusId
            if (p.roles.none { it.equals(c.role, true) }) p.roles += c.role
        }
        return people.values
            .map { p ->
                val roles = p.roles.sortedWith(compareBy<String> { roleRank(it) }.thenBy { it.lowercase() })
                Credit(p.name, roles.joinToString(", "), p.discogsId, p.geniusId) to roleRank(roles.first())
            }
            .withIndex()
            .sortedWith(compareBy<IndexedValue<Pair<Credit, Int>>> { it.value.second }.thenBy { it.index })
            .map { it.value.first }
            .take(14)
    }

    // ---- Genius ----

    private suspend fun geniusCredits(title: String, artists: List<String>): List<Credit>? {
        val token = BuildConfig.GENIUS_ACCESS_TOKEN
        val search = getJson("https://api.genius.com/search?q=${enc("$title ${artists.first()}")}", "Genius", "Authorization" to "Bearer $token") ?: return null
        val hits = search.obj("response")?.arr("hits") ?: return null
        // Only a hit whose primary artist is one of the song's artists counts (item 7: no loose fallback).
        val hit = hits.map { it.jsonObject.obj("result") }.firstOrNull { r ->
            val primary = r?.obj("primary_artist")?.str("name") ?: return@firstOrNull false
            artists.any { nameMatch(primary, it) } && (r.str("title")?.let { nameMatch(it, title) } ?: true)
        } ?: return null
        val id = hit.jsonPrimitiveInt("id") ?: return null
        val song = getJson("https://api.genius.com/songs/$id?text_format=plain", "Genius", "Authorization" to "Bearer $token")
            ?.obj("response")?.obj("song") ?: return null
        val out = ArrayList<Credit>()
        fun add(arr: JsonArray?, role: String) = arr?.forEach { a ->
            val o = a.jsonObject
            o.str("name")?.let { n -> out += Credit(n, role, geniusId = o.jsonPrimitiveInt("id")) }
        }
        add(song.arr("writer_artists"), "Songwriter")
        add(song.arr("producer_artists"), "Producer")
        add(song.arr("featured_artists"), "Featured")
        song.arr("custom_performances")?.forEach { cp ->
            val role = cp.jsonObject.str("label") ?: return@forEach
            add(cp.jsonObject.arr("artists"), role)
        }
        return out.ifEmpty { null }
    }

    // ---- Discogs (no key) ----

    /** "Artist (2)" → "Artist". */
    private fun discogsName(n: String) = n.replace(Regex("""\s*\(\d+\)$"""), "").trim()

    /**
     * A Discogs release list is only used when its artist is one of the song's Spotify artists
     * (item 7): the search result title reads "Artist - Title", and the release's own `artists`
     * are checked again. Search is artist + album, then artist + title; never the title alone.
     */
    private suspend fun discogsCredits(title: String, artists: List<String>, album: String?): List<Credit>? {
        fun artistOk(discogsArtist: String?): Boolean {
            val a = discogsArtist?.let(::discogsName) ?: return false
            return artists.any { nameMatch(a, it) }
        }
        val queries = listOfNotNull(album?.let { "${artists.first()} $it" }, "${artists.first()} $title").distinct()
        for (q in queries) {
            val search = getJson("https://api.discogs.com/database/search?q=${enc(q)}&type=release&per_page=6", "Discogs") ?: continue
            val results = search.arr("results")?.map { it.jsonObject }.orEmpty()
            val candidates = results.filter { r -> artistOk(r.str("title")?.substringBefore(" - ")) }.take(3)
            for (r in candidates) {
                val id = r.jsonPrimitiveInt("id") ?: continue
                val release = getJson("https://api.discogs.com/releases/$id", "Discogs") ?: continue
                val releaseArtists = release.arr("artists")?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
                if (releaseArtists.isNotEmpty() && releaseArtists.none { artistOk(it) }) continue
                val out = ArrayList<Credit>()
                fun add(arr: JsonArray?) = arr?.forEach { c ->
                    val o = c.jsonObject
                    val n = o.str("name") ?: return@forEach
                    val role = o.str("role")?.substringBefore('[')?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    // The main artist is left out so members and producers stand out.
                    if (artists.none { nameMatch(n, it) }) out += Credit(discogsName(n), role, discogsId = o.jsonPrimitiveInt("id"))
                }
                add(release.arr("extraartists"))
                // Per-track credits for this title, when the release lists them.
                release.arr("tracklist")?.forEach { t ->
                    val o = t.jsonObject
                    if (o.str("title")?.let { nameMatch(it, title) } == true) add(o.arr("extraartists"))
                }
                if (out.isNotEmpty()) return out
            }
        }
        return null
    }

    // ---- Person (item 9) ----

    /** Wikipedia summary through MusicBrainz → Wikidata → enwiki, else the Discogs artist profile. */
    suspend fun personAbout(name: String, discogsId: Int?): About? = cached("about:${name.lowercase()}:$discogsId") {
        val mb = musicBrainzLookup(name, strict = true)
        val entity = mb?.wikidataId?.let { wikidataEntity(it) }
        val wikiTitle = entity?.obj("sitelinks")?.obj("enwiki")?.str("title")
        val summary = wikiTitle?.let { wikipediaSummary(it) }
        if (summary != null) return@cached About(summary.first, summary.second, "Wikipedia")
        val id = discogsId ?: discogsArtistId(name) ?: return@cached null
        val artist = getJson("https://api.discogs.com/artists/$id", "Discogs") ?: return@cached null
        val profile = artist.str("profile")?.let(::stripDiscogsMarkup)?.takeIf { it.isNotBlank() } ?: return@cached null
        About(profile, artist.str("uri"), "Discogs")
    }

    /**
     * Releases or songs the person worked on: Genius artist songs when the credit came from Genius
     * (and a token exists), else the Discogs artist's releases (GET /artists/{id}/releases), each
     * matched on Spotify where possible. Unmatched rows still carry their text.
     */
    suspend fun personWorks(name: String, discogsId: Int?, geniusId: Int?): List<CreditedWork> = cached("works:${name.lowercase()}:$discogsId:$geniusId") {
        if (geniusId != null && BuildConfig.GENIUS_ACCESS_TOKEN.isNotBlank()) {
            val songs = getJson(
                "https://api.genius.com/artists/$geniusId/songs?sort=popularity&per_page=25", "Genius",
                "Authorization" to "Bearer ${BuildConfig.GENIUS_ACCESS_TOKEN}",
            )?.obj("response")?.arr("songs")?.map { it.jsonObject }.orEmpty()
            val rows = songs.mapNotNull { o ->
                val t = o.str("title") ?: return@mapNotNull null
                val a = o.obj("primary_artist")?.str("name") ?: return@mapNotNull null
                CreditedWork(t, a, year = o.obj("release_date_components")?.jsonPrimitiveInt("year")?.toString())
            }
            if (rows.isNotEmpty()) return@cached matchWorks(rows, tracks = true)
        }
        val id = discogsId ?: discogsArtistId(name) ?: return@cached emptyList()
        val releases = getJson("https://api.discogs.com/artists/$id/releases?sort=year&sort_order=desc&per_page=25", "Discogs")
            ?.arr("releases")?.map { it.jsonObject }.orEmpty()
        val rows = releases.mapNotNull { o ->
            val t = o.str("title") ?: return@mapNotNull null
            val a = o.str("artist")?.let(::discogsName) ?: return@mapNotNull null
            CreditedWork(t, a, year = o.jsonPrimitiveInt("year")?.takeIf { it > 0 }?.toString(), role = o.str("role")?.takeIf { it.isNotBlank() && it != "Main" })
        }.distinctBy { it.title.lowercase() + "|" + it.artist.lowercase() }
        matchWorks(rows, tracks = false)
    }

    /** Spotify matches for the first 20 rows, four at a time: tracks (Genius songs) or albums (Discogs releases). */
    private suspend fun matchWorks(rows: List<CreditedWork>, tracks: Boolean): List<CreditedWork> = coroutineScope {
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        rows.take(25).mapIndexed { i, w ->
            async {
                if (i >= 20) return@async w
                gate.withPermit {
                    if (tracks) w.copy(track = matchOnSpotify(w.title, w.artist))
                    else w.copy(albumId = matchAlbumOnSpotify(w.title, w.artist))
                }
            }
        }.map { it.await() }
    }

    private suspend fun matchAlbumOnSpotify(title: String, artist: String): String? {
        val various = artist.equals("Various", true) || artist.equals("Various Artists", true)
        val q = if (various) "album:$title" else "album:$title artist:$artist"
        val hits = api.search(q, "album", 5)?.albums?.items?.filterNotNull().orEmpty()
        val hit = hits.firstOrNull { a -> a.name?.let { nameMatch(it, title) } == true && (various || a.artists.any { nameMatch(it.name, artist) }) }
            ?: hits.firstOrNull { a -> various || a.artists.any { nameMatch(it.name, artist) } }
        return hit?.albumId
    }

    /** Similar listening for a person Last.fm knows (checked without autocorrect); empty otherwise. */
    suspend fun personSimilar(name: String): List<SpTrack> = cached("personSimilar:${name.lowercase()}") {
        val info = lastfm("artist.getInfo", "artist" to name, autocorrect = false)?.obj("artist") ?: return@cached emptyList()
        val known = info.str("name")?.let { nameMatch(it, name) } == true
        val listeners = info.obj("stats")?.str("listeners")?.toLongOrNull() ?: 0L
        if (!known || listeners < 1000) return@cached emptyList()
        similarForArtist(SpArtist(name = name))
    }

    private suspend fun discogsArtistId(name: String): Int? {
        val search = getJson("https://api.discogs.com/database/search?q=${enc(name)}&type=artist&per_page=5", "Discogs") ?: return null
        val results = search.arr("results")?.map { it.jsonObject }.orEmpty()
        return results.firstOrNull { r -> r.str("title")?.let { nameMatch(discogsName(it), name) } == true }?.jsonPrimitiveInt("id")
    }

    /** Discogs profile text uses [a=Name], [l=Label], [url=...]text[/url], [b]...[/b]; keep the words. */
    private fun stripDiscogsMarkup(s: String): String = s
        .replace(Regex("""\[(a|l|m|r)\d*=([^\]]+)\]"""), "$2")
        .replace(Regex("""\[url=[^\]]*\]([^\[]*)\[/url\]"""), "$1")
        .replace(Regex("""\[/?[a-z]+\]"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    // ---- Genre (item 10) ----

    /** Last.fm tag.getTopArtists (50) matched to Spotify artists, delivered in batches through [onPartial]. */
    suspend fun genreArtists(tag: String, onPartial: (List<SpArtist>) -> Unit = {}): List<SpArtist> = cached("genreArtists:${tag.lowercase()}") {
        val names = lastfm("tag.getTopArtists", "tag" to tag, "limit" to "50")?.obj("topartists")?.arr("artist")
            ?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
        val out = ArrayList<SpArtist>()
        for (chunk in names.chunked(8)) {
            val matched = coroutineScope { chunk.map { n -> async { matchArtistOnSpotify(n) } }.mapNotNull { it.await() } }
            out += matched
            onPartial(out.distinctBy { it.id ?: it.name }.toList())
        }
        out.distinctBy { it.id ?: it.name }
    }

    /** Last.fm tag.getTopTracks (50) matched to Spotify tracks, delivered in batches through [onPartial]. */
    suspend fun genreTracks(tag: String, onPartial: (List<SpTrack>) -> Unit = {}): List<SpTrack> = cached("genreTracks:${tag.lowercase()}") {
        val pairs = lastfm("tag.getTopTracks", "tag" to tag, "limit" to "50")?.obj("tracks")?.arr("track")
            ?.mapNotNull { t ->
                val o = t.jsonObject
                val n = o.str("name") ?: return@mapNotNull null
                val a = o.obj("artist")?.str("name") ?: return@mapNotNull null
                a to n
            }.orEmpty()
        val out = ArrayList<SpTrack>()
        for (chunk in pairs.chunked(8)) {
            val matched = coroutineScope { chunk.map { (a, n) -> async { matchOnSpotify(n, a) } }.mapNotNull { it.await() } }
            out += matched
            onPartial(out.distinctBy { it.uri }.toList())
        }
        out.distinctBy { it.uri }
    }

    private suspend fun matchArtistOnSpotify(name: String): SpArtist? {
        val hits = api.search("artist:$name", "artist", 3)?.artistList.orEmpty()
            .ifEmpty { api.search(name, "artist", 3)?.artistList.orEmpty() }
        return hits.firstOrNull { nameMatch(it.name, name) }
    }

    // ---- Last.fm ----

    private suspend fun artistTags(artist: String): List<String> =
        lastfm("artist.getTopTags", "artist" to artist)?.obj("toptags")?.arr("tag")
            ?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
            .filter { !it.equals("seen live", true) && !it.equals(artist, true) }

    private suspend fun lastfm(method: String, vararg params: Pair<String, String>, autocorrect: Boolean = true): JsonObject? {
        val key = BuildConfig.LASTFM_API_KEY
        if (key.isBlank()) return null
        val url = buildString {
            append("https://ws.audioscrobbler.com/2.0/?method=").append(method).append("&format=json&autocorrect=").append(if (autocorrect) 1 else 0).append("&api_key=").append(key)
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

    /** [strict]: only a name match counts (Person screen), never the first hit. */
    private suspend fun musicBrainzLookup(name: String, strict: Boolean = false): MbArtist? {
        val q = enc("artist:\"$name\"")
        val root = getJson("https://musicbrainz.org/ws/2/artist?query=$q&limit=3&fmt=json", "MusicBrainz") ?: return null
        val list = root.arr("artists") ?: return null
        val a = list.map { it.jsonObject }.firstOrNull { nameMatch(it.str("name") ?: "", name) }
            ?: (if (strict) null else list.firstOrNull()?.jsonObject) ?: return null
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
        private const val UA = "Motif/0.10 (personal Android client; github.com/KWIs87/motif)"

        /** Last.fm tags that say nothing about the music (0.10, item 7). */
        val JUNK_TAGS = setOf(
            "all", "favorites", "favorite", "favourite", "favourites", "seen live", "awesome", "love", "loved",
            "beautiful", "check out", "under 2000 listeners", "spotify",
        )

        /** Loose name match: case-insensitive, ignores punctuation and "The". */
        fun nameMatch(a: String, b: String): Boolean {
            fun norm(s: String) = s.lowercase().replace(Regex("""^the\s+"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), "")
            val x = norm(a); val y = norm(b)
            return x.isNotEmpty() && (x == y || x.startsWith(y) || y.startsWith(x))
        }
    }
}
