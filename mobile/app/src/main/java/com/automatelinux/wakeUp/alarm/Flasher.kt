package com.automatelinux.wakeUp.alarm

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The two channels that work when sound doesn't: light and touch.
 *
 * Light matters more than volume for actually ending sleep, and a strobe cannot be slept
 * through the way a steady tone can. `setTorchMode` needs no CAMERA permission.
 */
class Flasher(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var toggle: Runnable? = null
    private var on = false

    fun start(periodMs: Long = 450L) {
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        val id = runCatching { cm.cameraIdList.firstOrNull { camId ->
            cm.getCameraCharacteristics(camId)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } }.getOrNull() ?: run { Log.w(TAG, "no flash unit"); return }

        toggle = object : Runnable {
            override fun run() {
                on = !on
                runCatching { cm.setTorchMode(id, on) }
                    .onFailure { Log.w(TAG, "torch toggle failed", it) }
                handler.postDelayed(this, periodMs)
            }
        }.also { handler.post(it) }
    }

    fun stop() {
        toggle?.let { handler.removeCallbacks(it) }
        toggle = null
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        runCatching { cm.cameraIdList.forEach { cm.setTorchMode(it, false) } }
    }

    private companion object { const val TAG = "Flasher" }
}

/** Max-amplitude vibration, repeating, until told otherwise. */
class Buzzer(private val context: Context) {
    private var vibrator: Vibrator? = null

    fun start() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
        val pattern = longArrayOf(0, 600, 300, 600, 300, 600, 900)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0)
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, 0))
        }.onFailure { Log.w("Buzzer", "vibration failed", it) }
    }

    fun stop() { runCatching { vibrator?.cancel() }; vibrator = null }
}
