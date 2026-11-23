@file:OptIn(ExperimentalMaterial3Api::class)

package org.fartingaround.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.fartingaround.app.ui.components.Eyebrow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "When it happened": a date button and a time button, each opening the
 * Material pickers. Stores an ISO instant like the web app's datetime-local.
 */
@Composable
fun DateTimeField(label: String, iso: String, onChange: (String) -> Unit) {
	val zone = ZoneId.systemDefault()
	val current = runCatching { Instant.parse(iso).atZone(zone) }.getOrNull() ?: Instant.now().atZone(zone)
	var showDate by remember { mutableStateOf(false) }
	var showTime by remember { mutableStateOf(false) }

	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Eyebrow(label)
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
				Text(current.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
			}
			OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
				Text(current.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
			}
		}
	}

	if (showDate) {
		// DatePicker works in UTC-midnight millis for the selected day.
		val state = rememberDatePickerState(initialSelectedDateMillis = current.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
		DatePickerDialog(
			onDismissRequest = { showDate = false },
			confirmButton = {
				TextButton(onClick = {
					state.selectedDateMillis?.let { ms ->
						val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
						onChange(date.atTime(current.toLocalTime()).atZone(zone).toInstant().toString())
					}
					showDate = false
				}) { Text("OK") }
			},
			dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }
		) { DatePicker(state = state) }
	}

	if (showTime) {
		val state = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute)
		AlertDialog(
			onDismissRequest = { showTime = false },
			confirmButton = {
				TextButton(onClick = {
					val time = LocalTime.of(state.hour, state.minute)
					onChange(LocalDate.from(current).atTime(time).atZone(zone).toInstant().toString())
					showTime = false
				}) { Text("OK") }
			},
			dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
			text = { TimePicker(state = state) }
		)
	}
}
