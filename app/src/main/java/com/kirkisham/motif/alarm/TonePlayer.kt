package com.kirkisham.motif.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Fallback alarm sound when Spotify does not answer: a soft two-note pattern generated on the
 * fly and written to an AudioTrack on the alarm stream. No bundled audio file needed.
 */
class TonePlayer {
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    /** Why the last [start] failed, for the alarm log. */
    @Volatile var lastError: String? = null
        private set

    /** Starts the tone; false when the AudioTrack could not be created or initialised (logged by the caller). */
    fun start(): Boolean {
        if (running) return true
        lastError = null
        running = true
        val rate = 44_100
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack(attrs, format, maxOf(minBuf, rate / 2), AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"; running = false; return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            lastError = "state ${t.state}"; runCatching { t.release() }; running = false; return false
        }
        track = t
        try { t.play() } catch (e: IllegalStateException) {
            lastError = "play: ${e.message}"; runCatching { t.release() }; track = null; running = false; return false
        }
        worker = thread(name = "motif-tone", isDaemon = true) {
            // Pattern: 660 Hz 300 ms, 880 Hz 300 ms, silence 900 ms. Short attack/release to avoid clicks.
            val pattern = listOf(660.0 to 0.3, 880.0 to 0.3, 0.0 to 0.9)
            val chunk = ShortArray(rate / 10)
            var phase = 0.0
            outer@ while (running) {
                for ((freq, secs) in pattern) {
                    val total = (secs * rate).toInt()
                    var written = 0
                    while (written < total && running) {
                        val n = minOf(chunk.size, total - written)
                        for (i in 0 until n) {
                            val pos = written + i
                            val env = minOf(1.0, pos / (0.02 * rate), (total - pos) / (0.02 * rate)).coerceIn(0.0, 1.0)
                            val v = if (freq == 0.0) 0.0 else sin(phase) * 0.6 * env
                            phase += 2 * PI * freq / rate
                            if (phase > 2 * PI) phase -= 2 * PI
                            chunk[i] = (v * Short.MAX_VALUE).toInt().toShort()
                        }
                        t.write(chunk, 0, n)
                        written += n
                    }
                    if (!running) break@outer
                }
            }
        }
        return true
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
