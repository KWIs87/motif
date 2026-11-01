package com.kirkisham.motif.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import com.kirkisham.motif.MainActivity
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.R
import com.kirkisham.motif.data.SpotifyRemote
import com.kirkisham.motif.data.WidgetStyle
import com.spotify.protocol.types.Image
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Home screen widget (spec §3.9): title, artist (or show and episode), previous, play/pause, next.
 * Buttons broadcast back here; the receiver connects App Remote if needed, sends the command,
 * and redraws from the next PlayerState. Tapping the text opens Now playing; tapping the art
 * opens the widget's settings.
 */
class MotifWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        update(context, intArrayOf(id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action !in setOf(ACTION_PREV, ACTION_PLAY, ACTION_NEXT)) return
        val result = goAsync()
        val app = MotifApp.get(context)
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try { command(app, action) } finally { result.finish() }
        }
    }

    private suspend fun command(app: MotifApp, action: String) {
        val remote = app.remote
        if (!remote.isSpotifyInstalled) return
        remote.acquire(HOLD)
        try {
            val ok = withTimeoutOrNull(8_000) { remote.connection.first { it == SpotifyRemote.Connection.Connected } } != null
            if (!ok) { Log.w(TAG, "Widget: Spotify did not connect"); return }
            when (action) {
                ACTION_PREV -> remote.skipBack()
                ACTION_NEXT -> remote.skipForward()
                ACTION_PLAY -> remote.togglePlayPause()
            }
            // Let the new state arrive, then redraw once.
            withTimeoutOrNull(2_500) { remote.playback.first { it != null } }
            kotlinx.coroutines.delay(400)
            renderAll(app)
        } finally {
            remote.release(HOLD)
        }
    }

    companion object {
        private const val TAG = "MotifWidget"
        private const val HOLD = "widget"
        const val ACTION_PREV = "com.kirkisham.motif.widget.PREV"
        const val ACTION_PLAY = "com.kirkisham.motif.widget.PLAY"
        const val ACTION_NEXT = "com.kirkisham.motif.widget.NEXT"
        const val EXTRA_OPEN_NOW = "open_now_playing"

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MotifWidget::class.java))

        fun hasWidgets(context: Context) = ids(context).isNotEmpty()

        /** Redraw every instance from the current playback state and style. Safe from any thread. */
        fun update(context: Context, which: IntArray? = null) {
            val app = MotifApp.get(context)
            CoroutineScope(Dispatchers.Main.immediate).launch { renderAll(app, which) }
        }

        private suspend fun renderAll(app: MotifApp, which: IntArray? = null) {
            val ids = which ?: ids(app)
            if (ids.isEmpty()) return
            val style = effectiveStyle(app)
            val p = app.remote.playback.value
            val art = p?.imageUri?.let { runCatching { app.remote.loadArt(it, Image.Dimension.SMALL) }.getOrNull() }
            val manager = AppWidgetManager.getInstance(app)
            for (id in ids) manager.updateAppWidget(id, build(app, style, p, art))
        }

        /** App settings when the switch is on (opacity still comes from the widget's own), else the widget's own. */
        suspend fun effectiveStyle(app: MotifApp): WidgetStyle {
            val own = app.prefs.widgetStyleNow()
            if (!app.prefs.widgetUsesAppNow()) return own
            val a = app.prefs.appearanceNow()
            return own.copy(
                textColor = a.textColor,
                backgroundColor = a.backgroundColor.let { if (it == 0xFF121212.toInt()) 0xFF1A1A1A.toInt() else it },
                textSize = a.textSize,
                glowColor = a.glowColor,
                glowIntensity = a.glowIntensity,
            )
        }

        private fun build(context: Context, s: WidgetStyle, p: SpotifyRemote.Playback?, art: Bitmap?): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_motif)
            val text = s.textColor
            val bgAlpha = (255 * s.backgroundOpacity.coerceIn(0, 100) / 100f).roundToInt()
            rv.setInt(R.id.widget_bg, "setColorFilter", s.backgroundColor or 0xFF000000.toInt())
            rv.setInt(R.id.widget_bg, "setImageAlpha", bgAlpha)
            val gi = s.glowIntensity.coerceIn(0, 100) / 100f
            rv.setInt(R.id.widget_glow, "setColorFilter", s.glowColor or 0xFF000000.toInt())
            rv.setInt(R.id.widget_glow, "setImageAlpha", if (gi == 0f) 0 else (255 * (0.3f + 0.6f * gi)).roundToInt())

            val (titleSp, subSp) = when (s.textSize) { "S" -> 14f to 12f; "L" -> 19f to 15f; else -> 16f to 13f }
            rv.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
            rv.setTextViewTextSize(R.id.widget_artist, TypedValue.COMPLEX_UNIT_SP, subSp)
            rv.setTextColor(R.id.widget_title, text)
            rv.setTextColor(R.id.widget_artist, text)

            if (p == null) {
                rv.setTextViewText(R.id.widget_title, "Motif")
                rv.setTextViewText(R.id.widget_artist, "Nothing playing")
            } else {
                rv.setTextViewText(R.id.widget_title, p.title)
                rv.setTextViewText(R.id.widget_artist, if (p.isEpisode) p.album.ifBlank { p.artist } else p.artist)
            }
            if (art != null) rv.setImageViewBitmap(R.id.widget_art, art) else rv.setImageViewResource(R.id.widget_art, android.R.color.transparent)

            rv.setInt(R.id.widget_prev, "setColorFilter", text)
            rv.setInt(R.id.widget_next, "setColorFilter", text)
            rv.setInt(R.id.widget_play_bg, "setColorFilter", text)
            rv.setImageViewResource(R.id.widget_play, if (p?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
            rv.setInt(R.id.widget_play, "setColorFilter", s.backgroundColor or 0xFF000000.toInt())
            // Mirrors the board's glyph treatment on a light text color: dark glyph on the light circle.
            if (luminance(text) < 0.4f) rv.setInt(R.id.widget_play, "setColorFilter", Color.WHITE)

            rv.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREV))
            rv.setOnClickPendingIntent(R.id.widget_play, broadcast(context, ACTION_PLAY))
            rv.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT))
            val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_NOW, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setOnClickPendingIntent(R.id.widget_text, PendingIntent.getActivity(context, 10, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val config = Intent(context, WidgetConfigActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setOnClickPendingIntent(R.id.widget_art, PendingIntent.getActivity(context, 11, config, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return rv
        }

        private fun broadcast(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(context, action.hashCode(), Intent(context, MotifWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun luminance(c: Int): Float =
            (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
    }
}
