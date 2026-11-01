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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kirkisham.motif.data.Appearance
import com.kirkisham.motif.ui.MotifColors
import com.kirkisham.motif.ui.MotifNavHost
import com.kirkisham.motif.ui.MotifTheme
import com.kirkisham.motif.ui.Routes
import com.kirkisham.motif.widget.MotifWidget
import kotlinx.coroutines.launch

/** Entry point: login gate, then the navigation host. Screens live under ui/. */
class MainActivity : ComponentActivity() {

    private val app get() = application as MotifApp
    private var loggedIn by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)
    /** Route requested from outside (widget tap); consumed by the nav host. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        loggedIn = app.tokenStore.isLoggedIn
        handleIntent(intent)
        setContent {
            val appearance by app.prefs.appearance.collectAsStateWithLifecycle(Appearance())
            MotifTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (loggedIn) MotifNavHost(app, pendingRoute = pendingRoute, onRouteConsumed = { pendingRoute = null })
                    else LoginScreen(status) { app.spotifyAuth.startLogin(this@MainActivity) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (loggedIn) app.remote.acquire(HOLD)
    }

    override fun onStop() {
        super.onStop()
        app.remote.release(HOLD)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(MotifWidget.EXTRA_OPEN_NOW, false) == true) {
            intent.removeExtra(MotifWidget.EXTRA_OPEN_NOW)
            pendingRoute = Routes.NOW
        }
        val uri = intent?.data ?: return
        if (uri.scheme != "motif") return
        lifecycleScope.launch {
            status = "Signing in"
            val ok = app.spotifyAuth.handleCallback(uri)
            loggedIn = ok
            status = if (ok) null else "Spotify login failed. Try again."
            if (ok) app.remote.acquire(HOLD)
        }
    }

    private companion object { const val HOLD = "activity" }
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
