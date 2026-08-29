package com.automatelinux.wakeUp.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen() {
    val context = LocalContext.current
    var alarms by remember { mutableStateOf(AlarmStore.all(context)) }
    var problems by remember { mutableStateOf(Readiness.problems(context)) }
    var caching by remember { mutableStateOf(setOf<Int>()) }

    fun refresh() {
        alarms = AlarmStore.all(context)
        problems = Readiness.problems(context)
    }

    fun cacheVoice(alarm: Alarm) {
        caching = caching + alarm.id
        VoiceCache.refresh(context, alarm) {
            caching = caching - alarm.id
            refresh()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("wakeUp") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                pickTime(context) { h, m ->
                    val alarm = Alarm(id = AlarmStore.nextId(context), hour = h, minute = m)
                    AlarmStore.upsert(context, alarm)
                    AlarmScheduler.schedule(context, alarm)
                    // Get Claude's line onto the phone now, not at 06:00.
                    cacheVoice(alarm)
                    refresh()
                }
            }) { Icon(Icons.Filled.Add, contentDescription = "Add alarm") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (problems.isNotEmpty()) {
                item {
                    // Every one of these is a silent failure you would otherwise meet at 06:00.
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Tomorrow is not safe", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            problems.forEach { p ->
                                Spacer(Modifier.height(12.dp))
                                Text(p.title, fontWeight = FontWeight.SemiBold)
                                Text(p.detail, fontSize = 13.sp)
                                p.fix?.let { fix ->
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(onClick = {
                                        if (fix == Fix.CACHE_VOICE) {
                                            alarms.filter { it.enabled && it.voice }.forEach(::cacheVoice)
                                        } else openFix(context, fix)
                                    }) { Text("Fix this") }
                                }
                            }
                        }
                    }
                }
            }

            if (alarms.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                        Text("No alarms yet", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(
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
                        val days = if (day in alarm.days) alarm.days - day else alarm.days + day
                        val updated = alarm.copy(days = days)
                        AlarmStore.upsert(context, updated)
                        if (updated.enabled) AlarmScheduler.schedule(context, updated)
                        refresh()
                    },
                    onChallenge = { c ->
                        val updated = alarm.copy(challenge = c)
                        AlarmStore.upsert(context, updated)
                        refresh()
                    },
                    onRecache = { cacheVoice(alarm) },
                    onTest = { AlarmService.test(context, alarm.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AlarmRow(
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%02d:%02d".format(alarm.hour, alarm.minute),
                fontSize = 40.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f),
            )
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DAY_NAMES.forEach { (day, name) ->
                DayToggle(
                    label = name,
                    selected = day in alarm.days,
                    modifier = Modifier.weight(1f),
                    onClick = { onToggleDay(day) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("To stop it", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Challenge.entries.forEach { c ->
                FilterChip(
                    selected = alarm.challenge == c,
                    onClick = { onChallenge(c) },
                    label = { Text(c.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    caching -> "Fetching Claude's line…"
                    voiceState == VoiceCache.State.CACHED -> "Briefing ready on this phone"
                    else -> "No briefing cached — tone only"
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
                Icon(Icons.Filled.PlayArrow, contentDescription = "Ring it now")
            }
        }

        Text(
            if (alarm.repeats) "Repeats weekly" else "Once, at the next %02d:%02d".format(alarm.hour, alarm.minute),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * One day of the week. A plain weighted box rather than a [FilterChip] because a chip sizes
 * itself to its content and there are seven of them: on any phone narrower than the row wants,
 * the last chip is squeezed until its label disappears. A weight is a promise the layout can
 * always keep.
 */
@Composable
private fun DayToggle(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun pickTime(context: Context, onPicked: (Int, Int) -> Unit) {
    val now = Calendar.getInstance()
    TimePickerDialog(
        context, { _, h, m -> onPicked(h, m) },
        now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true,
    ).show()
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
