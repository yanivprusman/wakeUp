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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.automatelinux.wakeUp.alarm.*
import kotlinx.coroutines.delay
import java.util.Calendar

private val DAY_NAMES = listOf(
    Calendar.SUNDAY to "Su", Calendar.MONDAY to "Mo", Calendar.TUESDAY to "Tu",
    Calendar.WEDNESDAY to "We", Calendar.THURSDAY to "Th", Calendar.FRIDAY to "Fr",
    Calendar.SATURDAY to "Sa",
)

/**
 * What the time picker is about to set. One dialog serves both jobs, and a nullable alarm
 * plus a boolean would leave "new" and "editing nothing" as the same value — the exact
 * ambiguity that makes a picker save over the wrong row.
 */
private sealed interface TimeTarget {
    /** A brand-new alarm, seeded from the clock. */
    object New : TimeTarget

    /** One already on the list; everything but the time is kept. */
    data class Existing(val alarm: Alarm) : TimeTarget
}

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
    var picking by remember { mutableStateOf<TimeTarget?>(null) }

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
                onClick = { picking = TimeTarget.New },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Filled.Add, contentDescription = "Add alarm") }
        },
    ) { padding ->
        LazyColumn(
            // imePadding, because the window is edge-to-edge: enableEdgeToEdge turns off
            // decorFitsSystemWindows, so the keyboard arrives as an inset and does NOT resize
            // the window. Without this the name field of any alarm below the first is typed
            // into from underneath the keyboard.
            Modifier.padding(padding).fillMaxSize().imePadding(),
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
                    onEditTime = { picking = TimeTarget.Existing(alarm) },
                    // Every keystroke reaches the disk. A name typed and then abandoned —
                    // back pressed, app swiped away, screen off — is a name the user believes
                    // they set, and this app's whole promise is that what you set is what
                    // happens. Deliberately NOT a full refresh(): that re-runs the whole
                    // readiness scan, which has no business firing once per letter.
                    onType = { raw ->
                        val updated = alarm.copy(label = raw)
                        AlarmStore.upsert(context, updated)
                        alarms = alarms.map { if (it.id == updated.id) updated else it }
                    },
                    onSettled = { finalName ->
                        val updated = alarm.copy(label = finalName)
                        AlarmStore.upsert(context, updated)
                        // The briefing OPENS by saying the name, so the cached WAV now greets
                        // you by a name this alarm no longer has. Delete before fetching: the
                        // card then reads "no briefing" honestly while the new one renders,
                        // and a failed fetch leaves nothing rather than the wrong thing.
                        VoiceCache.clear(context, updated.id)
                        if (updated.voice) cacheVoice(updated)
                        refresh()
                    },
                    onRecache = { cacheVoice(alarm) },
                    onTest = { AlarmService.test(context, alarm.id) },
                )
            }
        }
    }

    picking?.let { target ->
        val now = Calendar.getInstance()
        val existing = (target as? TimeTarget.Existing)?.alarm
        WakeTimeDialog(
            title = if (existing == null) "Wake me at" else "Move it to",
            initialHour = existing?.hour ?: now.get(Calendar.HOUR_OF_DAY),
            initialMinute = existing?.minute ?: now.get(Calendar.MINUTE),
            onDismiss = { picking = null },
            onConfirm = { h, m ->
                picking = null
                if (existing == null) {
                    val alarm = Alarm(id = AlarmStore.nextId(context), hour = h, minute = m)
                    AlarmStore.upsert(context, alarm)
                    AlarmScheduler.schedule(context, alarm)
                    cacheVoice(alarm)   // get Claude's line onto the phone now, not at 04:40
                    refresh()
                } else {
                    val updated = existing.copy(hour = h, minute = m)
                    AlarmStore.upsert(context, updated)
                    // No cancel first: the PendingIntent is keyed on the id, so setAlarmClock
                    // replaces the old time outright — and schedule() cancels by itself when
                    // the alarm is off, which is the case a separate cancel would get wrong.
                    AlarmScheduler.schedule(context, updated)
                    // The briefing is NOT re-fetched: it never mentions the alarm's time, only
                    // the name. Re-rendering here would spend a Kokoro pass to produce the
                    // identical WAV.
                    refresh()
                }
            },
        )
    }
}

