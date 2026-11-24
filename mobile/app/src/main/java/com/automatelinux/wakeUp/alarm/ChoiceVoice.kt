package com.automatelinux.wakeUp.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

/**
 * Says out loud which button on the ring screen was pressed — "Stop" or "Snooze, five
 * minutes" — so a half-asleep tap is confirmed by ear, not by squinting at a screen that has
 * already gone away.
 *
 * Spoken after the alarm sound has stopped (it would be drowned otherwise), on the alarm
 * stream so silent mode and Do Not Disturb don't swallow it. On-device TTS, no network.
 * [onDone] runs once the line has been spoken, failed, or [TIMEOUT_MS] passed — whichever is
 * first — so the caller can hold its service alive just long enough.
 */
object ChoiceVoice {

    private const val TAG = "ChoiceVoice"
    private const val TIMEOUT_MS = 6000L

    fun say(context: Context, text: String, onDone: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        var finished = false
        fun finish() {
            main.post {
                if (finished) return@post
                finished = true
                runCatching { tts?.shutdown() }
                onDone()
            }
        }
        main.postDelayed({ finish() }, TIMEOUT_MS)

        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                Log.e(TAG, "text-to-speech unavailable (status $status) — cannot say \"$text\"")
                finish(); return@TextToSpeech
            }
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = finish()
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { Log.e(TAG, "failed to say \"$text\""); finish() }
            })
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "choice") != TextToSpeech.SUCCESS) {
                Log.e(TAG, "speak() refused \"$text\""); finish()
            }
        }
    }
}
