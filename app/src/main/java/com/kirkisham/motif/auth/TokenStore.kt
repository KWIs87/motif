package com.kirkisham.motif.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Spotify tokens, kept in encrypted preferences. Nothing here is ever logged. */
class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "motif_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var accessToken: String?
        get() = prefs.getString("access", null)
        set(v) = prefs.edit().putString("access", v).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh", null)
        set(v) = prefs.edit().putString("refresh", v).apply()

    /** Epoch millis when the access token stops working. */
    var expiresAt: Long
        get() = prefs.getLong("expires_at", 0L)
        set(v) = prefs.edit().putLong("expires_at", v).apply()

    /** PKCE verifier for the login currently in flight. */
    var pendingVerifier: String?
        get() = prefs.getString("verifier", null)
        set(v) = prefs.edit().putString("verifier", v).apply()

    val isLoggedIn: Boolean get() = refreshToken != null

    fun clear() = prefs.edit().clear().apply()
}
