package com.automatelinux.wakeUp.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The loud part.
 *
 * Three stages, because "louder" has three separate ceilings and the stock alarm only lifts
 * the first:
 *
 *  1. **Stream** — `USAGE_ALARM`. The alarm stream ignores silent mode and rings through Do
 *     Not Disturb. We also force its volume to maximum for the duration and put it back
 *     afterwards, so a slider you left at 7/15 last night cannot decide your morning.
 *  2. **Gain past the slider** — [LoudnessEnhancer] is an automatic-gain stage attached to the
 *     player's audio session, set in millibels ON TOP of a maxed stream. This is the only way
 *     an app gets louder than the volume control allows, and it is why this app can beat the
 *     one that shipped with the phone.
 *  3. **Ramp** — the gain climbs from nothing to [Alarm.maxGainMb] over [Alarm.rampSeconds]
 *     and never comes back down. Starting at full blast teaches you to lunge for the phone
 *     before you are conscious, which is the habit that makes stock alarms dismissable.
 *
 * The enhancer is treated as optional: some devices refuse to attach effects to a session.
 * If it fails the alarm still runs at a maxed alarm stream — the failure is logged at error,
 * never swallowed, because it is the difference between loud and merely audible.
 */
class AlarmAudio(private val context: Context) {

    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousVolume: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rampStep: Runnable? = null

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(alarm: Alarm) {
        val am = audioManager ?: run { Log.e(TAG, "no AudioManager — cannot sound the alarm"); return }

        // Stage 1: own the alarm stream, at the top of its range.
        previousVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0) }
            .onFailure { Log.e(TAG, "could not raise alarm volume", it) }

        requestFocus(am)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (uri == null) { Log.e(TAG, "device has no default alarm sound"); return }

        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            isLooping = true
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra"); true
            }
            runCatching {
                setDataSource(context, uri)
                prepare()
                start()
            }.onFailure { Log.e(TAG, "alarm sound failed to start", it) }
        }

        // Stage 2 + 3: gain beyond the slider, climbing.
        attachEnhancer(alarm)
    }

    private fun requestFocus(am: AudioManager) {
        // EXCLUSIVE, not TRANSIENT: nothing else on this phone has anything to say right now.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .build()
                .also { am.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
    }

    private fun attachEnhancer(alarm: Alarm) {
        val sessionId = player?.audioSessionId ?: return
        enhancer = runCatching {
            LoudnessEnhancer(sessionId).apply { setTargetGain(0); enabled = true }
        }.onFailure { Log.e(TAG, "LoudnessEnhancer unavailable — alarm is at stream max only", it) }
            .getOrNull()

        val steps = 30
        val interval = (alarm.rampSeconds * 1000L / steps).coerceAtLeast(200L)
        var step = 0
        rampStep = object : Runnable {
            override fun run() {
                step++
                val gain = (alarm.maxGainMb.toLong() * step / steps).toInt()
                runCatching { enhancer?.setTargetGain(gain) }
                if (step < steps) handler.postDelayed(this, interval)
            }
        }.also { handler.postDelayed(it, interval) }
    }

    fun stop() {
        rampStep?.let { handler.removeCallbacks(it) }
        rampStep = null
        runCatching { enhancer?.release() }
        enhancer = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null

        audioManager?.let { am ->
            previousVolume?.let { v ->
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") am.abandonAudioFocus(null)
            }
        }
        previousVolume = null
        focusRequest = null
    }

    private companion object { const val TAG = "AlarmAudio" }
}
