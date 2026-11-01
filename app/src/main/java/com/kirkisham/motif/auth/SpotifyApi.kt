package com.kirkisham.motif.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Thin Spotify Web API client. Grows with the app; today it only proves login works. */
class SpotifyApi(private val auth: SpotifyAuth) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Me(val display_name: String? = null, val id: String, val product: String? = null)

    suspend fun me(): Me? = get("https://api.spotify.com/v1/me")

    private suspend inline fun <reified T> get(url: String): T? = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: return@withContext null
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            json.decodeFromString<T>(resp.body!!.string())
        }
    }
}
