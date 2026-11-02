package com.kirkisham.motif.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.motifStore: DataStore<Preferences> by preferencesDataStore("motif_prefs")

/** Appearance settings, app-wide (spec §2). Colors are ARGB ints. */
@Serializable
data class Appearance(
    val textColor: Int = 0xFFE8E6E1.toInt(),
    val backgroundColor: Int = 0xFF121212.toInt(),
    /** S, M or L. */
    val textSize: String = "M",
    val glowColor: Int = 0xFFC9A96E.toInt(),
    /** 0..100 */
    val glowIntensity: Int = 20,
) {
    val textScale: Float get() = when (textSize) { "S" -> 0.9f; "L" -> 1.15f; else -> 1f }
}

/** The widget's own settings, used when "Widget uses app settings" is off. */
@Serializable
data class WidgetStyle(
    val textColor: Int = 0xFFE8E6E1.toInt(),
    val backgroundColor: Int = 0xFF1A1A1A.toInt(),
    /** 0..100 */
    val backgroundOpacity: Int = 100,
    val textSize: String = "M",
    val glowColor: Int = 0xFFC9A96E.toInt(),
    val glowIntensity: Int = 20,
)

/** The last podcast episode Motif saw playing, so Home can offer it again. */
@Serializable
data class LastEpisode(
    val uri: String,
    val name: String,
    val showName: String,
    val imageUrl: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val seenAt: Long = 0,
)

