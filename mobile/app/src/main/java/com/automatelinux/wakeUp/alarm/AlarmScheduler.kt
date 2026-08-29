package com.automatelinux.wakeUp.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Puts alarms into [AlarmManager].
 *
 * `setAlarmClock` and nothing else. It is the ONLY scheduling API Android promises will fire
 * on time in Doze — `setExact` is throttled, `setExactAndAllowWhileIdle` is rate-limited to
 * roughly once every nine minutes, and both were "close enough" right up until the morning
 * they weren't. It also puts the alarm icon in the status bar, which is a free correctness
 * check you can read from across the room before you go to sleep.
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    private fun fireIntent(context: Context, alarm: Alarm): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_ID, alarm.id)
            // The id must be part of the intent's identity, not just its extras: PendingIntent
            // equality ignores extras, so without this every alarm would overwrite the last.
            data = android.net.Uri.parse("wakeup://alarm/${alarm.id}")
        }
        return PendingIntent.getBroadcast(
            context, alarm.id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The intent the system's alarm icon opens — tapping it lands you in the app. */
    private fun showIntent(context: Context): PendingIntent {
        val i = Intent(context, com.automatelinux.wakeUp.MainActivity::class.java)
        return PendingIntent.getActivity(
            context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, alarm: Alarm) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (!alarm.enabled) { cancel(context, alarm); return }
        if (!canScheduleExact(context)) {
            // Loud on purpose: a silently downgraded alarm is the failure this app exists to
            // prevent. The UI surfaces the same condition as a banner.
            Log.e(TAG, "exact alarms not permitted — alarm ${alarm.id} NOT scheduled")
            return
        }
        val at = alarm.nextTrigger()
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(context)), fireIntent(context, alarm))
        Log.i(TAG, "alarm ${alarm.id} set for $at")
    }

    fun cancel(context: Context, alarm: Alarm) {
        context.getSystemService(AlarmManager::class.java)?.cancel(fireIntent(context, alarm))
    }

    /** After a boot, a time change, or any edit: make the system agree with the store. */
    fun rescheduleAll(context: Context) {
        AlarmStore.all(context).forEach { if (it.enabled) schedule(context, it) else cancel(context, it) }
    }

    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else true
}
