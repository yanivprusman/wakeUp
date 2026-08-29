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
import java.io.File

/**
 * The loud part, and the part that refuses to be turned down.
 *
 * Three ceilings on "louder", and the alarm that shipped with the phone lifts only the first:
 *
 *  1. **Stream** — `USAGE_ALARM` ignores silent mode and rings through Do Not Disturb. We take
 *     the stream to the volume the current [Stage] asks for and **hold it there**, see below.
 *  2. **Gain past the slider** — [LoudnessEnhancer] is a gain stage on the player's audio
 *     session, in millibels, on top of an already-maxed stream. This is the only way an app
 *     exceeds the volume control, and it is why this can be louder than the stock alarm.
 *  3. **Time** — the stage ladder climbs and never descends. See [Escalation].
 *
 * **Anti-defeat**: the volume is re-asserted on a timer. Pressing volume-down half-asleep is
 * the single most effective way to defeat an alarm, and it is pure muscle memory — it costs no
 * wakefulness at all. Here it does nothing: the level goes back up within the second. The
 * user's own setting is captured before the first change and restored when the alarm stops, so
 * holding the stream hostage never outlives the ring.
 *
 * A voice line, if one was cached for this alarm, plays on its own player over the top —
 * Claude saying something true about this specific morning. Novelty is what stops a brain
 * learning to sleep through a sound, and a briefing is different every day by construction.
 */
class AlarmAudio(private val context: Context) {

    private var tone: MediaPlayer? = null
    private var voice: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousVolume: Int? = null
    private var targetVolume: Int = 0

    private val handler = Handler(Looper.getMainLooper())
    private var volumeGuard: Runnable? = null

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    val streamMax: Int get() = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0

    fun start(alarm: Alarm, firstStage: Stage) {
        val am = audioManager ?: run { Log.e(TAG, "no AudioManager — cannot sound the alarm"); return }
        previousVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        requestFocus(am)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (uri == null) {
            Log.e(TAG, "device has no default alarm sound — ringing on voice and vibration only")
        } else {
            tone = MediaPlayer().apply {
                setAudioAttributes(attributes)
                isLooping = true
                setOnErrorListener { _, what, extra -> Log.e(TAG, "tone error $what/$extra"); true }
                runCatching { setDataSource(context, uri); prepare(); start() }
                    .onFailure { Log.e(TAG, "alarm tone failed to start", it) }
            }
            attachEnhancer()
        }

        applyStage(firstStage)
        startVolumeGuard()
    }

    /** Play the cached briefing over the tone. Called once per stage-0 cycle. */
    fun speak(file: File) {
        if (!file.exists()) { Log.w(TAG, "no cached voice line at ${file.name}"); return }
        runCatching { voice?.release() }
        voice = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setOnErrorListener { _, what, extra -> Log.e(TAG, "voice error $what/$extra"); true }
            runCatching { setDataSource(file.absolutePath); prepare(); start() }
                .onFailure { Log.e(TAG, "voice line failed to play", it) }
        }
    }

    fun applyStage(stage: Stage) {
        val am = audioManager ?: return
        targetVolume = (streamMax * stage.volumeFraction).toInt().coerceIn(1, streamMax)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0) }
            .onFailure { Log.e(TAG, "could not set alarm volume", it) }
        runCatching { enhancer?.setTargetGain(stage.gainMb) }
            .onFailure { Log.e(TAG, "could not set gain", it) }
        Log.i(TAG, "stage ${stage.atSeconds}s: vol $targetVolume/$streamMax gain ${stage.gainMb}mB (${stage.note})")
    }

    private fun startVolumeGuard() {
        volumeGuard = object : Runnable {
            override fun run() {
                val am = audioManager
                if (am != null && targetVolume > 0) {
                    val now = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    if (now < targetVolume) {
                        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0) }
                        Log.i(TAG, "volume was pulled to $now — put back to $targetVolume")
                    }
                }
                handler.postDelayed(this, GUARD_MS)
            }
        }.also { handler.postDelayed(it, GUARD_MS) }
    }

    private fun requestFocus(am: AudioManager) {
        // EXCLUSIVE: nothing else on this phone has anything to say right now.
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

    private fun attachEnhancer() {
        val sessionId = tone?.audioSessionId ?: return
        // Optional stage, never a silent one: if the device refuses to attach effects the alarm
        // still runs at a maxed stream, and the reason is in the log rather than swallowed.
        enhancer = runCatching { LoudnessEnhancer(sessionId).apply { setTargetGain(0); enabled = true } }
            .onFailure { Log.e(TAG, "LoudnessEnhancer unavailable — stream max is the ceiling", it) }
            .getOrNull()
    }

    fun stop() {
        volumeGuard?.let { handler.removeCallbacks(it) }
        volumeGuard = null
        targetVolume = 0
        runCatching { enhancer?.release() }; enhancer = null
        runCatching { voice?.stop() }; runCatching { voice?.release() }; voice = null
        runCatching { tone?.stop() }; runCatching { tone?.release() }; tone = null

        audioManager?.let { am ->
            previousVolume?.let { v -> runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") am.abandonAudioFocus(null)
            }
        }
        previousVolume = null
        focusRequest = null
    }

    private companion object {
        const val TAG = "AlarmAudio"
        const val GUARD_MS = 700L
    }
}
