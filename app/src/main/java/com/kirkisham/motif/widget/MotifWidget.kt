package com.kirkisham.motif.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.kirkisham.motif.MainActivity
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.R
import com.kirkisham.motif.data.SpotifyRemote
import com.kirkisham.motif.data.WidgetConfig
import com.kirkisham.motif.ui.components.formatClock
import com.spotify.protocol.types.Image
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Home screen widget (spec §3.9, 0.12 layout): title, "Artist · Album", elapsed · progress · remaining,
 * previous / play-pause / next. Buttons broadcast back here; the receiver connects App Remote if needed,
 * sends the command, and redraws from the next PlayerState. Tapping the text opens Now playing; tapping
 * the art thumbnail (when shown) opens this instance's setup. Each instance has its own [WidgetConfig].
 */
class MotifWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        update(context, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val app = MotifApp.get(context)
        CoroutineScope(Dispatchers.Main.immediate).launch { runCatching { app.prefs.deleteWidgetConfigs(ids) } }
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
            // Keep the whole handler under the ~10 s a receiver gets after goAsync().
            val ok = withTimeoutOrNull(6_000) { remote.connection.first { it == SpotifyRemote.Connection.Connected } } != null
            if (!ok) { Log.w(TAG, "Widget: Spotify did not connect"); return }
            when (action) {
                ACTION_PREV -> remote.skipBack()
                ACTION_NEXT -> remote.skipForward()
                ACTION_PLAY -> remote.togglePlayPause()
            }
            // Let the new state arrive, then redraw once.
            withTimeoutOrNull(1_500) { remote.playback.first { it != null } }
            kotlinx.coroutines.delay(300)
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
        private const val PROGRESS_MAX = 1000

        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MotifWidget::class.java))

        fun hasWidgets(context: Context) = ids(context).isNotEmpty()

        /** Redraw every instance (or [which]) from the current playback state and each one's setup. Safe from any thread. */
        fun update(context: Context, which: IntArray? = null) {
            val app = MotifApp.get(context)
            CoroutineScope(Dispatchers.Main.immediate).launch { renderAll(app, which) }
        }

        /** Progress tick (0.12): only the bar and the two times, as a partial update. Called once a second while playing. */
        fun tickProgress(context: Context) {
            val app = MotifApp.get(context)
            val p = app.remote.playback.value ?: return
            val ids = ids(app)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(app)
            val rv = RemoteViews(app.packageName, R.layout.widget_motif)
            bindProgress(rv, p)
            for (id in ids) manager.partiallyUpdateAppWidget(id, rv)
        }

        private suspend fun renderAll(app: MotifApp, which: IntArray? = null) {
            val ids = which ?: ids(app)
            if (ids.isEmpty()) return
            val p = app.remote.playback.value
            val textColor = app.prefs.appearanceNow().textColor
            val manager = AppWidgetManager.getInstance(app)
            var art: Bitmap? = null
            var artLoaded = false
            for (id in ids) {
                val cfg = app.prefs.widgetConfigNow(id)
                if (cfg.showArt && !artLoaded) {
                    artLoaded = true
                    art = p?.imageUri?.let { runCatching { app.remote.loadArt(it, Image.Dimension.SMALL) }.getOrNull() }
                }
                // Portrait size the launcher gave this instance (0 when unknown, which shows everything).
                val opts = manager.getAppWidgetOptions(id)
                val w = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
                val h = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
                manager.updateAppWidget(id, build(app, id, cfg, textColor, p, art, w, h))
            }
        }

        private fun bindProgress(rv: RemoteViews, p: SpotifyRemote.Playback?) {
            val pos = p?.positionNow() ?: 0L
            val dur = p?.durationMs ?: 0L
            rv.setProgressBar(R.id.widget_progress, PROGRESS_MAX, if (dur > 0) (pos * PROGRESS_MAX / dur).toInt().coerceIn(0, PROGRESS_MAX) else 0, false)
            rv.setTextViewText(R.id.widget_elapsed, formatClock(pos))
            rv.setTextViewText(R.id.widget_remaining, "-" + formatClock((dur - pos).coerceAtLeast(0)))
        }

        private fun build(
            context: Context, id: Int, cfg: WidgetConfig, textColor: Int,
            p: SpotifyRemote.Playback?, art: Bitmap?, widthDp: Int = 0, heightDp: Int = 0,
        ): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_motif)
            // Small cells (Samsung): below about 96 dp of height the second line goes, below 72 the progress
            // row too; below about 150 dp of width only the play button fits.
            val short = heightDp in 1 until COMPACT_HEIGHT_DP
            val tiny = heightDp in 1 until TINY_HEIGHT_DP
            val narrow = widthDp in 1 until COMPACT_WIDTH_DP
            rv.setViewVisibility(R.id.widget_artist, if (short) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.widget_progress_row, if (tiny) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.widget_prev, if (narrow) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.widget_next, if (narrow) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.widget_art, if (cfg.showArt && !short) View.VISIBLE else View.GONE)
            val timesVis = if (cfg.showTimes) View.VISIBLE else View.GONE
            rv.setViewVisibility(R.id.widget_elapsed, timesVis)
            rv.setViewVisibility(R.id.widget_remaining, timesVis)

            // Card: #121212 at the configured transparency.
            rv.setInt(R.id.widget_bg, "setImageAlpha", (255 * (100 - cfg.transparency.coerceIn(0, 100)) / 100f).roundToInt())

            val (titleSp, subSp, timeSp) = when (cfg.textSize) { "S" -> Triple(13f, 11f, 10f); "L" -> Triple(17f, 13f, 12f); else -> Triple(15f, 12f, 11f) }
            rv.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, titleSp)
            rv.setTextViewTextSize(R.id.widget_artist, TypedValue.COMPLEX_UNIT_SP, subSp)
            rv.setTextViewTextSize(R.id.widget_elapsed, TypedValue.COMPLEX_UNIT_SP, timeSp)
            rv.setTextViewTextSize(R.id.widget_remaining, TypedValue.COMPLEX_UNIT_SP, timeSp)
            for (v in intArrayOf(R.id.widget_title, R.id.widget_artist, R.id.widget_elapsed, R.id.widget_remaining)) rv.setTextColor(v, textColor)

            if (p == null) {
                rv.setTextViewText(R.id.widget_title, "Motif")
                rv.setTextViewText(R.id.widget_artist, "Nothing playing")
            } else {
                rv.setTextViewText(R.id.widget_title, p.title)
                val second = when {
                    p.isEpisode -> p.album.ifBlank { p.artist }
                    cfg.showAlbum && p.album.isNotBlank() -> p.artist + " · " + p.album
                    else -> p.artist
                }
                rv.setTextViewText(R.id.widget_artist, second)
            }
            bindProgress(rv, p)
            if (cfg.showArt) {
                if (art != null) rv.setImageViewBitmap(R.id.widget_art, art) else rv.setImageViewResource(R.id.widget_art, android.R.color.transparent)
            }

            rv.setInt(R.id.widget_prev, "setColorFilter", textColor)
            rv.setInt(R.id.widget_next, "setColorFilter", textColor)
            rv.setInt(R.id.widget_play, "setColorFilter", textColor)
            rv.setImageViewResource(R.id.widget_play, if (p?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)

            rv.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREV))
            rv.setOnClickPendingIntent(R.id.widget_play, broadcast(context, ACTION_PLAY))
            rv.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT))
            val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_NOW, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setOnClickPendingIntent(R.id.widget_text, PendingIntent.getActivity(context, 10, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val config = Intent(context, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            rv.setOnClickPendingIntent(R.id.widget_art, PendingIntent.getActivity(context, 1000 + id, config, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            return rv
        }

        private const val COMPACT_HEIGHT_DP = 96
        private const val TINY_HEIGHT_DP = 72
        private const val COMPACT_WIDTH_DP = 150

        private fun broadcast(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(context, action.hashCode(), Intent(context, MotifWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
