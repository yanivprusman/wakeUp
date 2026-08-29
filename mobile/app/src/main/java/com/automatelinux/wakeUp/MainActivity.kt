package com.automatelinux.wakeUp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.automatelinux.wakeUp.alarm.AlarmScheduler
import com.automatelinux.wakeUp.alarm.Readiness
import com.automatelinux.wakeUp.ui.AlarmListScreen
import com.automatelinux.wakeUp.ui.WakeUpTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark by design, so the system bars must be told to draw LIGHT icons.
        // Left to itself enableEdgeToEdge picks by the device's day/night setting and drew a
        // black clock on an indigo bar — invisible.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        // The ringing alarm is a foreground service with a full-screen intent; without the
        // notification permission it has no notification to attach that intent to.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Anything could have moved while we were away: a reboot, a clock change, an OS that
        // dropped our alarms. Re-arm from the store on every launch.
        AlarmScheduler.rescheduleAll(this)

        // Ask every night, while there is still a night in which to fix whatever is wrong.
        Readiness.scheduleBedtimeCheck(this)

        setContent {
            WakeUpTheme { AlarmListScreen() }
        }
    }
}
