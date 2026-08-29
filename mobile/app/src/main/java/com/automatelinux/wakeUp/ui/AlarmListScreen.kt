package com.automatelinux.wakeUp.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.automatelinux.wakeUp.alarm.Alarm
import com.automatelinux.wakeUp.alarm.AlarmScheduler
import com.automatelinux.wakeUp.alarm.AlarmStore
import java.util.Calendar

private val DAY_NAMES = listOf(
    Calendar.SUNDAY to "S", Calendar.MONDAY to "M", Calendar.TUESDAY to "T",
    Calendar.WEDNESDAY to "W", Calendar.THURSDAY to "T", Calendar.FRIDAY to "F",
    Calendar.SATURDAY to "S",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen() {
    val context = LocalContext.current
    var alarms by remember { mutableStateOf(AlarmStore.all(context)) }
    var canSchedule by remember { mutableStateOf(AlarmScheduler.canScheduleExact(context)) }

    fun refresh() {
        alarms = AlarmStore.all(context)
        canSchedule = AlarmScheduler.canScheduleExact(context)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("wakeUp") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { pickTime(context) { h, m ->
                val alarm = Alarm(id = AlarmStore.nextId(context), hour = h, minute = m)
                AlarmStore.upsert(context, alarm)
                AlarmScheduler.schedule(context, alarm)
                refresh()
            } }) { Icon(Icons.Filled.Add, contentDescription = "Add alarm") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Without exact-alarm permission every alarm here is decoration. Say so, loudly,
            // at the top — this is the one failure you must not discover in the morning.
            if (!canSchedule) {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Alarms cannot fire", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Android is not letting this app schedule exact alarms, so nothing below will go off.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openExactAlarmSettings(context) }) { Text("Fix it") }
                    }
                }
            }

            if (alarms.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No alarms yet", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmRow(
                            alarm = alarm,
                            onToggle = { on ->
                                val updated = alarm.copy(enabled = on)
                                AlarmStore.upsert(context, updated)
                                if (on) AlarmScheduler.schedule(context, updated)
                                else AlarmScheduler.cancel(context, updated)
                                refresh()
                            },
                            onDelete = {
                                AlarmScheduler.cancel(context, alarm)
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
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onToggleDay: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%02d:%02d".format(alarm.hour, alarm.minute),
                fontSize = 40.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAY_NAMES.forEach { (day, name) ->
                FilterChip(
                    selected = day in alarm.days,
                    onClick = { onToggleDay(day) },
                    label = { Text(name) },
                )
            }
        }
        Text(
            if (alarm.repeats) "Repeats weekly" else "Once, at the next ${"%02d:%02d".format(alarm.hour, alarm.minute)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun pickTime(context: Context, onPicked: (Int, Int) -> Unit) {
    val now = Calendar.getInstance()
    TimePickerDialog(
        context,
        { _, h, m -> onPicked(h, m) },
        now.get(Calendar.HOUR_OF_DAY),
        now.get(Calendar.MINUTE),
        true,
    ).show()
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
