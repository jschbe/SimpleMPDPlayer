package com.github.jschbe.simplempdplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.timer.*
import androidx.compose.ui.platform.LocalContext
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerDialog(
    state: SleepTimerState,
    selectedName: String?,
    settingsBusy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onMinutes: (Int) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by rememberSaveable(state.settings.minutes) { mutableStateOf(state.settings.minutes.toString()) }
    var customMode by rememberSaveable { mutableStateOf(state.settings.minutes !in sleepTimerPresets) }
    var expanded by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val minutes = sleepMinutes(custom)
    val editable = !state.loading && !state.busy && !settingsBusy && state.operationError != TimerError.STORAGE
    val delivering = state.settings.record?.status == TimerStatus.DELIVERING
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.timer_enabled), Modifier.weight(1f))
                    Switch(state.enabled, onEnabled, enabled = editable && !delivering && (state.enabled || selectedName != null),
                        modifier = Modifier.testTag("sleep_timer_switch"))
                }
                if (selectedName == null && !state.enabled) Text(stringResource(R.string.timer_select_server))
                state.targetName?.takeIf { state.settings.record != null }?.let {
                    Text(stringResource(R.string.timer_target, it))
                }
                if (state.active) {
                    SleepRemaining(state)
                    state.estimatedEndMillis?.let {
                        val context = LocalContext.current
                        Text(stringResource(R.string.timer_estimated_end,
                            android.text.format.DateFormat.getDateFormat(context).format(Date(it)) + " " +
                                android.text.format.DateFormat.getTimeFormat(context).format(Date(it))))
                    }
                } else Text(stringResource(timerStatusText(state)))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (editable && !delivering) expanded = it }) {
                    OutlinedTextField(
                        value = if (customMode) stringResource(R.string.timer_custom_option) else pluralStringResource(R.plurals.timer_duration_minutes, state.settings.minutes, state.settings.minutes),
                        onValueChange = {}, readOnly = true, singleLine = true, enabled = editable && !delivering,
                        label = { Text(stringResource(R.string.timer_duration)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                            enabled = editable && !delivering).testTag("timer_duration_dropdown"))
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        sleepTimerPresets.forEach { preset ->
                            DropdownMenuItem(text = { Text(pluralStringResource(R.plurals.timer_duration_minutes, preset, preset)) },
                                onClick = { customMode = false; expanded = false; onMinutes(preset) },
                                modifier = Modifier.testTag("timer_preset_$preset"))
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.timer_custom_option)) },
                            onClick = { customMode = true; expanded = false }, modifier = Modifier.testTag("timer_custom_option"))
                    }
                }
                if (customMode) {
                    OutlinedTextField(custom, { custom = it }, label = { Text(stringResource(R.string.timer_custom_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (editable && !delivering && minutes != null) {
                                onMinutes(minutes)
                                focus.clearFocus(); keyboard?.hide()
                            }
                        }), singleLine = true, enabled = editable && !delivering, isError = minutes == null,
                        supportingText = { if (minutes == null) Text(stringResource(R.string.timer_minutes_error)) },
                        modifier = Modifier.fillMaxWidth().testTag("timer_custom_minutes"))
                    Text(stringResource(R.string.timer_custom_done_hint), style = MaterialTheme.typography.bodySmall)
                }
                if (state.waiting) Text(stringResource(R.string.timer_waiting_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.timer_restart_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.timer_power_hint), style = MaterialTheme.typography.bodySmall)
                state.operationError?.let { Text(stringResource(timerErrorText(it)), color = MaterialTheme.colorScheme.error) }
                if (state.settings.recovered) Text(stringResource(R.string.timer_recovered))
                if (state.operationError == TimerError.STORAGE) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        },
        confirmButton = { TextButton(onClick = {
            if (customMode && editable && !delivering) minutes?.let(onMinutes)
            onDismiss()
        }, enabled = !customMode || minutes != null) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
internal fun SleepRemaining(state: SleepTimerState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val remaining = state.remainingMillis
    val text = when {
        state.waiting -> stringResource(R.string.timer_waiting)
        state.active && remaining == 0L -> stringResource(R.string.timer_due)
        state.active && remaining != null -> {
            val minutes = (remaining + 59_999) / 60_000
            if (compact) stringResource(R.string.timer_compact_remaining, minutes)
            else stringResource(R.string.timer_remaining, minutes / 60, minutes % 60)
        }
        compact && !state.enabled -> stringResource(R.string.timer_compact_off)
        else -> stringResource(timerStatusText(state))
    }
    Text(text, modifier.testTag(if (state.active) "timer_remaining" else "timer_status"),
        style = if (compact) MaterialTheme.typography.bodySmall else LocalTextStyle.current,
        maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
        textAlign = if (compact) TextAlign.End else TextAlign.Start)
}

private fun timerStatusText(state: SleepTimerState): Int = when (state.settings.record?.status) {
    null -> R.string.timer_off
    TimerStatus.WAITING -> R.string.timer_waiting
    TimerStatus.ARMING -> R.string.timer_scheduling
    TimerStatus.ACTIVE -> R.string.timer_off
    TimerStatus.DELIVERING -> R.string.timer_delivering
    TimerStatus.COMPLETED -> R.string.timer_completed
    TimerStatus.CANCELLED -> R.string.timer_cancelled
    TimerStatus.PROFILE_CHANGED -> R.string.timer_profile_changed
    TimerStatus.REBOOTED -> R.string.timer_rebooted
    TimerStatus.FAILED -> timerErrorText(state.settings.record.error ?: TimerError.UNCERTAIN)
}

private fun timerErrorText(error: TimerError): Int = when (error) {
    TimerError.NETWORK -> R.string.timer_error_network
    TimerError.TIMEOUT -> R.string.timer_error_timeout
    TimerError.REJECTED -> R.string.timer_error_rejected
    TimerError.AUTHENTICATION -> R.string.timer_error_authentication
    TimerError.PERMISSION -> R.string.timer_error_permission
    TimerError.PROTOCOL -> R.string.timer_error_protocol
    TimerError.SCHEDULING -> R.string.timer_error_scheduling
    TimerError.UNCERTAIN -> R.string.timer_error_uncertain
    TimerError.MISSING_ALARM -> R.string.timer_error_missing_alarm
    TimerError.CLOCK -> R.string.timer_error_clock
    TimerError.STORAGE -> R.string.timer_error_storage
}
