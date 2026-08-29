package com.automatelinux.wakeUp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.automatelinux.wakeUp.alarm.*
import java.util.Calendar

private val DAY_NAMES = listOf(
    Calendar.SUNDAY to "Su", Calendar.MONDAY to "Mo", Calendar.TUESDAY to "Tu",
    Calendar.WEDNESDAY to "We", Calendar.THURSDAY to "Th", Calendar.FRIDAY to "Fr",
    Calendar.SATURDAY to "Sa",
)

/** "in 6h 20m" — the only number that answers the question you actually have at bedtime. */
private fun countdown(millisFromNow: Long): String {
    val minutes = (millisFromNow / 60_000).coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h >= 24 -> "in ${h / 24}d ${h % 24}h"
        h > 0 -> "in ${h}h ${m}m"
        else -> "in ${m}m"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen() {
    val context = LocalContext.current
    var alarms by remember { mutableStateOf(AlarmStore.all(context)) }
    var problems by remember { mutableStateOf(Readiness.problems(context)) }
    var caching by remember { mutableStateOf(setOf<Int>()) }
    var picking by remember { mutableStateOf(false) }

    fun refresh() {
        alarms = AlarmStore.all(context)
        problems = Readiness.problems(context)
    }

    fun cacheVoice(alarm: Alarm) {
        caching = caching + alarm.id
        VoiceCache.refresh(context, alarm) { caching = caching - alarm.id; refresh() }
    }

    val next = alarms.filter { it.enabled }.minByOrNull { it.nextTrigger() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { picking = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Filled.Add, contentDescription = "Add alarm") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { Header(next) }

            if (problems.isNotEmpty()) {
                item {
                    ProblemsCard(problems) { fix ->
                        if (fix == Fix.CACHE_VOICE) alarms.filter { it.enabled && it.voice }.forEach(::cacheVoice)
                        else openFix(context, fix)
                    }
                }
            }

            if (alarms.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("Nothing set", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            items(alarms, key = { it.id }) { alarm ->
                AlarmCard(
                    alarm = alarm,
                    voiceState = VoiceCache.state(context, alarm.id),
                    caching = alarm.id in caching,
                    onToggle = { on ->
                        val updated = alarm.copy(enabled = on)
                        AlarmStore.upsert(context, updated)
                        if (on) AlarmScheduler.schedule(context, updated) else AlarmScheduler.cancel(context, updated)
                        refresh()
                    },
                    onDelete = {
                        AlarmScheduler.cancel(context, alarm)
                        VoiceCache.clear(context, alarm.id)
                        AlarmStore.delete(context, alarm.id)
                        refresh()
                    },
                    onToggleDay = { day ->
                        val updated = alarm.copy(days = if (day in alarm.days) alarm.days - day else alarm.days + day)
                        AlarmStore.upsert(context, updated)
                        if (updated.enabled) AlarmScheduler.schedule(context, updated)
                        refresh()
                    },
                    onChallenge = { c ->
                        AlarmStore.upsert(context, alarm.copy(challenge = c)); refresh()
                    },
                    onRecache = { cacheVoice(alarm) },
                    onTest = { AlarmService.test(context, alarm.id) },
                )
            }
        }
    }

    if (picking) {
        val now = Calendar.getInstance()
        WakeTimeDialog(
            initialHour = now.get(Calendar.HOUR_OF_DAY),
            initialMinute = now.get(Calendar.MINUTE),
            onDismiss = { picking = false },
            onConfirm = { h, m ->
                picking = false
                val alarm = Alarm(id = AlarmStore.nextId(context), hour = h, minute = m)
                AlarmStore.upsert(context, alarm)
                AlarmScheduler.schedule(context, alarm)
                cacheVoice(alarm)   // get Claude's line onto the phone now, not at 04:40
                refresh()
            },
        )
    }
}

@Composable
private fun Header(next: Alarm?) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 20.dp)) {
        Text(
            "wakeUp",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(10.dp))
        if (next == null) {
            Text("No alarm armed", fontSize = 30.sp, fontWeight = FontWeight.Light)
        } else {
            // The countdown is the headline, not the time: at bedtime the question is never
            // "when is it set for", it is "how long have I got".
            Text(
                countdown(next.nextTrigger() - System.currentTimeMillis()),
                fontSize = 40.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "%02d:%02d".format(next.hour, next.minute) + (next.label.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProblemsCard(problems: List<Problem>, onFix: (Fix) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Tomorrow is not safe",
                fontWeight = FontWeight.Bold, fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            problems.forEach { p ->
                Spacer(Modifier.height(14.dp))
                Text(p.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(p.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                p.fix?.let { fix ->
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onFix(fix) }) { Text("Fix this") }
                }
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    voiceState: VoiceCache.State,
    caching: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onToggleDay: (Int) -> Unit,
    onChallenge: (Challenge) -> Unit,
    onRecache: () -> Unit,
    onTest: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
    ) {
        // A disabled alarm is still on the list, but it must never read as armed at a glance.
        Column(Modifier.padding(20.dp).alpha(if (alarm.enabled) 1f else 0.45f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%02d:%02d".format(alarm.hour, alarm.minute),
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Light,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }

            Text(
                if (alarm.repeats) "Every week" else "Once",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAY_NAMES.forEach { (day, name) ->
                    DayToggle(name, day in alarm.days, Modifier.weight(1f)) { onToggleDay(day) }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("To stop it", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Challenge.entries.forEach { c ->
                    ChallengePill(
                        label = c.name.lowercase().replaceFirstChar { it.uppercase() },
                        selected = alarm.challenge == c,
                        modifier = Modifier.weight(1f),
                    ) { onChallenge(c) }
                }
            }
            Text(
                when (alarm.challenge) {
                    Challenge.NONE -> "One tap ends it — the move a sleeping hand makes best"
                    Challenge.MATH -> "${alarm.requiredCorrect} sums, in a row"
                    Challenge.SPEAK -> "Say it out loud, ${alarm.requiredCorrect}×"
                    Challenge.SHAKE -> "Shake it hard, ${alarm.requiredCorrect}×"
                },
                fontSize = 12.sp,
                color = if (alarm.challenge == Challenge.NONE) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        caching -> "Fetching Claude's line…"
                        voiceState == VoiceCache.State.CACHED -> "Briefing on this phone"
                        else -> "No briefing — tone only"
                    },
                    fontSize = 12.sp,
                    color = if (voiceState == VoiceCache.State.CACHED || caching) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRecache, enabled = !caching) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Re-fetch the briefing")
                }
                IconButton(onClick = onTest) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Ring it now, for real")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

/**
 * One day of the week. A plain weighted box rather than a [FilterChip] because a chip sizes
 * itself to its content and there are seven of them: on any phone narrower than the row wants,
 * the last chip is squeezed until its label disappears — which is exactly how Saturday went
 * missing. A weight is a promise the layout can always keep.
 */
@Composable
private fun DayToggle(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 13.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Same equal-weight rule as the days: four of these must always fit, on any phone. */
@Composable
private fun ChallengePill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 13.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The app's own time picker.
 *
 * The platform `TimePickerDialog` renders in the OS's holo-era green regardless of the app's
 * theme — a teal clock face dropped into a night-indigo app, which is what it looked like.
 * Material 3's [TimePicker] inherits the scheme above, so it belongs to this app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeTimeDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Wake me at") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun openFix(context: Context, fix: Fix) {
    val intent = when (fix) {
        Fix.EXACT_ALARMS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            else null
        Fix.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        // The settings LIST, not the per-app request dialog: the request needs a permission
        // that flags the app in review, and the list is one tap further for the same result.
        Fix.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        Fix.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        Fix.CACHE_VOICE -> null
    }
    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
}
