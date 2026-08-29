package com.automatelinux.wakeUp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The alarm went off. Hand straight to [AlarmService] and get out of the way — a receiver has
 * ~10 seconds and everything that matters (audio, screen, challenge) outlives that.
 *
 * Starting a foreground service from here is allowed even from the background: an exact alarm
 * firing is one of the documented exemptions.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return

        val svc = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtra(EXTRA_ID, id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc)
        else context.startService(svc)

        // A repeating alarm re-arms itself for next week; a one-shot switches itself off so the
        // list tells the truth in the morning.
        AlarmStore.get(context, id)?.let { alarm ->
            if (alarm.repeats) AlarmScheduler.schedule(context, alarm)
            else AlarmStore.upsert(context, alarm.copy(enabled = false))
        }
    }

    companion object {
        const val ACTION_FIRE = "com.automatelinux.wakeUp.FIRE"
        const val EXTRA_ID = "alarmId"
    }
}
