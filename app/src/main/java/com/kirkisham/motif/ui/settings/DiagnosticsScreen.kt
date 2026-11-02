package com.kirkisham.motif.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.ApiLog
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val STAMP = DateTimeFormatter.ofPattern("HH:mm:ss")
private val STAMP_LONG = DateTimeFormatter.ofPattern("MMM d  HH:mm:ss")

/**
 * Diagnostics (0.12.1, Settings → Diagnostics): the auth state and the last 50 Web API calls, so a
 * screenshot says what Spotify answered when something "won't load". Paths and ids only.
 */
@Composable
fun DiagnosticsScreen(app: MotifApp, onBack: () -> Unit) {
    val calls by ApiLog.calls.collectAsStateWithLifecycle()
    val authLogged by ApiLog.auth.collectAsStateWithLifecycle()
    val lastError by app.api.lastError.collectAsStateWithLifecycle()
    val rateLimitedUntil by app.api.rateLimitedUntil.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val auth = remember(authLogged, now) { app.spotifyAuth.snapshot() }
    val zone = ZoneId.systemDefault()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = MINI_PLAYER_SPACE),
    ) {
        item {
            TopBar("Diagnostics", onBack) {
                if (calls.isNotEmpty()) Text(
                    "Clear", style = MaterialTheme.typography.labelLarge, color = motif.textMuted,
                    modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { ApiLog.clear() }.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        item {
            Column(Modifier.padding(start = 8.dp)) {
                SectionLabel("Auth", Modifier.padding(bottom = 6.dp))
                KeyValue("Signed in", if (auth.signedIn) "yes" else "no")
                KeyValue("Token", when {
                    !auth.signedIn -> "none"
                    auth.expiresAt == 0L -> "unknown expiry"
                    auth.expiresAt > now -> "valid, ${minutes(auth.expiresAt - now)} left"
                    else -> "expired ${minutes(now - auth.expiresAt)} ago; refreshes on the next call"
                })
                KeyValue("Last refresh", when (auth.lastRefreshOk) {
                    null -> "none this session"
                    true -> "ok, " + Instant.ofEpochMilli(auth.lastRefreshAt).atZone(zone).format(STAMP_LONG)
                    false -> "failed (${auth.lastRefreshError.ifBlank { "unknown" }}), " + Instant.ofEpochMilli(auth.lastRefreshAt).atZone(zone).format(STAMP_LONG)
                })
                KeyValue("Rate limit", if (rateLimitedUntil > now) "Spotify asked us to wait ${minutes(rateLimitedUntil - now)} more" else "none")
                KeyValue("Last error", when (val e = lastError) {
                    null -> "none"
                    else -> e.toString().removePrefix("SpotifyApi.").substringAfterLast('$')
                })
                Spacer(Modifier.height(16.dp))
                SectionLabel("Last ${ApiLog.MAX} API calls", Modifier.padding(bottom = 6.dp))
                Text("time · method · path · status · ms. Paths carry Spotify ids only; no tokens, no names.", style = MaterialTheme.typography.bodySmall, color = motif.textMuted, lineHeight = 16.sp)
                Spacer(Modifier.height(6.dp))
            }
        }
        if (calls.isEmpty()) item { PlainLine("No calls logged yet.", Modifier.padding(start = 8.dp)) }
        items(calls, key = { "${it.at}-${it.method}-${it.path.hashCode()}-${it.status}" }) { c ->
            val bad = c.status < 200 || c.status >= 300 || c.error.isNotBlank()
            Column(Modifier.padding(start = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Instant.ofEpochMilli(c.at).atZone(zone).format(STAMP) + "  " + c.method + "  " + c.path,
                            fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = if (bad) motif.accent else motif.text,
                        )
                        Text(
                            (if (c.status == 0) "no response" else c.status.toString()) + "  ${c.ms} ms" + (if (c.error.isNotBlank()) "  " + c.error else ""),
                            fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, color = motif.textMuted,
                        )
                    }
                }
                Hairline()
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = motif.textMuted, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = motif.text, modifier = Modifier.weight(1f))
    }
}

private fun minutes(ms: Long): String {
    val m = (ms + 59_999) / 60_000
    return if (m < 2) "${(ms + 999) / 1000} s" else "$m min"
}
