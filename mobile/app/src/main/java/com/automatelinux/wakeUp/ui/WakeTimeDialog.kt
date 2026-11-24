package com.automatelinux.wakeUp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's own time picker.
 *
 * The platform `TimePickerDialog` renders in the OS's holo-era green regardless of the app's
 * theme — a teal clock face dropped into a night-indigo app, which is what it looked like.
 * Material 3's [TimePicker] inherits the scheme above, so it belongs to this app.
 *
 * **Typing**: the first tap on the hours (or minutes) box picks it, as always, and the dial
 * follows. A SECOND tap on the box that is already picked swaps the dial for the keyboard,
 * with that box ready to be typed over. No extra button — the owner asked for exactly this.
 * The picker's header boxes are internal to Material, so the second tap is read from a
 * parent observer in the Initial pass (it sees the tap, consumes nothing) and judged against
 * the selection as it was before the tap landed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WakeTimeDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    // null = the dial; otherwise the keyboard is up, starting on this field.
    var typing by remember { mutableStateOf<TimePickerSelectionMode?>(null) }
    var hourText by remember { mutableStateOf(TextFieldValue("")) }
    var minuteText by remember { mutableStateOf(TextFieldValue("")) }

    val typedHour = hourText.text.toIntOrNull()?.takeIf { it in 0..23 }
    val typedMinute = minuteText.text.toIntOrNull()?.takeIf { it in 0..59 }
    val valid = typing == null || (typedHour != null && typedMinute != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            val start = typing
            if (start == null) {
                Box(
                    Modifier.pointerInput(Unit) {
                        val headerPx = HEADER_HEIGHT.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (down.position.y > headerPx) return@awaitEachGesture
                            val before = state.selection
                            waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                            val tapped = if (down.position.x < size.width / 2) TimePickerSelectionMode.Hour
                                         else TimePickerSelectionMode.Minute
                            if (tapped == before) {
                                hourText = TextFieldValue("%02d".format(state.hour))
                                minuteText = TextFieldValue("%02d".format(state.minute))
                                typing = tapped
                            }
                        }
                    },
                ) {
                    TimePicker(state = state, layoutType = TimePickerLayoutType.Vertical)
                }
            } else {
                TypedTime(
                    hour = hourText, minute = minuteText, start = start,
                    onHour = { hourText = it }, onMinute = { minuteText = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (typing == null) onConfirm(state.hour, state.minute)
                    else onConfirm(typedHour!!, typedMinute!!)
                },
                enabled = valid,
                modifier = Modifier.testTag("wake-time-set"),
            ) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("wake-time-cancel")) { Text("Cancel") }
        },
    )
}

/** Height of [TimePicker]'s hours/minutes header in the vertical layout (Material's
 *  TimeSelectorContainerHeight). Taps below it belong to the dial and are ignored. */
private val HEADER_HEIGHT = 80.dp

/**
 * The two boxes again, as fields. The one that was tapped has focus with its digits selected,
 * so typing replaces them; two digits of hours move on to the minutes by themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypedTime(
    hour: TextFieldValue,
    minute: TextFieldValue,
    start: TimePickerSelectionMode,
    onHour: (TextFieldValue) -> Unit,
    onMinute: (TextFieldValue) -> Unit,
) {
    val hourFocus = remember { FocusRequester() }
    val minuteFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        (if (start == TimePickerSelectionMode.Hour) hourFocus else minuteFocus).requestFocus()
        keyboard?.show()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeField(
                value = hour, max = 23, focus = hourFocus, dataId = "wake-time-hour",
                imeAction = ImeAction.Next,
                onChange = { v ->
                    // Only typing moves on — selecting the old digits on focus is a change too,
                    // and advancing on that skipped the hours the moment they were tapped.
                    val typed = v.text != hour.text
                    onHour(v)
                    if (typed && v.text.length == 2) minuteFocus.requestFocus()
                },
            )
            Text(":", fontSize = 57.sp, modifier = Modifier.padding(horizontal = 8.dp))
            TimeField(
                value = minute, max = 59, focus = minuteFocus, dataId = "wake-time-minute",
                imeAction = ImeAction.Done,
                onChange = onMinute,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Type the hour and the minutes",
            color = MaterialTheme.colorScheme.outline, fontSize = 13.sp,
        )
    }
}

@Composable
private fun TimeField(
    value: TextFieldValue,
    max: Int,
    focus: FocusRequester,
    dataId: String,
    imeAction: ImeAction,
    onChange: (TextFieldValue) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bad = value.text.isNotEmpty() && (value.text.toIntOrNull() ?: Int.MAX_VALUE) > max
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = { v -> onChange(v.copy(text = v.text.filter { it.isDigit() }.take(2))) },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 57.sp,
            textAlign = TextAlign.Center,
            color = when {
                bad -> scheme.error
                focused -> scheme.onPrimaryContainer
                else -> scheme.onSurface
            },
        ),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        modifier = Modifier
            .width(96.dp)
            .height(80.dp)
            .background(
                if (focused) scheme.primaryContainer else scheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .wrapContentHeight(Alignment.CenterVertically)
            .focusRequester(focus)
            .onFocusChanged { f ->
                // Selecting everything on focus means the first digit typed replaces the old
                // value instead of being appended to it.
                if (f.isFocused && !focused) onChange(value.copy(selection = TextRange(0, value.text.length)))
                focused = f.isFocused
            }
            .testTag(dataId),
    )
}
