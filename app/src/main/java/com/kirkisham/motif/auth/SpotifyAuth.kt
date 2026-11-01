package com.kirkisham.motif.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.kirkisham.motif.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify login with PKCE (Proof Key for Code Exchange): a public-client flow that needs
 * only the client ID, never the client secret, so nothing secret ships in the app.
 * Docs: https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
 */
class SpotifyAuth(private val context: Context, private val store: TokenStore) {

    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /** Everything Motif needs today. Add scopes here as features land; the user re-consents once. */
    private val scopes = listOf(
        "user-read-private",
        "user-read-email",
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "user-read-recently-played",
        "user-library-read",
        "user-library-modify",
        "playlist-read-private",
        "playlist-read-collaborative",
        "playlist-modify-private",
        "playlist-modify-public",
        "app-remote-control",
        "streaming",
    )

    /** Opens Spotify's login page in a Custom Tab. The result comes back to MainActivity as motif://callback. */
    fun startLogin() {
        val verifier = randomVerifier()
        store.pendingVerifier = verifier
        val challenge = sha256Base64Url(verifier)
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", scopes.joinToString(" "))
            .build()
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }

    /** Handles the motif://callback?code=... redirect. Returns true when tokens were stored. */
    suspend fun handleCallback(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code") ?: return false
        val verifier = store.pendingVerifier ?: return false
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
            .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        return exchange(body).also { if (it) store.pendingVerifier = null }
    }

    /** Returns a usable access token, refreshing if it is within a minute of expiry. Null when logged out. */
    suspend fun accessToken(): String? {
        val refresh = store.refreshToken ?: return null
        if (System.currentTimeMillis() < store.expiresAt - 60_000) return store.accessToken
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .build()
        return if (exchange(body)) store.accessToken else null
    }

    fun logout() = store.clear()

    private suspend fun exchange(body: FormBody): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            val t = json.decodeFromString<TokenResponse>(resp.body!!.string())
            store.accessToken = t.access_token
            t.refresh_token?.let { store.refreshToken = it }
            store.expiresAt = System.currentTimeMillis() + t.expires_in * 1000L
            true
        }
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val expires_in: Int,
        val refresh_token: String? = null,
    )

    private fun randomVerifier(): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
