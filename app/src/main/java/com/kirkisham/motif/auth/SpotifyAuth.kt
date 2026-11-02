package com.kirkisham.motif.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.kirkisham.motif.BuildConfig
import com.kirkisham.motif.data.ApiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Opens Spotify's login page in a Custom Tab. The result comes back to MainActivity as motif://callback.
     * Must be given an Activity: launching from the application context throws without FLAG_ACTIVITY_NEW_TASK,
     * and a Custom Tab wants an activity anyway. Falls back to a plain browser intent if no Custom Tabs browser exists.
     */
    fun startLogin(activity: android.app.Activity) {
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
        try {
            CustomTabsIntent.Builder().setShowTitle(false).build().launchUrl(activity, uri)
        } catch (e: android.content.ActivityNotFoundException) {
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
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

    /**
     * Returns a usable access token, refreshing if it is within a minute of expiry; [force] refreshes
     * regardless (after a 401). Null when logged out or the refresh failed. One refresh at a time
     * (0.12.1): Spotify rotates the refresh token, so two parallel refreshes with the same old token
     * made the loser fail; now the second caller waits and reuses the first one's result. Never throws.
     */
    suspend fun accessToken(force: Boolean = false, staleSince: Long = 0L): String? {
        store.refreshToken ?: return null
        if (!force && System.currentTimeMillis() < store.expiresAt - 60_000) return store.accessToken
        return refreshLock.withLock {
            // Someone else may have refreshed while we waited: reuse it unless it predates the 401 we saw.
            val refresh = store.refreshToken ?: return@withLock null
            val fresh = System.currentTimeMillis() < store.expiresAt - 60_000
            if (fresh && (!force || lastRefreshAt > staleSince)) return@withLock store.accessToken
            lastRefreshAt = System.currentTimeMillis()
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                .build()
            val ok = exchange(body)
            ApiLog.auth { it.copy(signedIn = store.refreshToken != null, expiresAt = store.expiresAt, lastRefreshAt = System.currentTimeMillis(), lastRefreshOk = ok, lastRefreshError = if (ok) "" else lastError) }
            if (ok) store.accessToken else null
        }
    }

    private val refreshLock = Mutex()
    /** When the last refresh started (this process), so a 401 retry does not refresh a token refreshed since. */
    @Volatile private var lastRefreshAt = 0L
    private var lastError = ""

    /** Signed-in flag and token expiry for the Diagnostics screen; no token material. */
    fun snapshot(): ApiLog.Auth = ApiLog.auth.value.copy(signedIn = store.refreshToken != null, expiresAt = store.expiresAt)

    fun logout() {
        store.clear()
        ApiLog.auth { ApiLog.Auth() }
    }

    /** Token endpoint call. Returns false on any failure (HTTP, network, parse) and records why; never throws. */
    private suspend fun exchange(body: FormBody): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        val t0 = System.currentTimeMillis()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    lastError = "HTTP ${resp.code}"
                    ApiLog.call("POST", "accounts/api/token", resp.code, System.currentTimeMillis() - t0, lastError)
                    return@withContext false
                }
                val t = json.decodeFromString<TokenResponse>(resp.body!!.string())
                store.accessToken = t.access_token
                t.refresh_token?.let { store.refreshToken = it }
                store.expiresAt = System.currentTimeMillis() + t.expires_in * 1000L
                lastError = ""
                ApiLog.call("POST", "accounts/api/token", resp.code, System.currentTimeMillis() - t0)
                true
            }
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            ApiLog.call("POST", "accounts/api/token", 0, System.currentTimeMillis() - t0, lastError)
            false
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
