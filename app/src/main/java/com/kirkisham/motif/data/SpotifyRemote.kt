package com.kirkisham.motif.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kirkisham.motif.BuildConfig
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.PlayerApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.PlaybackSpeed
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedHashMap
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * One connection to the Spotify app through App Remote, shared by every screen, the alarm
 * service and the widget. Callers take a hold while they need it; the connection lives while
 * at least one hold exists and reconnects with backoff when Spotify drops it.
 *
 * Everything here runs on the main thread (App Remote requires it). Nothing about the user's
 * library is logged.
 */
class SpotifyRemote(private val context: Context) {

    sealed interface Connection {
        data object Idle : Connection
        data object Connecting : Connection
        data object Connected : Connection
        data object NotInstalled : Connection
        data class Failed(val reason: String) : Connection
    }

    /** Snapshot of what Spotify is doing, flattened from the SDK's PlayerState. */
    data class Playback(
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        /** For episodes this is the show's URI (Spotify maps show -> album). */
        val albumUri: String?,
        val imageUri: String?,
        val durationMs: Long,
        val positionMs: Long,
        /** SystemClock.elapsedRealtime() when positionMs was reported. */
        val reportedAt: Long,
        val isPaused: Boolean,
        val speed: Float,
        val isEpisode: Boolean,
        val isPodcast: Boolean,
        val isShuffling: Boolean,
        val repeatMode: Int,
        val canSkipNext: Boolean,
        val canSkipPrev: Boolean,
        val canSeek: Boolean,
    ) {
        /** Position right now, extrapolated from the last report while playing. */
        fun positionNow(): Long {
            if (isPaused) return positionMs
            val elapsed = ((SystemClock.elapsedRealtime() - reportedAt) * (if (speed > 0f) speed else 1f)).toLong()
            return min(durationMs, positionMs + elapsed)
        }
        val isPlaying: Boolean get() = !isPaused
    }

    private val handler = Handler(Looper.getMainLooper())
    private var remote: SpotifyAppRemote? = null
    private var stateSub: Subscription<PlayerState>? = null
    private var contextSub: Subscription<PlayerContext>? = null
    private val holds = mutableSetOf<String>()
    private var retryAttempt = 0
    private var retryPosted: Runnable? = null

    private val _connection = MutableStateFlow<Connection>(Connection.Idle)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    /** Title of what is playing from (playlist, album, show), or null. */
    private val _contextTitle = MutableStateFlow<String?>(null)
    val contextTitle: StateFlow<String?> = _contextTitle.asStateFlow()
    private val _contextUri = MutableStateFlow<String?>(null)
    val contextUri: StateFlow<String?> = _contextUri.asStateFlow()

