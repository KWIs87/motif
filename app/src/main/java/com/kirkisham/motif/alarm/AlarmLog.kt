package com.kirkisham.motif.alarm

import android.content.Context
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
 * Alarm log (0.12): a small persisted ring buffer of the last [MAX] events on the alarm fire path,
 * written at every step (scheduled, receiver fired, service started, foreground ok, remote
 * connected / failed, tone fallback, ringing shown, dismissed, snoozed, error) so a silent alarm
 * can be read back on the phone. Carries alarm ids (first 6 chars), times and failure reasons
 * only: never tokens, keys or anything from the library.
 */
object AlarmLog {
    const val MAX = 50

    @Serializable
    data class Entry(val at: Long, val event: String, val detail: String = "")

    private const val TAG = "MotifAlarm"
    private val json = Json { ignoreUnknownKeys = true }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "motif-alarmlog").apply { isDaemon = true } }
    private var file: File? = null
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Newest first. Empty until [load] has run once in this process. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    @Volatile private var loaded = false

    private fun fileFor(context: Context): File = file ?: File(context.applicationContext.filesDir, "alarm_log.json").also { file = it }

    /** Reads the file once per process (the first log or the log screen triggers it). */
    fun load(context: Context) {
        val f = fileFor(context)
        io.execute {
            if (loaded) return@execute
            loaded = true
            val list = runCatching { if (f.exists()) json.decodeFromString<List<Entry>>(f.readText()) else emptyList() }.getOrDefault(emptyList())
            _entries.value = (list + _entries.value).sortedByDescending { it.at }.take(MAX)
        }
    }

    /** Append one event. Safe from any thread; the write happens on one background thread, in order. */
    fun log(context: Context, event: String, detail: String = "") {
        Log.i(TAG, "$event ${detail.take(120)}")
        val f = fileFor(context)
        val e = Entry(System.currentTimeMillis(), event, detail)
        if (!loaded) load(context)
        io.execute {
            val list = (listOf(e) + _entries.value).take(MAX)
            _entries.value = list
            runCatching { f.writeText(json.encodeToString(list)) }
        }
    }

    fun error(context: Context, where: String, t: Throwable) =
        log(context, "error", "$where: ${t.javaClass.simpleName}: ${t.message?.take(140) ?: ""}")

    fun clear(context: Context) {
        val f = fileFor(context)
        io.execute {
            _entries.value = emptyList()
            runCatching { f.delete() }
        }
    }

    /** "id abc123" for log lines: six characters of the id, never the name or songs. */
    fun idOf(id: String): String = "id " + id.take(6)
}
