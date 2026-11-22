package fartingaround.capture.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.core.content.ContextCompat
import fartingaround.capture.capture.AudioRecorder
import fartingaround.capture.ui.components.Eyebrow
import fartingaround.capture.ui.components.fmtDuration
import fartingaround.capture.ui.theme.LocalCaptureColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * A text field that owns its text while you type and commits to the store
 * after a short pause — the editor's autosave. [key] resets the local text
 * when the thing being edited changes (another block, another photo).
 */
@OptIn(FlowPreview::class)
@Composable
fun DebouncedField(
	key: Any,
	value: String,
	label: String,
	onCommit: (String) -> Unit,
	modifier: Modifier = Modifier,
	placeholder: String? = null,
	singleLine: Boolean = true,
	minLines: Int = 1
) {
	var text by rememberSaveable(key) { mutableStateOf(value) }
	LaunchedEffect(key) {
		snapshotFlow { text }.drop(1).debounce(400).collect { if (it != value) onCommit(it) }
	}
	OutlinedTextField(
		value = text, onValueChange = { text = it },
		label = { Eyebrow(label) },
		placeholder = placeholder?.let { { Text(it) } },
		singleLine = singleLine, minLines = minLines,
		keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
		modifier = modifier
	)
}

/**
 * Record-audio control: asks for the microphone, shows elapsed time while
 * recording, hands the finished clip back. Used by the capture bar and by a
 * dump photo's "record caption".
 */
@Composable
fun RecordButton(label: String = "🎙 Record", modifier: Modifier = Modifier, onRecorded: (AudioRecorder.Result) -> Unit, onError: (String) -> Unit) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val c = LocalCaptureColors.current
	val recorder = remember { AudioRecorder(context) }
	var recording by remember { mutableStateOf(false) }
	var elapsedMs by remember { mutableLongStateOf(0L) }
	DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.cancel() } }
	LaunchedEffect(recording) {
		val started = System.currentTimeMillis()
		while (recording) { elapsedMs = System.currentTimeMillis() - started; delay(250) }
	}
	fun start() {
		try { recorder.start(); recording = true } catch (e: Exception) { onError("Couldn't start recording: ${e.message}") }
	}
	val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if (granted) start() else onError("Microphone permission denied.")
	}
	if (recording) {
		Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(
				onClick = {
					recording = false
					scope.launch { try { onRecorded(recorder.stop()) } catch (e: Exception) { onError("Recording failed: ${e.message}") } }
				},
				colors = ButtonDefaults.buttonColors(containerColor = c.wax),
				modifier = Modifier.weight(1f)
			) { Text("■ Stop ${fmtDuration(elapsedMs)}") }
			OutlinedButton(onClick = { recorder.cancel(); recording = false }) { Text("Cancel") }
		}
	} else {
		OutlinedButton(
			onClick = {
				val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
				if (granted) start() else mic.launch(Manifest.permission.RECORD_AUDIO)
			},
			modifier = modifier
		) { Text(label) }
	}
}
