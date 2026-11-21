package com.github.jschbe.simplempdplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.jschbe.simplempdplayer.R
import com.github.jschbe.simplempdplayer.timer.*
import java.text.DateFormat
import java.util.Date

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
    var custom by remember(state.settings.minutes) { mutableStateOf(state.settings.minutes.toString()) }
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
                    Switch(state.active || delivering, onEnabled, enabled = editable && !delivering && (state.active || selectedName != null),
                        modifier = Modifier.testTag("sleep_timer_switch"))
                }
                if (selectedName == null && !state.active) Text(stringResource(R.string.timer_select_server))
                state.targetName?.takeIf { state.settings.record != null }?.let {
                    Text(stringResource(R.string.timer_target, it))
                }
                if (state.active) {
                    SleepRemaining(state)
                    state.estimatedEndMillis?.let {
                        Text(stringResource(R.string.timer_estimated_end, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))))
                    }
                } else Text(stringResource(timerStatusText(state)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    sleepTimerPresets.forEach { preset ->
                        FilterChip(selected = state.settings.minutes == preset, onClick = { onMinutes(preset) },
                            enabled = editable && !delivering, label = { Text(stringResource(R.string.timer_preset_minutes, preset)) },
                            modifier = Modifier.testTag("timer_preset_$preset"))
                    }
                }
                OutlinedTextField(custom, { custom = it }, label = { Text(stringResource(R.string.timer_custom_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                    enabled = editable && !delivering, isError = minutes == null,
                    supportingText = { if (minutes == null) Text(stringResource(R.string.timer_minutes_error)) },
                    modifier = Modifier.fillMaxWidth().testTag("timer_custom_minutes"))
                TextButton(onClick = { minutes?.let(onMinutes) }, enabled = editable && !delivering && minutes != null && minutes != state.settings.minutes,
                    modifier = Modifier.testTag("timer_apply_duration")) { Text(stringResource(R.string.timer_apply_duration)) }
                Text(stringResource(R.string.timer_restart_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.timer_power_hint), style = MaterialTheme.typography.bodySmall)
                state.operationError?.let { Text(stringResource(timerErrorText(it)), color = MaterialTheme.colorScheme.error) }
                if (state.settings.recovered) Text(stringResource(R.string.timer_recovered))
                if (state.operationError == TimerError.STORAGE) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
internal fun SleepRemaining(state: SleepTimerState) {
    val remaining = state.remainingMillis ?: return
    if (remaining == 0L) Text(stringResource(R.string.timer_due)) else {
        val minutes = (remaining + 59_999) / 60_000
        Text(stringResource(R.string.timer_remaining, minutes / 60, minutes % 60), modifier = Modifier.testTag("timer_remaining"))
    }
}

private fun timerStatusText(state: SleepTimerState): Int = when (state.settings.record?.status) {
    null -> R.string.timer_off
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
