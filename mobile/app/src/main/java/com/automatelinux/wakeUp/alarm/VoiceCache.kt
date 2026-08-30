package com.automatelinux.wakeUp.alarm

import android.content.Context
import android.util.Log
import com.automatelinux.wakeUp.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Claude's line for a given alarm, downloaded ahead of time and kept on the phone.
 *
 * **The download happens when the alarm is SET, never when it fires.** At 06:00 the phone
 * plays a file it already holds: no desktop, no Wi-Fi, no server, no round-trip on the one
 * path in this app that must never have one. A briefing that only works when the network does
 * is not an alarm, it is a nice idea about an alarm.
 *
 * A missing file is not silently tolerated: [state] reports it, the alarm list shows it, and
 * the bedtime check tells you the night before. The tone and the ladder still run — the voice
 * is a layer on top, not the mechanism — but you are never left thinking you have something
 * you do not.
 *
 * **A cached briefing belongs to ONE occurrence.** It names the day and the time it will ring,
 * so the moment the fire time moves — the alarm is re-timed, re-dayed, switched back on, or
 * has just gone off and re-armed for next week — the file on disk is about a morning that is
 * no longer coming. Every caller that moves the fire time re-renders; see `rearm` in
 * AlarmListScreen and the STOP path in [AlarmService].
 */
object VoiceCache {

    enum class State { CACHED, MISSING, FAILED }

    fun file(context: Context, alarmId: Int): File =
        File(context.filesDir, "wake-voice-$alarmId.wav")

    fun state(context: Context, alarmId: Int): State {
        val f = file(context, alarmId)
        return if (f.exists() && f.length() > 0) State.CACHED else State.MISSING
    }

    fun clear(context: Context, alarmId: Int) { file(context, alarmId).delete() }

    /**
     * Fetch and store the briefing for [alarm]. Blocking — call it off the main thread.
     * Returns true if a playable file is now on disk.
     */
    fun refreshBlocking(context: Context, alarm: Alarm): Boolean {
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        // `at` is the moment it will RING, never "now". The render happens at bedtime and the
        // playback happens the next morning, so a server reading its own clock announced the
        // evening you set the alarm to someone standing in the dark eight hours later.
        val url = buildString {
            append(base).append("/api/wake-audio")
            append("?label=").append(java.net.URLEncoder.encode(alarm.label, "UTF-8"))
            append("&at=").append(alarm.nextTrigger())
            append("&tz=").append(java.net.URLEncoder.encode(java.util.TimeZone.getDefault().id, "UTF-8"))
        }
        val dest = file(context, alarm.id)
        val tmp = File(dest.absolutePath + ".part")

        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 150_000   // Kokoro renders at ~0.13x realtime; a briefing takes seconds
                requestMethod = "GET"
            }
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            val code = conn.responseCode
            conn.disconnect()
            if (code != 200 || tmp.length() == 0L) error("HTTP $code, ${tmp.length()} bytes")
            // Swap only once the whole file is down, so a killed download can never leave a
            // truncated WAV that plays as a click and nothing else.
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            Log.i(TAG, "cached briefing for alarm ${alarm.id} (${dest.length()} bytes)")
            true
        }.onFailure {
            tmp.delete()
            Log.e(TAG, "could not cache a briefing for alarm ${alarm.id} from $url", it)
        }.getOrDefault(false)
    }

    /** Fire-and-forget refresh, for the UI thread. */
    fun refresh(context: Context, alarm: Alarm, onDone: (Boolean) -> Unit = {}) {
        Thread {
            val ok = refreshBlocking(context.applicationContext, alarm)
            onDone(ok)
        }.apply { isDaemon = true }.start()
    }

    private const val TAG = "VoiceCache"
}