    /** Small LRU of album art by image URI so screens do not re-fetch the same cover. */
    private val artCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }

    val isSpotifyInstalled: Boolean get() = SpotifyAppRemote.isSpotifyInstalled(context)
    val isConnected: Boolean get() = remote?.isConnected == true

    /** Keep the connection alive while [tag] holds it. Safe to call from any thread. */
    fun acquire(tag: String) = onMain {
        holds += tag
        connectIfNeeded()
    }

    /** Drop a hold; the connection closes when nobody holds it. */
    fun release(tag: String) = onMain {
        holds -= tag
        if (holds.isEmpty()) disconnect()
    }

    private fun connectIfNeeded() {
        if (holds.isEmpty()) return
        if (remote?.isConnected == true || _connection.value == Connection.Connecting) return
        if (!isSpotifyInstalled) {
            _connection.value = Connection.NotInstalled
            return
        }
        _connection.value = Connection.Connecting
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) {
                remote = r
                retryAttempt = 0
                _connection.value = Connection.Connected
                subscribe(r)
                if (holds.isEmpty()) disconnect()
            }

            override fun onFailure(t: Throwable) {
                Log.w(TAG, "App Remote connect failed: ${t.javaClass.simpleName}")
                remote = null
                _connection.value = Connection.Failed(t.javaClass.simpleName)
                scheduleRetry()
            }
        })
    }

    private fun subscribe(r: SpotifyAppRemote) {
        stateSub?.cancel()
        stateSub = r.playerApi.subscribeToPlayerState()
            .setEventCallback { ps -> _playback.value = ps.toPlayback() }
            .setErrorCallback { t ->
                Log.w(TAG, "Player state stream ended: ${t.javaClass.simpleName}")
                onDropped()
            } as Subscription<PlayerState>
        // Prime the state immediately rather than waiting for the first change.
        r.playerApi.playerState.setResultCallback { ps -> _playback.value = ps.toPlayback() }
        contextSub?.cancel()
        contextSub = r.playerApi.subscribeToPlayerContext()
            .setEventCallback { c -> _contextTitle.value = c.title?.takeIf { it.isNotBlank() }; _contextUri.value = c.uri }
            .setErrorCallback { } as Subscription<PlayerContext>
    }

    private fun onDropped() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        stateSub = null
        contextSub = null
        if (holds.isNotEmpty()) {
            _connection.value = Connection.Failed("Disconnected")
            scheduleRetry()
        } else {
            _connection.value = Connection.Idle
        }
    }

    private fun scheduleRetry() {
        retryPosted?.let { handler.removeCallbacks(it) }
        if (holds.isEmpty()) return
        val delay = min(30_000L, 1_000L shl min(retryAttempt, 5))
        retryAttempt++
        val r = Runnable { retryPosted = null; connectIfNeeded() }
        retryPosted = r
        handler.postDelayed(r, delay)
    }

    private fun disconnect() {
        retryPosted?.let { handler.removeCallbacks(it) }
        retryPosted = null
        stateSub?.cancel()
        stateSub = null
        contextSub?.cancel()
        contextSub = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        retryAttempt = 0
        _connection.value = Connection.Idle
    }

    // ---- Commands. Each returns true when Spotify accepted it. ----

    suspend fun play(uri: String): Boolean = call { it.play(uri) }
    suspend fun playOnAlarmStream(uri: String): Boolean = call { it.play(uri, PlayerApi.StreamType.ALARM) }
    suspend fun pause(): Boolean = call { it.pause() }
    suspend fun resume(): Boolean = call { it.resume() }
    suspend fun next(): Boolean = call { it.skipNext() }
    suspend fun previous(): Boolean = call { it.skipPrevious() }
    suspend fun seekTo(ms: Long): Boolean = call { it.seekTo(ms) }
    suspend fun seekRelative(ms: Long): Boolean = call { it.seekToRelativePosition(ms) }
    suspend fun setShuffle(on: Boolean): Boolean = call { it.setShuffle(on) }
    suspend fun setRepeat(mode: Int): Boolean = call { it.setRepeat(mode) }
    suspend fun queue(uri: String): Boolean = call { it.queue(uri) }
    suspend fun skipToIndex(contextUri: String, index: Int): Boolean = call { it.skipToIndex(contextUri, index) }

    /** Podcast speed: Spotify accepts 0.5, 0.8, 1, 1.2, 1.5, 2, 3. 1.25x maps to 1.2x. */
    suspend fun setPodcastSpeed(speed: Float): Boolean {
        val s = when {
            speed <= 0.5f -> PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_50
            speed <= 0.8f -> PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_80
            speed <= 1.0f -> PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_100
            speed <= 1.25f -> PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_120
            speed <= 1.5f -> PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_150
            speed <= 2.0f -> PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_200
            else -> PlaybackSpeed.PodcastPlaybackSpeed.PLAYBACK_SPEED_300
        }
        return call { it.setPodcastPlaybackSpeed(s) }
    }

    /** Toggle based on the last known state. */
    suspend fun togglePlayPause(): Boolean {
        val p = _playback.value ?: return resume()
        return if (p.isPaused) resume() else pause()
    }

    /** 15 s skips for episodes; track skips otherwise. */
    suspend fun skipForward(): Boolean =
        if (_playback.value?.isEpisode == true) seekRelative(15_000) else next()

    suspend fun skipBack(): Boolean =
        if (_playback.value?.isEpisode == true) seekRelative(-15_000) else previous()

    /** Album art through App Remote. Null when not connected or Spotify has none. */
    suspend fun loadArt(imageUri: String?, dimension: Image.Dimension = Image.Dimension.LARGE): Bitmap? {
        if (imageUri.isNullOrBlank()) return null
        val key = "$imageUri@${dimension.name}"
        artCache[key]?.let { return it }
        val r = remote?.takeIf { it.isConnected } ?: return null
        val bmp = suspendCancellableCoroutine<Bitmap?> { cont ->
            val result = r.imagesApi.getImage(ImageUri(imageUri), dimension)
            result.setResultCallback { if (cont.isActive) cont.resume(it) }
            result.setErrorCallback { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { result.cancel() }
        }
        if (bmp != null) artCache[key] = bmp
        return bmp
    }

    private suspend fun call(block: (PlayerApi) -> CallResult<*>): Boolean {
        val r = remote?.takeIf { it.isConnected } ?: return false
        return suspendCancellableCoroutine { cont ->
            try {
                val result = block(r.playerApi)
                result.setResultCallback { if (cont.isActive) cont.resume(true) }
                result.setErrorCallback { t ->
                    Log.w(TAG, "Command failed: ${t.javaClass.simpleName}")
                    if (cont.isActive) cont.resume(false)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Command threw: ${t.javaClass.simpleName}")
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun PlayerState.toPlayback(): Playback? {
        val t = track ?: return null
        return Playback(
            uri = t.uri ?: "",
            title = t.name ?: "",
            artist = t.artist?.name ?: t.artists?.firstOrNull()?.name ?: "",
            album = t.album?.name ?: "",
            albumUri = t.album?.uri,
            imageUri = t.imageUri?.raw,
            durationMs = t.duration,
            positionMs = playbackPosition,
            reportedAt = SystemClock.elapsedRealtime(),
            isPaused = isPaused,
            speed = playbackSpeed,
            isEpisode = t.isEpisode,
            isPodcast = t.isPodcast,
            isShuffling = playbackOptions?.isShuffling ?: false,
            repeatMode = playbackOptions?.repeatMode ?: Repeat.OFF,
            canSkipNext = playbackRestrictions?.canSkipNext ?: true,
            canSkipPrev = playbackRestrictions?.canSkipPrev ?: true,
            canSeek = playbackRestrictions?.canSeek ?: true,
        )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    companion object {
        private const val TAG = "MotifRemote"
    }
}
