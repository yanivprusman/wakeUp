package com.automatelinux.wakeUp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * One scheme, and it is dark — deliberately, not as a default.
 *
 * Every moment this app is looked at is a dark one: setting tomorrow's alarm in bed, and being
 * woken by it at 04:40. A white screen at either end is hostile, and the ring screen forces
 * brightness to maximum, so a light theme there would be a flashbulb. The palette is the app
 * icon's: indigo night, amber for anything that is armed and will act.
 */
private val WakeUpColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFFFB020),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF2A1600),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF5C3B00),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDFA8),
    secondary = androidx.compose.ui.graphics.Color(0xFFCFA8FF),
    background = androidx.compose.ui.graphics.Color(0xFF130B27),
    onBackground = androidx.compose.ui.graphics.Color(0xFFEFE7FF),
    surface = androidx.compose.ui.graphics.Color(0xFF1C1136),
    onSurface = androidx.compose.ui.graphics.Color(0xFFEFE7FF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF2A1B4D),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC6B7E4),
    outline = androidx.compose.ui.graphics.Color(0xFF8C7BB5),
    error = androidx.compose.ui.graphics.Color(0xFFFF8A7A),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF5B1A14),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFD9D2),
)

@Composable
fun WakeUpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WakeUpColors, typography = Typography(), content = content)
}
