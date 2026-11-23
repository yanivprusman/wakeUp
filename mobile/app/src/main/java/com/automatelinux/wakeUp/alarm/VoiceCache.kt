package com.automatelinux.wakeUp.alarm

import android.content.Context
import android.util.Log
import com.automatelinux.wakeUp.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 *
 * **This object is the one source of truth for what is on disk and what is on its way.**
 * Screens observe [fetching] and [changes]; they never keep their own copy. On 2026-11-15
 * the list kept a private "fetching" set fed by its own callbacks, while the ring service
 * cleared and re-fetched the briefing behind its back after a test ring: the list resumed,
 * saw no file and no fetch it knew of, and showed "No briefing" in red for a briefing that
 * landed seconds later and stayed red until the app was reopened.
 */
object VoiceCache {

    enum class State { CACHED, MISSING, FAILED }

    fun file(context: Context, alarmId: Int): File =
        File(context.filesDir, "wake-voice-$alarmId.wav")

    fun state(context: Context, alarmId: Int): State {
        val f = file(context, alarmId)
        return if (f.exists() && f.length() > 0) State.CACHED else State.MISSING
    }

    /** The moment ([Alarm.nextTrigger]) the file on disk was rendered for, or null. */
    fun renderedFor(context: Context, alarmId: Int): Long? =
        if (state(context, alarmId) != State.CACHED) null
        else runCatching { atFile(context, alarmId).readText().trim().toLong() }.getOrNull()

    /** Ids whose briefing is being fetched right now, by anyone — the screen or the service. */
    val fetching: StateFlow<Set<Int>> get() = _fetching.asStateFlow()

    /** Bumped whenever a briefing file appears or disappears. Observe it; re-read on change. */
    val changes: StateFlow<Long> get() = _changes.asStateFlow()

    fun clear(context: Context, alarmId: Int) {
        synchronized(lock) {
            // A fetch already on its way is now for something that was deliberately thrown
            // away (a renamed or deleted alarm), so it must not land after this.
            generation[alarmId] = (generation[alarmId] ?: 0) + 1
            file(context, alarmId).delete()
            atFile(context, alarmId).delete()
        }
        _changes.update { it + 1 }
    }

    /**
     * Fetch and store the briefing for [alarm]. Blocking — call it off the main thread.
     * Returns true if a playable file is now on disk.
     */
    fun refreshBlocking(context: Context, alarm: Alarm): Boolean =
        fetch(context, alarm, begin(alarm.id))

