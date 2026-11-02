package com.kirkisham.motif.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.kirkisham.motif.auth.SpotifyApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spotify Connect devices (0.10, item 5). One list shared by the Now playing header, the mini
 * player's speaker icon and the device sheet; [refresh] is called by whoever is showing it
 * (the sheet every 5 s while open, Now playing every 15 s). Nothing about devices is logged.
 */
class Devices(private val api: SpotifyApi, context: Context) {
    private val _devices = MutableStateFlow<List<SpDevice>?>(null)
    /** Null until the first answer; empty when Spotify lists nothing. */
    val devices: StateFlow<List<SpDevice>?> = _devices.asStateFlow()

    private val _line = MutableStateFlow<String?>(null)
    /** One plain line when a transfer or refresh failed. */
    val line: StateFlow<String?> = _line.asStateFlow()

    /** Names this phone may appear under in Spotify's list: the user's device name and the model. */
    private val localNames: List<String> = listOfNotNull(
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull(),
        Build.MODEL,
        Build.DEVICE,
    ).filter { it.isNotBlank() }.map { it.lowercase() }

    suspend fun refresh(): List<SpDevice>? {
        val list = api.devices()
        if (list != null) { _devices.value = list; _line.value = null }
        else if (_devices.value == null) _line.value = "Spotify did not list any devices."
        return list
    }

    /** True for the active device when it is not this phone (so the UI can name it). */
    fun activeElsewhere(): SpDevice? {
        val active = _devices.value?.firstOrNull { it.is_active } ?: return null
        return if (isThisPhone(active)) null else active
    }

    /**
     * Whether a device is the phone Motif runs on: a Smartphone whose name matches the device name
     * or model, or the only smartphone in the list.
     */
    fun isThisPhone(d: SpDevice): Boolean {
        if (!d.type.equals("Smartphone", true)) return false
        val n = d.name.lowercase()
        if (localNames.any { n.contains(it) || it.contains(n) }) return true
        return _devices.value?.count { it.type.equals("Smartphone", true) } == 1
    }

    suspend fun transfer(d: SpDevice): Boolean {
        val id = d.id ?: return false
        val ok = api.transferPlayback(id, play = true)
        _line.value = if (ok) null else "Spotify could not move playback to ${d.name}."
        if (ok) {
            // Mark it active at once; the next refresh confirms.
            _devices.value = _devices.value?.map { it.copy(is_active = it.id == id) }
        }
        return ok
    }

    fun clearLine() { _line.value = null }
}
