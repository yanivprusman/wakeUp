package com.automatelinux.wakeUp.ring

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.automatelinux.wakeUp.alarm.Alarm
import com.automatelinux.wakeUp.alarm.AlarmReceiver
import com.automatelinux.wakeUp.alarm.AlarmService
import com.automatelinux.wakeUp.alarm.AlarmStore
import com.automatelinux.wakeUp.alarm.Challenge
import kotlin.random.Random

/**
 * The screen that appears over the lock screen when the alarm goes off.
 *
 * Everything here exists to make dismissal cost consciousness. There is no swipe, the back
 * button is inert, and with a challenge set the Stop button does not appear until you have
 * answered correctly — a wrong answer draws a new sum, so guessing is slower than waking up.
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val id = intent.getIntExtra(AlarmReceiver.EXTRA_ID, -1)
            .takeIf { it >= 0 } ?: AlarmService.currentAlarmId ?: run { finish(); return }
        val alarm = AlarmStore.get(this, id)

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
     * Turn the screen on and show through the keyguard. Brightness is forced to full: the
     * light is half the alarm, and a screen at 5% at 06:00 is no light at all.
     */
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
    // The back button is the one-handed reflex dismissal. It does nothing here.
    BackHandler(enabled = true) {}

    var a by remember { mutableIntStateOf(Random.nextInt(11, 99)) }
    var b by remember { mutableIntStateOf(Random.nextInt(11, 99)) }
    var answer by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    val needsChallenge = alarm?.challenge == Challenge.MATH
    val solved = !needsChallenge || answer.toIntOrNull() == a + b

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
            Spacer(Modifier.height(12.dp))
            Text(
                alarm?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "",
                fontSize = 64.sp, fontWeight = FontWeight.Light,
            )

            if (needsChallenge) {
                Spacer(Modifier.height(40.dp))
                Text("$a + $b = ?", fontSize = 40.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = answer,
                    onValueChange = { new ->
                        answer = new.filter { it.isDigit() }.take(4)
                        wrong = false
                        // A complete, wrong answer redraws the sum: guessing costs more than
                        // thinking, which is the whole point.
                        if (answer.length >= (a + b).toString().length && answer.toIntOrNull() != a + b) {
                            wrong = true
                            a = Random.nextInt(11, 99); b = Random.nextInt(11, 99); answer = ""
                        }
                    },
                    label = { Text(if (wrong) "Not that. Try the new one." else "Answer") },
                    isError = wrong,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Spacer(Modifier.height(36.dp))
            Button(
                onClick = onDismiss,
                enabled = solved,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(if (solved) "Stop" else "Solve it to stop", fontSize = 20.sp) }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
                Text("Snooze 5 minutes")
            }
        }
    }
}
