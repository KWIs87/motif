package com.kirkisham.motif.alarm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.alarmStore: DataStore<Preferences> by preferencesDataStore("motif_alarms")

/** Alarms as one JSON list in DataStore. Few alarms, small objects; no database needed. */
class AlarmStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("alarms")

    val alarms: Flow<List<Alarm>> = context.alarmStore.data.map { decode(it[key]) }

    suspend fun all(): List<Alarm> = alarms.first()
    suspend fun get(id: String): Alarm? = all().firstOrNull { it.id == id }

    suspend fun save(alarm: Alarm) = context.alarmStore.edit { p ->
        val cur = decode(p[key]).toMutableList()
        val i = cur.indexOfFirst { it.id == alarm.id }
        if (i >= 0) cur[i] = alarm else cur += alarm
        p[key] = json.encodeToString(cur)
    }

    suspend fun delete(id: String) = context.alarmStore.edit { p ->
        p[key] = json.encodeToString(decode(p[key]).filterNot { it.id == id })
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val a = get(id) ?: return
        save(a.copy(enabled = enabled))
    }

    private fun decode(s: String?): List<Alarm> =
        s?.let { runCatching { json.decodeFromString<List<Alarm>>(it) }.getOrNull() } ?: emptyList()
}
