package com.automatelinux.wakeUp.ring

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.util.Log
import kotlin.math.sqrt

/** The phrase the SPEAK challenge wants to hear. Short, unambiguous, hard to mumble. */
const val WAKE_PHRASE = "I am awake"

/**
 * Listens for [WAKE_PHRASE], on the device, with no network.
 *
 * Speaking is the best proof of wakefulness there is — it needs breath control, word
 * selection and articulation, none of which survive being half asleep, and unlike a tap it
 * cannot be done by a hand that acts before its owner does.
 */
class SpeechChallenge(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun listen(onResult: (heard: String, correct: Boolean) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("This phone has no speech recogniser"); return
        }
        stop()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val heard = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    onResult(heard, matches(heard))
                }
                override fun onError(error: Int) {
                    // NO_MATCH and SPEECH_TIMEOUT are ordinary: you said nothing, or nothing
                    // it understood. Both mean "still asleep", so they are a failed attempt
                    // rather than a broken challenge.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) onResult("", false) else onError("Recogniser error $error")
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // Offline: at 06:00 there may be no network, and there is no reason to need one
                // to hear three words.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            runCatching { startListening(intent) }
                .onFailure { Log.e("SpeechChallenge", "startListening failed", it); onError("Could not start listening") }
        }
    }

    fun stop() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun matches(heard: String): Boolean {
        val h = heard.lowercase().filter { it.isLetter() || it.isWhitespace() }.trim()
        // "I'm awake" and "I am awake" are the same act of waking up.
        return h.contains("awake") && (h.contains("i am") || h.contains("im") || h.contains("i'm"))
    }
}

/**
 * Counts real shakes.
 *
 * The bar is deliberately physical: enough movement, enough times, that you have to sit up and
 * use an arm. A phone waggled by a hand on a pillow does not reach it.
 */
class ShakeChallenge(private val context: Context, private val needed: Int = 12) {

    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var count = 0
    private var lastShakeAt = 0L

    fun start(onProgress: (Int, Int) -> Unit, onComplete: () -> Unit) {
        manager = context.getSystemService(SensorManager::class.java)
        val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        count = 0
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                // 2.4g, and no faster than four a second — otherwise a single sharp jolt
                // registers as a dozen and the challenge costs nothing.
                if (g > 2.4f && now - lastShakeAt > 250) {
                    lastShakeAt = now
                    count++
                    onProgress(count, needed)
                    if (count >= needed) { stop(); onComplete() }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        manager?.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
    }
}