@Composable
private fun Header(next: Alarm?) {
    // Sheep mode: the same wait, counted in seconds. One sheep a second is the old trick for
    // getting to sleep, and it is also exactly the number the question asks for — so the joke
    // and the answer are the same figure.
    var sheep by remember { mutableStateOf(false) }

    // The clock ticks ALWAYS, not only in sheep mode. It was keyed on `sheep`, which meant the
    // ordinary "in 4h 38m" was frozen at whatever the time was when the screen was first
    // composed: leave the app open, or come back to it, and it kept reporting a wait that had
    // already shrunk. A countdown that does not count is not a countdown, and on this screen
    // it is the one number the whole app is for. One recomposition a second, and it stops on
    // its own when the header leaves composition.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "wakeUp",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            if (next != null) SheepButton(active = sheep) { sheep = !sheep }
        }
        Spacer(Modifier.height(10.dp))

        if (next == null) {
            Text("No alarm armed", fontSize = 30.sp, fontWeight = FontWeight.Light)
        } else {
            val remaining = (next.nextTrigger() - now).coerceAtLeast(0L)
            if (sheep) {
                val seconds = remaining / 1000
                Text(
                    "%,d".format(seconds),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "sheep, one a second, until %02d:%02d".format(next.hour, next.minute),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // The countdown is the headline, not the time: at bedtime the question is never
                // "when is it set for", it is "how long have I got".
                Text(
                    countdown(remaining),
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
}

/** Tap the sheep to count the wait in seconds instead of hours. Tap it again to stop. */
@Composable
private fun SheepButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("\uD83D\uDC11", fontSize = 22.sp)
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
    onEditTime: () -> Unit,
    onType: (String) -> Unit,
    onSettled: (String) -> Unit,
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
                // The clock opens the picker. The pencil is there because an invisible tap
                // target is the same problem as no tap target: the time sat here unchangeable
                // for long enough that "can I even edit this?" was a fair question, and a
                // ripple you only find by guessing does not answer it.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onEditTime)
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "%02d:%02d".format(alarm.hour, alarm.minute),
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Light,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Change the time",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }

            Text(
                if (alarm.repeats) "Every week" else "Once",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(12.dp))
            NameField(name = alarm.label, onType = onType, onSettled = onSettled)

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
 * The alarm's name, edited in place.
 *
 * The name was never decoration and it was never editable: the notification title, the ring
 * screen's headline and the FIRST WORDS Claude speaks are all this one string, and nothing in
 * the app could set it — every alarm was "Alarm", greeted with "Good morning."
 *
 * It looks like the day and challenge toggles — the same rounded surfaceVariant box — so that
 * "you can touch this" is said the same way everywhere on the card.
 *
 * Two callbacks, because the two things a rename touches cost wildly different amounts.
 * [onType] fires per keystroke and writes the name — a SharedPreferences apply(), and cheap
 * enough that the name can never be lost to a back press or a swiped-away app. [onSettled]
 * fires once typing pauses and re-renders Claude's briefing, which is a Kokoro render over
 * the network: one per name, never one per letter.
 */
@Composable
private fun NameField(name: String, onType: (String) -> Unit, onSettled: (String) -> Unit) {
    // Seeded once, on purpose. `items(key = { it.id })` already gives one of these per alarm,
    // and re-seeding from the store as it saves would swallow the space you just typed.
    var text by remember { mutableStateOf(name) }
    var settled by remember { mutableStateOf(name.trim()) }
    val focus = LocalFocusManager.current

    LaunchedEffect(text) {
        val trimmed = text.trim()
        if (trimmed == settled) return@LaunchedEffect
        delay(1200)
        settled = trimmed
        onSettled(trimmed)
    }

    BasicTextField(
        value = text,
        // A name, not a note: it is spoken aloud and it sits under a 52sp clock.
        onValueChange = { text = it.take(40); onType(text) },
        singleLine = true,
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text("Name it", fontSize = 15.sp, color = MaterialTheme.colorScheme.outline)
                }
                field()
            }
        },
    )
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
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
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
