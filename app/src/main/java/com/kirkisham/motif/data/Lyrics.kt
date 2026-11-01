package com.kirkisham.motif.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One timed line of synced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Synced lyrics from LRCLIB (no key). GET /api/get?artist_name&track_name&album_name&duration;
 * 404 means no match and the Lyrics button hides. Results are cached per track URI.
 */
class Lyrics {
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = object : LinkedHashMap<String, List<LyricLine>?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>?>?) = size > 40
    }

    @Serializable
    private data class Response(val syncedLyrics: String? = null, val plainLyrics: String? = null, val instrumental: Boolean = false)

    /** Null when LRCLIB has no synced lyrics for this track. */
    suspend fun forTrack(trackUri: String, title: String, artist: String, album: String, durationMs: Long): List<LyricLine>? {
        synchronized(cache) { if (cache.containsKey(trackUri)) return cache[trackUri] }
        val result = fetch(title, artist, album, durationMs)
        synchronized(cache) { cache[trackUri] = result }
        return result
    }

    private suspend fun fetch(title: String, artist: String, album: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = buildString {
            append("https://lrclib.net/api/get?artist_name=").append(enc(artist))
            append("&track_name=").append(enc(title))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationMs > 0) append("&duration=").append(durationMs / 1000)
        }
        val req = Request.Builder().url(url).header("User-Agent", "Motif/1.0 (personal Android client)").build()
        try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext null
                if (!resp.isSuccessful) { Log.w(TAG, "LRCLIB ${resp.code}"); return@withContext null }
                val body = json.decodeFromString<Response>(resp.body?.string().orEmpty())
                parse(body.syncedLyrics ?: return@withContext null).takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB failed: ${e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        private const val TAG = "MotifLyrics"
        private val STAMP = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        /** Parses "[mm:ss.xx] line" (several stamps on one line allowed). Sorted by time. */
        fun parse(lrc: String): List<LyricLine> {
            val out = ArrayList<LyricLine>()
            for (raw in lrc.lines()) {
                val stamps = STAMP.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val text = raw.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val fracStr = m.groupValues[3]
                    val frac = when (fracStr.length) { 0 -> 0L; 1 -> fracStr.toLong() * 100; 2 -> fracStr.toLong() * 10; else -> fracStr.take(3).toLong() }
                    out += LyricLine(min * 60_000 + sec * 1000 + frac, text)
                }
            }
            return out.sortedBy { it.timeMs }
        }
    }
}
