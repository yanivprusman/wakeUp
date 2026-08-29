package com.automatelinux.wakeUp.ring

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.automatelinux.wakeUp.alarm.Alarm
import com.automatelinux.wakeUp.alarm.AlarmReceiver
import com.automatelinux.wakeUp.alarm.AlarmService
import com.automatelinux.wakeUp.alarm.AlarmStore
import com.automatelinux.wakeUp.alarm.Challenge
import kotlin.random.Random

/**
 * The screen that takes the phone over when the alarm goes off.
 *
 * Every decision here exists to make dismissal cost consciousness. There is no swipe, the back
 * button is inert, the volume keys are swallowed, and Stop stays disabled until the challenge
 * has been passed [Alarm.requiredCorrect] times in a row — one correct answer is a coin flip
 * you can win asleep, two is not.
 */
class RingActivity : ComponentActivity() {

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val id = intent.getIntExtra(AlarmReceiver.EXTRA_ID, -1)
            .takeIf { it >= 0 } ?: AlarmService.currentAlarmId ?: run { finish(); return }
        val alarm = AlarmStore.get(this, id)

        if (alarm?.challenge == Challenge.SPEAK &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RingScreen(
                    alarm = alarm,
                    onDismiss = { AlarmService.stop(this); finish() },
                    onSnooze = { AlarmService.snooze(this, id); finish() },
                )
            }
        }
    }

    /**
     * The volume keys are the reflex dismissal that costs the least wakefulness of all, and
     * on many phones they silence a ringing alarm outright. Here they do nothing — swallowed
     * before the system sees them, on top of [com.automatelinux.wakeUp.alarm.AlarmAudio]'s
     * volume guard, which would put the level back anyway.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = when (event.keyCode) {
        android.view.KeyEvent.KEYCODE_VOLUME_UP,
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
        android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
        -> true
        else -> super.dispatchKeyEvent(event)
    }

    /** Turn the screen on, show through the keyguard, and go to full brightness — the light is
     *  half the alarm, and a screen at 5% at 06:00 is no light at all. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }
    }
}

@Composable
private fun RingScreen(alarm: Alarm?, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    BackHandler(enabled = true) {}

    val challenge = alarm?.challenge ?: Challenge.NONE
    val needed = alarm?.requiredCorrect ?: 1
    var passed by remember { mutableIntStateOf(0) }
    val solved = challenge == Challenge.NONE || passed >= needed

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                alarm?.label?.ifBlank { null } ?: "Wake up",
                fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                alarm?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "",
                fontSize = 64.sp, fontWeight = FontWeight.Light,
            )

            if (challenge != Challenge.NONE) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "$passed of $needed",
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(28.dp))
                when (challenge) {
                    Challenge.MATH -> MathChallenge(solved) { passed++ }
                    Challenge.SPEAK -> SpeakChallenge(solved) { passed++ }
                    Challenge.SHAKE -> ShakeChallengeUi(solved) { passed++ }
                    Challenge.NONE -> Unit
                }
            }

            Spacer(Modifier.height(36.dp))
            Button(
                onClick = onDismiss,
                enabled = solved,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(if (solved) "Stop" else "Not yet", fontSize = 20.sp) }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
                Text("Snooze 5 minutes")
            }
        }
    }
}

@Composable
private fun MathChallenge(done: Boolean, onPass: () -> Unit) {
    var a by remember { mutableIntStateOf(Random.nextInt(11, 99)) }
    var b by remember { mutableIntStateOf(Random.nextInt(11, 99)) }
    var answer by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    if (done) { Text("Solved.", fontSize = 22.sp); return }

    Text("$a + $b = ?", fontSize = 40.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = answer,
        onValueChange = { new ->
            answer = new.filter { it.isDigit() }.take(4)
            wrong = false
            val target = a + b
            if (answer.length >= target.toString().length) {
                if (answer.toIntOrNull() == target) {
                    onPass()
                    a = Random.nextInt(11, 99); b = Random.nextInt(11, 99); answer = ""
                } else {
                    // A wrong answer redraws the sum: guessing is slower than waking up.
                    wrong = true
                    a = Random.nextInt(11, 99); b = Random.nextInt(11, 99); answer = ""
                }
            }
        },
        label = { Text(if (wrong) "No. Here's another." else "Answer") },
        isError = wrong,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun SpeakChallenge(done: Boolean, onPass: () -> Unit) {
    val context = LocalContext.current
    val challenge = remember { SpeechChallenge(context) }
    var status by remember { mutableStateOf("Press and say it") }
    var listening by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { challenge.stop() } }
    if (done) { Text("Heard you.", fontSize = 22.sp); return }

    Text("“$WAKE_PHRASE”", fontSize = 32.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(status, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            listening = true
            status = "Listening…"
            challenge.listen(
                onResult = { heard, correct ->
                    listening = false
                    if (correct) { status = "Good."; onPass() }
                    else status = if (heard.isBlank()) "Didn't catch that. Again." else "Heard “$heard”. Again."
                },
                onError = { listening = false; status = it },
            )
        },
        enabled = !listening,
    ) { Text(if (listening) "Listening…" else "Say it") }
}

@Composable
private fun ShakeChallengeUi(done: Boolean, onPass: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var target by remember { mutableIntStateOf(12) }

    DisposableEffect(done) {
        val challenge = ShakeChallenge(context)
        if (!done) {
            challenge.start(
                onProgress = { c, n -> progress = c; target = n },
                onComplete = { progress = 0; onPass() },
            )
        }
        onDispose { challenge.stop() }
    }
    if (done) { Text("Shaken.", fontSize = 22.sp); return }

    Text("Shake it", fontSize = 32.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(
        progress = { progress.toFloat() / target.coerceAtLeast(1) },
        modifier = Modifier.fillMaxWidth().height(10.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text("$progress / $target", color = MaterialTheme.colorScheme.outline)
}
