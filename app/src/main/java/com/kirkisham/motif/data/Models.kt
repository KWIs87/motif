package com.kirkisham.motif.data

import kotlinx.serialization.Serializable

/** Spotify Web API shapes Motif reads. Only the fields used are declared; the rest are ignored. */

@Serializable
data class SpImage(val url: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class SpOwner(val id: String, val display_name: String? = null)

@Serializable
data class SpTracksRef(val total: Int = 0)

@Serializable
data class SpPlaylist(
    val id: String,
    val name: String,
    val uri: String,
    val images: List<SpImage>? = null,
    val tracks: SpTracksRef? = null,
    val owner: SpOwner? = null,
    val snapshot_id: String? = null,
    val description: String? = null,
) {
    val coverUrl: String? get() = images?.firstOrNull()?.url
    val smallCoverUrl: String? get() = images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url ?: coverUrl
    val trackCount: Int get() = tracks?.total ?: 0
}

@Serializable
data class SpArtist(
    val name: String,
    val uri: String? = null,
    val id: String? = null,
    /** Present on full artist objects (GET /artists/{id}, search). */
    val images: List<SpImage>? = null,
    val genres: List<String>? = null,
) {
    val imageUrl: String? get() = images?.firstOrNull()?.url
    val smallImageUrl: String? get() = images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url ?: imageUrl
}

@Serializable
data class SpAlbum(
    val name: String? = null,
    val uri: String? = null,
    val images: List<SpImage>? = null,
    val release_date: String? = null,
) {
    val year: String? get() = release_date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
}

@Serializable
data class SpTrack(
    val id: String? = null,
    val name: String = "",
    val uri: String = "",
    val duration_ms: Long = 0,
    val artists: List<SpArtist> = emptyList(),
    val album: SpAlbum? = null,
    val type: String? = null,
    val is_local: Boolean = false,
) {
    val artistNames: String get() = artists.joinToString(", ") { it.name }
    val coverUrl: String? get() = album?.images?.firstOrNull()?.url
    val smallCoverUrl: String? get() = album?.images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url ?: coverUrl
}

@Serializable
data class SpPlaylistItem(val added_at: String? = null, val track: SpTrack? = null)

@Serializable
data class SpPage<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val total: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class SpShow(
    val id: String,
    val name: String,
    val uri: String,
    val publisher: String? = null,
    val images: List<SpImage>? = null,
    val total_episodes: Int? = null,
) {
    val coverUrl: String? get() = images?.firstOrNull()?.url
    val smallCoverUrl: String? get() = images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url ?: coverUrl
}

@Serializable
data class SpSavedShow(val added_at: String? = null, val show: SpShow)

@Serializable
data class SpResumePoint(val fully_played: Boolean = false, val resume_position_ms: Long = 0)

@Serializable
data class SpEpisode(
    val id: String,
    val name: String,
    val uri: String,
    val duration_ms: Long = 0,
    val release_date: String? = null,
    val images: List<SpImage>? = null,
    val resume_point: SpResumePoint? = null,
    val show: SpShow? = null,
) {
    val coverUrl: String? get() = images?.firstOrNull()?.url ?: show?.coverUrl
    val smallCoverUrl: String? get() = images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url ?: show?.smallCoverUrl ?: coverUrl
}

/** GET /search. Spotify pads some pages with nulls, so every item type is nullable here. */
@Serializable
data class SpSearchResult(
    val tracks: SpPage<SpTrack?>? = null,
    val artists: SpPage<SpArtist?>? = null,
    val playlists: SpPage<SpPlaylist?>? = null,
    val shows: SpPage<SpShow?>? = null,
    val episodes: SpPage<SpEpisode?>? = null,
) {
    val trackList: List<SpTrack> get() = tracks?.items?.filterNotNull().orEmpty()
    val artistList: List<SpArtist> get() = artists?.items?.filterNotNull().orEmpty()
    val playlistList: List<SpPlaylist> get() = playlists?.items?.filterNotNull().orEmpty()
    val showList: List<SpShow> get() = shows?.items?.filterNotNull().orEmpty()
    val episodeList: List<SpEpisode> get() = episodes?.items?.filterNotNull().orEmpty()
    val isEmpty: Boolean get() = trackList.isEmpty() && artistList.isEmpty() && playlistList.isEmpty() && showList.isEmpty() && episodeList.isEmpty()
}

@Serializable
data class SpSnapshot(val snapshot_id: String)

@Serializable
data class SpCurrentlyPlaying(
    val currently_playing_type: String? = null,
    val is_playing: Boolean = false,
    val progress_ms: Long? = null,
    val item: SpEpisode? = null,
)