/** Small per-user state kept on the phone: pins, recency, appearance. Nothing secret lives here. */
class Prefs(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val store get() = context.motifStore

    private object K {
        val pins = stringPreferencesKey("pins")                  // JSON list of playlist ids, in pin order
        val lastPlayed = stringPreferencesKey("last_played")     // JSON map playlist id -> epoch ms
        val recentAddedTo = stringPreferencesKey("recent_added") // JSON list of playlist ids, newest first (pre-0.9)
        val addedToAt = stringPreferencesKey("added_to_at")      // JSON map playlist id -> epoch ms of the last add from Motif
        val lastEpisode = stringPreferencesKey("last_episode")
        val podcastSpeeds = stringPreferencesKey("podcast_speeds") // JSON map show uri -> speed
        val appearance = stringPreferencesKey("appearance")
        val widgetStyle = stringPreferencesKey("widget_style")
        val widgetUsesApp = booleanPreferencesKey("widget_uses_app")
        val permissionsAsked = booleanPreferencesKey("permissions_asked")
        val locationAsked = booleanPreferencesKey("location_asked")
        val updateCheckedAt = longPreferencesKey("update_checked_at")
        val allSort = stringPreferencesKey("all_sort")
        val allSortReversed = booleanPreferencesKey("all_sort_reversed")   // 0.10: direction flipped from the sort's natural one
        val playlistSort = stringPreferencesKey("playlist_sort")   // JSON map playlist id -> sort name
        val playlistSortReversed = stringPreferencesKey("playlist_sort_reversed") // JSON list of playlist ids whose direction is flipped
    }

    val pins: Flow<List<String>> = store.data.map { decodeList(it[K.pins]) }
    val lastPlayed: Flow<Map<String, Long>> = store.data.map { decodeMap(it[K.lastPlayed]) }
    /**
     * When each playlist last received a song from Motif. Seeded from the pre-0.9 "recent_added" list
     * (newest first, one millisecond apart) when the map is still empty, so the order carries over.
     */
    val addedToAt: Flow<Map<String, Long>> = store.data.map { p ->
        val map = decodeMap(p[K.addedToAt])
        if (map.isNotEmpty()) map
        else decodeList(p[K.recentAddedTo]).withIndex().associate { (i, id) -> id to (1_000_000L - i) }
    }
    val lastEpisode: Flow<LastEpisode?> = store.data.map { p -> p[K.lastEpisode]?.let { runCatching { json.decodeFromString<LastEpisode>(it) }.getOrNull() } }
    val podcastSpeeds: Flow<Map<String, Float>> = store.data.map { p -> p[K.podcastSpeeds]?.let { runCatching { json.decodeFromString<Map<String, Float>>(it) }.getOrNull() } ?: emptyMap() }
    val appearance: Flow<Appearance> = store.data.map { p -> p[K.appearance]?.let { runCatching { json.decodeFromString<Appearance>(it) }.getOrNull() } ?: Appearance() }
    val widgetStyle: Flow<WidgetStyle> = store.data.map { p -> p[K.widgetStyle]?.let { runCatching { json.decodeFromString<WidgetStyle>(it) }.getOrNull() } ?: WidgetStyle() }
    val widgetUsesApp: Flow<Boolean> = store.data.map { it[K.widgetUsesApp] ?: true }
    val permissionsAsked: Flow<Boolean> = store.data.map { it[K.permissionsAsked] ?: false }
    val locationAsked: Flow<Boolean> = store.data.map { it[K.locationAsked] ?: false }
    val updateCheckedAt: Flow<Long> = store.data.map { it[K.updateCheckedAt] ?: 0L }
    val allSort: Flow<String> = store.data.map { it[K.allSort] ?: "Recent" }
    /** True when the All playlists sort runs against its natural direction (0.10, item 2). */
    val allSortReversed: Flow<Boolean> = store.data.map { it[K.allSortReversed] ?: false }
    val playlistSorts: Flow<Map<String, String>> = store.data.map { p -> p[K.playlistSort]?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap() }
    /** Playlist ids whose sort runs against its natural direction. */
    val playlistSortReversed: Flow<Set<String>> = store.data.map { decodeList(it[K.playlistSortReversed]).toSet() }

    suspend fun togglePin(playlistId: String) = store.edit { p ->
        val cur = decodeList(p[K.pins]).toMutableList()
        if (!cur.remove(playlistId)) cur += playlistId
        p[K.pins] = json.encodeToString(cur)
    }

    suspend fun markPlayed(playlistId: String) = store.edit { p ->
        val cur = decodeMap(p[K.lastPlayed]).toMutableMap()
        cur[playlistId] = System.currentTimeMillis()
        // Keep the map small.
        val trimmed = cur.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
        p[K.lastPlayed] = json.encodeToString(trimmed)
    }

    suspend fun markAddedTo(playlistId: String) = store.edit { p ->
        val cur = decodeMap(p[K.addedToAt]).toMutableMap()
        if (cur.isEmpty()) {
            // First write since 0.9: carry the old list over so earlier targets keep their order.
            decodeList(p[K.recentAddedTo]).withIndex().forEach { (i, id) -> cur[id] = 1_000_000L - i }
        }
        cur[playlistId] = System.currentTimeMillis()
        val trimmed = cur.entries.sortedByDescending { it.value }.take(500).associate { it.key to it.value }
        p[K.addedToAt] = json.encodeToString(trimmed)
    }

    suspend fun forgetPlaylist(playlistId: String) = store.edit { p ->
        p[K.pins] = json.encodeToString(decodeList(p[K.pins]) - playlistId)
        p[K.recentAddedTo] = json.encodeToString(decodeList(p[K.recentAddedTo]) - playlistId)
        p[K.addedToAt] = json.encodeToString(decodeMap(p[K.addedToAt]) - playlistId)
        p[K.lastPlayed] = json.encodeToString(decodeMap(p[K.lastPlayed]) - playlistId)
    }

    suspend fun setLastEpisode(e: LastEpisode) = store.edit { it[K.lastEpisode] = json.encodeToString(e) }

    suspend fun setPodcastSpeed(showUri: String, speed: Float) = store.edit { p ->
        val cur = p[K.podcastSpeeds]?.let { runCatching { json.decodeFromString<Map<String, Float>>(it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()
        cur[showUri] = speed
        p[K.podcastSpeeds] = json.encodeToString(cur)
    }

    suspend fun setAppearance(a: Appearance) = store.edit { it[K.appearance] = json.encodeToString(a) }
    suspend fun setWidgetStyle(w: WidgetStyle) = store.edit { it[K.widgetStyle] = json.encodeToString(w) }
    suspend fun setWidgetUsesApp(on: Boolean) = store.edit { it[K.widgetUsesApp] = on }
    suspend fun setPermissionsAsked() = store.edit { it[K.permissionsAsked] = true }
    suspend fun setLocationAsked() = store.edit { it[K.locationAsked] = true }
    suspend fun setUpdateCheckedAt(ms: Long) = store.edit { it[K.updateCheckedAt] = ms }
    /** Picks a sort; a different sort starts in its natural direction, the same one flips direction. */
    suspend fun setAllSort(sort: String) = store.edit { p ->
        val same = (p[K.allSort] ?: "Recent") == sort
        p[K.allSort] = sort
        p[K.allSortReversed] = if (same) !(p[K.allSortReversed] ?: false) else false
    }
    suspend fun setPlaylistSort(playlistId: String, sort: String) = store.edit { p ->
        val cur = p[K.playlistSort]?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }?.toMutableMap() ?: mutableMapOf()
        val same = (cur[playlistId] ?: "Custom") == sort
        cur[playlistId] = sort
        p[K.playlistSort] = json.encodeToString(cur)
        val rev = decodeList(p[K.playlistSortReversed]).toMutableSet()
        if (same && sort != "Custom") { if (!rev.remove(playlistId)) rev += playlistId } else rev -= playlistId
        p[K.playlistSortReversed] = json.encodeToString(rev.toList())
    }

    suspend fun appearanceNow(): Appearance = appearance.first()
    suspend fun widgetStyleNow(): WidgetStyle = widgetStyle.first()
    suspend fun widgetUsesAppNow(): Boolean = widgetUsesApp.first()

    private fun decodeList(s: String?): List<String> =
        s?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList()

    private fun decodeMap(s: String?): Map<String, Long> =
        s?.let { runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() } ?: emptyMap()
}
