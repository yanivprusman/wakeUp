package com.automatelinux.wakeUp.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.automatelinux.wakeUp.R
import com.automatelinux.wakeUp.ring.RingActivity

/**
 * Owns a ringing alarm: the sound, the buzz, the strobe, the wake lock, and the full-screen
 * intent that throws [RingActivity] over the lock screen.
 *
 * It is a foreground service so that nothing — Doze, the launcher being killed, the screen
 * staying off — can collect it mid-ring, and it has no stop button on its notification. The
 * only way out is [RingActivity], which asks for proof you are awake.
 */
class AlarmService : Service() {

    private var audio: AlarmAudio? = null
    private var buzzer: Buzzer? = null
    private var flasher: Flasher? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timeline: Runnable? = null
    private var startedAt = 0L
    private var stage: Stage? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getIntExtra(AlarmReceiver.EXTRA_ID, -1)
                val alarm = AlarmStore.get(this, id)
                if (alarm == null) { Log.e(TAG, "alarm $id fired but is not in the store"); stopSelf(); return START_NOT_STICKY }
                ring(alarm)
            }
            ACTION_STOP -> {
                // Grab it before stopEverything() clears it.
                val justRang = currentAlarmId
                stopEverything()
                renderNextBriefing(justRang)
                stopSelf()
            }
            ACTION_SNOOZE -> {
                val id = intent.getIntExtra(AlarmReceiver.EXTRA_ID, -1)
                snooze(id)
                stopEverything(); stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ring(alarm: Alarm) {
        currentAlarmId = alarm.id
        startForegroundNotification(alarm)

        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakeUp:ring")
            ?.apply { setReferenceCounted(false); acquire(RING_TIMEOUT_MS) }

        startedAt = System.currentTimeMillis()
        val first = Escalation.DEFAULT.first()
        stage = first
        audio = AlarmAudio(this).also { a ->
            a.start(alarm, first)
            if (alarm.voice) a.speak(VoiceCache.file(this, alarm.id))
        }
        runTimeline(alarm)
    }

    /**
     * Walks the [Escalation] ladder for as long as the alarm rings.
     *
     * One second is the resolution because that is also the anti-defeat interval: a stage that
     * arrives a second late costs nothing, and a check that runs a second late is a second
     * where a sleepy volume-down would have worked.
     */
    private fun runTimeline(alarm: Alarm) {
        timeline = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                val want = Escalation.stageAt(elapsed)
                if (want != stage) {
                    stage = want
                    audio?.applyStage(want)

                    if (want.vibrate && alarm.vibrate && buzzer == null) {
                        buzzer = Buzzer(this@AlarmService).also { it.start() }
                    }
                    if (want.flash && alarm.flash) {
                        flasher?.stop()
                        flasher = Flasher(this@AlarmService).also { it.start(want.flashPeriodMs) }
                    }
                    Log.i(TAG, "escalated to ${want.atSeconds}s — ${want.note}")
                }
                // Say it again periodically. A briefing you slept through the first time is
                // worth repeating; a briefing on a loop is just another tone.
                if (alarm.voice && elapsed > 0 && elapsed % VOICE_REPEAT_S == 0L) {
                    audio?.speak(VoiceCache.file(this@AlarmService, alarm.id))
                }
                handler.postDelayed(this, 1000L)
            }
        }.also { handler.postDelayed(it, 1000L) }
    }

    /**
     * The briefing that just played was written for THIS morning — it said this day and this
     * time out loud. The receiver has already re-armed a repeating alarm for next week, so the
     * file on disk is now about a morning that has been and gone.
     *
     * Render the next one now, with a whole day of network ahead of it rather than none at
     * 04:40. Without this a repeating alarm plays one identical recording every morning
     * forever — which is exactly the sound a brain learns to sleep through, and the reason
     * this app speaks at all.
     */
    private fun renderNextBriefing(id: Int?) {
        val alarm = id?.let { AlarmStore.get(this, it) } ?: return
        if (!alarm.enabled || !alarm.voice) return   // a spent one-shot needs nothing
        VoiceCache.clear(this, alarm.id)
        VoiceCache.refresh(applicationContext, alarm)
    }

    private fun snooze(id: Int) {
        val alarm = AlarmStore.get(this, id) ?: return
        val at = System.currentTimeMillis() + SNOOZE_MS
        val am = getSystemService(android.app.AlarmManager::class.java) ?: return
        val i = Intent(this, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_ID, alarm.id)
            data = android.net.Uri.parse("wakeup://alarm/${alarm.id}")
        }
        val pi = PendingIntent.getBroadcast(
            this, alarm.id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(at, pi), pi)
    }

    private fun stopEverything() {
        timeline?.let { handler.removeCallbacks(it) }; timeline = null
        stage = null
        audio?.stop(); audio = null
        buzzer?.stop(); buzzer = null
        flasher?.stop(); flasher = null
        runCatching { wakeLock?.release() }; wakeLock = null
        currentAlarmId = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() { stopEverything(); super.onDestroy() }

    private fun startForegroundNotification(alarm: Alarm) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The alarm going off. Full screen, and it bypasses Do Not Disturb."
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // We play the sound ourselves through the alarm stream with gain on top; a
                // channel sound here would be a second, quieter alarm fighting the real one.
                setSound(null, null)
                enableVibration(false)
            }
            nm?.createNotificationChannel(channel)
        }

        val full = PendingIntent.getActivity(
            this, alarm.id,
            Intent(this, RingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmReceiver.EXTRA_ID, alarm.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val label = alarm.label.ifBlank { "Alarm" }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(label)
            .setContentText("Tap to stop — you'll have to prove you're awake.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.automatelinux.wakeUp.RING"
        const val ACTION_STOP = "com.automatelinux.wakeUp.STOP"
        const val ACTION_SNOOZE = "com.automatelinux.wakeUp.SNOOZE"
        private const val CHANNEL = "wakeup.ring"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AlarmService"
        private const val RING_TIMEOUT_MS = 30 * 60 * 1000L
        private const val SNOOZE_MS = 5 * 60 * 1000L
        private const val VOICE_REPEAT_S = 45L


        /** Which alarm is ringing, for [RingActivity] launched by the full-screen intent. */
        @Volatile var currentAlarmId: Int? = null
            private set

        /**
         * Ring right now, for this alarm, exactly as it would at its real time — the full
         * ladder, the real challenge, no time limit.
         *
         * A test that is gentler than the thing it tests answers the wrong question. You need
         * to know whether THIS is loud enough and whether you can pass the challenge when it
         * matters, and a capped preview tells you neither.
         */
        fun test(context: Context, id: Int) {
            val i = Intent(context, AlarmService::class.java).apply {
                action = ACTION_START
                putExtra(AlarmReceiver.EXTRA_ID, id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).apply { action = ACTION_STOP })
        }

        fun snooze(context: Context, id: Int) {
            context.startService(Intent(context, AlarmService::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(AlarmReceiver.EXTRA_ID, id)
            })
        }
    }
}
