package com.kirkisham.motif

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.kirkisham.motif.auth.SpotifyApi
import com.kirkisham.motif.ui.MotifColors
import com.kirkisham.motif.ui.MotifTheme
import kotlinx.coroutines.launch

/**
 * Build 0.1: proves the Spotify login round-trip. Screens from the wireframes land here next
 * (see docs/spec.md). Keep this file thin; screens live under ui/.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as MotifApp
    private var loggedIn by mutableStateOf(false)
    private var displayName by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        loggedIn = app.tokenStore.isLoggedIn
        handleIntent(intent)
        setContent {
            MotifTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (loggedIn) HomePlaceholder(displayName, status, onLogout = {
                        app.spotifyAuth.logout(); loggedIn = false; displayName = null
                    }) else LoginScreen(status) { app.spotifyAuth.startLogin() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "motif") return
        lifecycleScope.launch {
            status = "Signing in"
            val ok = app.spotifyAuth.handleCallback(uri)
            loggedIn = ok
            status = if (ok) null else "Spotify login failed. Try again."
        }
    }

    @Composable
    private fun HomePlaceholder(name: String?, status: String?, onLogout: () -> Unit) {
        LaunchedEffect(Unit) {
            displayName = SpotifyApi(app.spotifyAuth).me()?.display_name ?: "Spotify user"
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Motif", fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp)
            Text(
                name?.let { "Connected as $it" } ?: "Connecting",
                color = MotifColors.TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            status?.let { Text(it, color = MotifColors.TextMuted, modifier = Modifier.padding(top = 8.dp)) }
            TextButton(onClick = onLogout, modifier = Modifier.padding(top = 32.dp)) {
                Text("Sign out", color = MotifColors.TextMuted)
            }
        }
    }
}

@Composable
private fun LoginScreen(status: String?, onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Motif", fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp)
        Text(
            "Your Spotify library, your way.",
            color = MotifColors.TextMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 40.dp),
        )
        Button(onClick = onLogin) { Text("Connect Spotify") }
        status?.let { Text(it, color = MotifColors.TextMuted, modifier = Modifier.padding(top = 16.dp)) }
    }
}
