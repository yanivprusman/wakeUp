package com.automatelinux.wakeUp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager forgets everything across a reboot, a clock change moves every wall-clock alarm,
 * and **an app update cancels every alarm the app had pending**. All three are silent — you
 * find out by oversleeping — so re-arm from the store on each.
 *
 * The update case is the one that nearly bit: alarms were only being restored by
 * MainActivity.onCreate, so installing a new build and not opening it left the phone with
 * nothing scheduled and an app that still listed the alarm as on. Caught by checking dumpsys
 * after an install rather than trusting the list screen.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> AlarmScheduler.rescheduleAll(context)
        }
    }
}
