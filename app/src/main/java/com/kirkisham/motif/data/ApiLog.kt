package com.kirkisham.motif.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/**
 * API diagnostics (0.12.1): the last [MAX] Web API calls (method, path without query, status, ms,
 * a short error) and the auth state (token expiry, last refresh and its result), kept as a small
 * persisted ring buffer on the AlarmLog pattern and shown on Settings → Diagnostics. Paths carry
 * Spotify ids only; never tokens, keys, names or anything from the library.
 */
object ApiLog {
    const val MAX = 50

    @Serializable
    data class Call(val at: Long, val method: String, val path: String, val status: Int, val ms: Long, val error: String = "")

    /** Auth state as the client sees it. Times are epoch ms (0 = never). */
    @Serializable
    data class Auth(
        val signedIn: Boolean = false,
        val expiresAt: Long = 0,
        val lastRefreshAt: Long = 0,
        val lastRefreshOk: Boolean? = null,
        val lastRefreshError: String = "",
        /** When Spotify's 429 says to wait until (0 = not rate-limited). */
        val rateLimitedUntil: Long = 0,
    )

    private const val TAG = "MotifApi"
    private val json = Json { ignoreUnknownKeys = true }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "motif-apilog").apply { isDaemon = true } }
    private var file: File? = null
    @Volatile private var loaded = false

    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    /** Newest first. */
    val calls: StateFlow<List<Call>> = _calls.asStateFlow()
    private val _auth = MutableStateFlow(Auth())
    val auth: StateFlow<Auth> = _auth.asStateFlow()

    /** Points the log at its file and reads what was there. Call once from the Application. */
    fun init(dir: File) {
        val f = File(dir, "api_log.json").also { file = it }
        io.execute {
            if (loaded) return@execute
            loaded = true
            val list = runCatching { if (f.exists()) json.decodeFromString<List<Call>>(f.readText()) else emptyList() }.getOrDefault(emptyList())
            _calls.value = (_calls.value + list).sortedByDescending { it.at }.take(MAX)
        }
    }

    /** One call. [path] must already be stripped of its query string. Safe from any thread. */
    fun call(method: String, path: String, status: Int, ms: Long, error: String = "") {
        if (status < 200 || status >= 300 || error.isNotBlank()) Log.w(TAG, "$method $path -> $status ${error.take(80)}")
        val e = Call(System.currentTimeMillis(), method, path, status, ms, error.take(80))
        io.execute {
            val list = (listOf(e) + _calls.value).take(MAX)
            _calls.value = list
            file?.let { f -> runCatching { f.writeText(json.encodeToString(list)) } }
        }
    }

    fun auth(update: (Auth) -> Auth) { _auth.value = update(_auth.value) }

    fun clear() {
        _calls.value = emptyList()
        io.execute { file?.let { f -> runCatching { f.delete() } } }
    }
}
