package com.automatelinux.wakeUp.alarm

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Getting a briefing onto the phone, owned by WorkManager instead of a bare thread.
 *
 * On 2026-11-23 the alarm was edited at 19:07. That threw the old briefing away (it named the
 * old time) and started a download on a plain thread, which died at 19:08 halfway through — a
 * 145 KB `.part` was left behind. A thread dies with the process and nothing retried it, so the
 * phone sat with no briefing until the 22:00 check found the gap, warned "not ready", and then
 * fetched it five seconds later.
 *
 * WorkManager survives the app being swiped away, the process being killed and a reboot; it
 * waits for a network, and it retries with backoff. It keeps retrying until the briefing is on
 * disk or the morning it was for has passed.
 */
class BriefingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val id = inputData.getInt(KEY_ID, -1)
        val alarm = AlarmStore.get(applicationContext, id)
        if (alarm == null || !alarm.enabled || !alarm.voice || VoiceCache.isCurrent(applicationContext, alarm)) {
            VoiceCache.settled(id)
            return Result.success()
        }
        if (VoiceCache.refreshBlocking(applicationContext, alarm)) {
            VoiceCache.settled(id)
            // A warning posted while this was still retrying is no longer true.
            Readiness.clearIfReady(applicationContext)
            return Result.success()
        }
        if (alarm.nextTrigger() <= System.currentTimeMillis()) {
            VoiceCache.settled(id)
            return Result.failure()
        }
        Log.i(TAG, "briefing for alarm $id not fetched (attempt ${runAttemptCount + 1}); retrying")
        return Result.retry()
    }

    companion object {
        private const val TAG = "BriefingWork"
        private const val KEY_ID = "alarmId"

        /**
         * [replace] = the briefing's content changed (new time, new label, re-armed after a
         * ring): anything already queued is for the old content. Otherwise an existing request
         * is kept, so reopening the app does not reset a retry that is already under way.
         */
        fun enqueue(context: Context, alarmId: Int, replace: Boolean) {
            val request = OneTimeWorkRequestBuilder<BriefingWorker>()
                .setInputData(workDataOf(KEY_ID to alarmId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "briefing-$alarmId",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

/**
 * The 22:00 check, run as work so it may take as long as a download takes (a receiver gets
 * seconds). It FETCHES FIRST and warns only about what is still wrong after that — on
 * 2026-11-23 it warned first and then fixed the very thing it had warned about, five seconds
 * later. A briefing that is already on the phone for the right morning is left alone: its
 * content is the label, the time and the day, so re-rendering it produces the same file.
 */
class BedtimeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        AlarmStore.all(ctx)
            .filter { it.enabled && it.voice && !VoiceCache.isCurrent(ctx, it) }
            .forEach { alarm ->
                if (!VoiceCache.refreshBlocking(ctx, alarm)) {
                    // Still missing: the warning below says so, and a retrying download keeps
                    // trying through the night and takes the warning down if it lands.
                    VoiceCache.refresh(ctx, alarm, replace = false)
                }
            }
        Readiness.notifyIfNotReady(ctx)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "bedtime-check",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BedtimeWorker>().build(),
            )
        }
    }
}
