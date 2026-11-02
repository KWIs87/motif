package com.kirkisham.motif.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kirkisham.motif.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Settings "Updates" row (spec §4): reads the rolling `latest` GitHub release, compares the
 * version in the APK's name with this build, and installs through DownloadManager plus the
 * package installer. Checked on app start at most every 6 hours, and whenever the row is tapped.
 */
class Updates(private val context: Context, private val prefs: Prefs) {

    data class State(
        val installed: String = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
        /** Newer version on the release, or null when up to date / unknown. */
        val available: String? = null,
        /** Release notes line ("Build 0.7 from abc1234, 2026-11-02T00:33Z."). */
        val notes: String? = null,
        val downloadUrl: String? = null,
        val checking: Boolean = false,
        val downloading: Boolean = false,
        /** Epoch millis of the last successful check, 0 when never. */
        val checkedAt: Long = 0,
        /** One plain line when something went wrong. */
        val line: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Asset(val name: String, val browser_download_url: String)

    @Serializable
    private data class Release(val body: String? = null, val assets: List<Asset> = emptyList())

    /** Checks unless a check ran within the last 6 hours. */
    suspend fun checkIfDue() {
        val last = prefs.updateCheckedAt.first()
        if (System.currentTimeMillis() - last < SIX_HOURS) { _state.value = _state.value.copy(checkedAt = last); return }
        check()
    }

    suspend fun check() {
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, line = null)
        val rel = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(RELEASE_URL).header("Accept", "application/vnd.github+json").header("User-Agent", "Motif").build()
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { Log.w(TAG, "release -> ${resp.code}"); null }
                    else json.decodeFromString<Release>(resp.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                Log.w(TAG, "release failed: ${e.javaClass.simpleName}"); null
            }
        }
        if (rel == null) {
            _state.value = _state.value.copy(checking = false, line = "Could not reach GitHub to check for updates.")
            return
        }
        val versioned = rel.assets.firstNotNullOfOrNull { a -> APK_NAME.matchEntire(a.name)?.groupValues?.get(1) }
        val url = rel.assets.firstOrNull { it.name == "motif-latest.apk" }?.browser_download_url
            ?: rel.assets.firstOrNull { APK_NAME.matches(it.name) }?.browser_download_url
        val now = System.currentTimeMillis()
        prefs.setUpdateCheckedAt(now)
        val newer = versioned != null && compareVersions(versioned, _state.value.installed) > 0
        _state.value = _state.value.copy(
            checking = false,
            available = if (newer) versioned else null,
            notes = rel.body?.trim()?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() },
            downloadUrl = url,
            checkedAt = now,
        )
    }

    /** Downloads motif-latest.apk into the app's external files dir and hands it to the installer. */
    fun install() {
        val s = _state.value
        val url = s.downloadUrl ?: return
        if (s.downloading) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.getExternalFilesDir(null) ?: return
        val file = File(dir, FILE_NAME)
        if (file.exists()) file.delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Motif ${s.available ?: ""}".trim())
            .setDescription("Downloading the update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
        val id = try { dm.enqueue(req) } catch (e: Exception) {
            _state.value = s.copy(line = "Could not start the download.")
            return
        }
        _state.value = s.copy(downloading = true, line = null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { context.unregisterReceiver(this) }
                val ok = dm.query(DownloadManager.Query().setFilterById(id)).use { cur ->
                    cur.moveToFirst() && cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                }
                _state.value = _state.value.copy(downloading = false)
                if (!ok) { _state.value = _state.value.copy(line = "The download did not finish."); return }
                val uri = if (file.exists()) FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    else dm.getUriForDownloadedFile(id)
                if (uri == null) { _state.value = _state.value.copy(line = "The downloaded file is missing."); return }
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(view) } catch (e: Exception) {
                    _state.value = _state.value.copy(line = "Android could not open the installer.")
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    companion object {
        private const val TAG = "MotifUpdates"
        private const val RELEASE_URL = "https://api.github.com/repos/KWIs87/motif/releases/tags/latest"
        private const val FILE_NAME = "motif-latest.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val SIX_HOURS = 6 * 60 * 60 * 1000L
        /** motif-0.7-abc1234.apk, motif-0.7.1-abc1234-unsigned.apk */
        private val APK_NAME = Regex("""motif-(\d+(?:\.\d+)*)-[0-9a-fA-F]+(?:-unsigned)?\.apk""")

        /** Numeric, segment by segment; missing segments count as 0 ("0.7" == "0.7.0"). */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
