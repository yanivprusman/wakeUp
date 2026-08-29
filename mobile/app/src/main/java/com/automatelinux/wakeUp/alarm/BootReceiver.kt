package com.automatelinux.wakeUp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager forgets everything across a reboot, and a clock change moves every wall-clock
 * alarm. Both are silent — you find out by oversleeping — so re-arm from the store on each.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> AlarmScheduler.rescheduleAll(context)
        }
    }
}
