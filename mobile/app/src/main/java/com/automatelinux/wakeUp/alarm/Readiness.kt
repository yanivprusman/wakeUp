package com.automatelinux.wakeUp.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.automatelinux.wakeUp.MainActivity
import com.automatelinux.wakeUp.R
import java.util.Calendar

/** One thing that would stop tomorrow's alarm working. */
data class Problem(val title: String, val detail: String, val fix: Fix?)

enum class Fix { EXACT_ALARMS, NOTIFICATIONS, BATTERY, DND_ACCESS, CACHE_VOICE }

/**
 * Everything that has to still be true tonight for the alarm to go off tomorrow.
 *
 * An alarm has exactly one failure mode that matters: not going off. Every cause of it is
 * silent — a permission Android revoked while the app sat unused, a battery optimiser that
 * decided this app was idle, a briefing that never downloaded — and every one of them is
 * discovered at the moment it is too late to do anything about.
 *
 * So the app checks them all, shows them at the top of the list, and — this is the part that
 * matters — asks at bedtime, while there is still a night in which to fix it.
 */
object Readiness {

    fun problems(context: Context): List<Problem> = buildList {
        if (!AlarmScheduler.canScheduleExact(context)) {
            add(Problem(
                "Alarms cannot fire",
                "Android is not letting this app schedule exact alarms. Nothing will go off.",
                Fix.EXACT_ALARMS,
            ))
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            add(Problem(
                "Notifications are off",
                "The ringing alarm is a foreground service; without a notification it has nothing to hang the full-screen intent on.",
                Fix.NOTIFICATIONS,
            ))
        }
        val power = context.getSystemService(PowerManager::class.java)
        if (power != null && !power.isIgnoringBatteryOptimizations(context.packageName)) {
            add(Problem(
                "Battery optimisation is on",
                "Android may decide this app is idle and hold its alarm. Exempt it and it will not.",
                Fix.BATTERY,
            ))
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm?.isNotificationPolicyAccessGranted == false) {
            add(Problem(
                "No Do Not Disturb access",
                "Without it the alarm channel cannot promise to bypass Do Not Disturb.",
                Fix.DND_ACCESS,
            ))
        }
        AlarmStore.all(context).filter { it.enabled && it.voice }.forEach { alarm ->
            if (VoiceCache.state(context, alarm.id) != VoiceCache.State.CACHED) {
                add(Problem(
                    "No briefing for %02d:%02d".format(alarm.hour, alarm.minute),
                    "Claude's line for this alarm is not on the phone. It will still ring — with the tone only.",
                    Fix.CACHE_VOICE,
                ))
            }
        }
    }

    /** Ask, every night, while there is still a night left to fix it. */
    fun scheduleBedtimeCheck(context: Context, hour: Int = 22, minute: Int = 0) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val pi = PendingIntent.getBroadcast(
            context, BEDTIME_REQUEST,
            Intent(context, BedtimeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact on purpose: this is a reminder, not the alarm. It costs no battery budget
        // and nothing depends on it landing at 22:00 exactly.
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, next, AlarmManager.INTERVAL_DAY, pi)
    }

    fun notifyIfNotReady(context: Context) {
        val problems = problems(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (problems.isEmpty()) { nm.cancel(BEDTIME_NOTIFICATION); return }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Bedtime check", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Told before you sleep when tomorrow's alarm could not go off."
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = problems.joinToString("\n") { "• ${it.title}" }
        nm.notify(
            BEDTIME_NOTIFICATION,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("Tomorrow's alarm is not safe")
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentText(problems.first().title)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private const val CHANNEL = "wakeup.bedtime"
    private const val BEDTIME_NOTIFICATION = 2001
    private const val BEDTIME_REQUEST = 9001
}

/** 22:00. Anything wrong with tomorrow's alarm gets said now, not at 06:00. */
class BedtimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Readiness.notifyIfNotReady(context)
        // Re-cache tonight so a stale or missing briefing repairs itself while you sleep.
        AlarmStore.all(context).filter { it.enabled && it.voice }.forEach { alarm ->
            Thread { VoiceCache.refreshBlocking(context.applicationContext, alarm) }
                .apply { isDaemon = true }.start()
        }
    }
}