    private fun fetch(context: Context, alarm: Alarm, ticket: Long): Boolean {
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        val at = alarm.nextTrigger()
        // `at` is the moment it will RING, never "now". The render happens at bedtime and the
        // playback happens the next morning, so a server reading its own clock announced the
        // evening you set the alarm to someone standing in the dark eight hours later.
        val url = buildString {
            append(base).append("/api/wake-audio")
            append("?label=").append(java.net.URLEncoder.encode(alarm.label, "UTF-8"))
            append("&at=").append(at)
            append("&tz=").append(java.net.URLEncoder.encode(java.util.TimeZone.getDefault().id, "UTF-8"))
        }
        val dest = file(context, alarm.id)
        // Its own temp file: two fetches for one alarm (a rename while the first is still
        // rendering) used to stream into one shared ".part" and rename it out from each other.
        var tmp: File? = null

        return runCatching {
            val part = File.createTempFile("wake-voice-${alarm.id}-", ".part", context.filesDir)
            tmp = part
            // An idle dev server is put to sleep, and the daemon answers on its port instead:
            // 503 with a Retry-After, while it starts the real one. That is "ask again in a
            // moment" said in so many words. Reading it as a failure is how a briefing went
            // missing on 2026-10-04 for a server that was up 219ms later — so a 503 that
            // names its own wait is waited out, and only that; any other answer is final.
            var waited = 0
            while (true) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 150_000   // Kokoro renders at ~0.13x realtime; a briefing takes seconds
                    requestMethod = "GET"
                }
                try {
                    val code = conn.responseCode
                    val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                    if (code == 503 && retryAfter != null && waited < STARTING_WAITS) {
                        waited++
                        Log.i(TAG, "server is starting; asking again in ${retryAfter}s ($waited/$STARTING_WAITS)")
                        Thread.sleep(retryAfter.coerceIn(1, 10) * 1000)
                        continue
                    }
                    if (code != 200) error("HTTP $code")
                    conn.inputStream.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
                    if (part.length() == 0L) error("HTTP 200, 0 bytes")
                    break
                } finally {
                    conn.disconnect()
                }
            }
            // Swap only once the whole file is down, so a killed download can never leave a
            // truncated WAV that plays as a click and nothing else. Only the newest request
            // for this alarm may land: an older one finishing late carries an old name.
            synchronized(lock) {
                if (generation[alarm.id] != ticket) {
                    part.delete()
                    Log.i(TAG, "dropped a superseded briefing for alarm ${alarm.id}")
                    return@runCatching false
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) error("could not move the briefing into place")
                atFile(context, alarm.id).writeText(at.toString())
            }
            Log.i(TAG, "cached briefing for alarm ${alarm.id} (${dest.length()} bytes)")
            true
        }.onFailure {
            tmp?.delete()
            Log.e(TAG, "could not cache a briefing for alarm ${alarm.id} from $url", it)
        }.getOrDefault(false).also { end(alarm.id) }
    }

    /**
     * Get [alarm]'s briefing onto the phone, retrying until it lands — see [BriefingWorker].
     * [replace]: the content changed, so a request already queued is for the old content.
     */
    fun refresh(context: Context, alarm: Alarm, replace: Boolean) {
        // Marked as on its way HERE, on the caller's thread, before the work is queued — so a
        // screen that just cleared the file never draws a frame of "no briefing" in between.
        synchronized(lock) {
            queued.add(alarm.id)
            publishFetching()
        }
        BriefingWorker.enqueue(context.applicationContext, alarm.id, replace)
    }

    /** The queued download for [alarmId] is finished, one way or the other. */
    fun settled(alarmId: Int) {
        synchronized(lock) {
            queued.remove(alarmId)
            publishFetching()
        }
        _changes.update { it + 1 }
    }

    /** The briefing on disk is for the morning [alarm] will next ring. */
    fun isCurrent(context: Context, alarm: Alarm): Boolean =
        renderedFor(context, alarm.id) == alarm.nextTrigger()

    private fun atFile(context: Context, alarmId: Int) = File(context.filesDir, "wake-voice-$alarmId.at")

    private val lock = Any()
    private val generation = HashMap<Int, Long>()
    private val inFlight = HashMap<Int, Int>()
    /** Ids with a [BriefingWorker] queued or retrying — "on its way" even between attempts. */
    private val queued = HashSet<Int>()
    private val _fetching = MutableStateFlow<Set<Int>>(emptySet())
    private val _changes = MutableStateFlow(0L)

    private fun begin(alarmId: Int): Long = synchronized(lock) {
        inFlight[alarmId] = (inFlight[alarmId] ?: 0) + 1
        publishFetching()
        ((generation[alarmId] ?: 0) + 1).also { generation[alarmId] = it }
    }

    private fun end(alarmId: Int) {
        synchronized(lock) {
            val left = (inFlight[alarmId] ?: 1) - 1
            if (left <= 0) inFlight.remove(alarmId) else inFlight[alarmId] = left
            publishFetching()
        }
        _changes.update { it + 1 }
    }

    /** Call holding [lock]. */
    private fun publishFetching() {
        _fetching.value = inFlight.keys + queued
    }

    private const val TAG = "VoiceCache"
    private const val STARTING_WAITS = 20   // about a minute at the daemon's Retry-After: 3
}
