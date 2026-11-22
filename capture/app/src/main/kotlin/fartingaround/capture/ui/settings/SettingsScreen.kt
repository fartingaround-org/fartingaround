package fartingaround.capture.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fartingaround.capture.captureApp
import fartingaround.capture.data.CaptureSettings
import fartingaround.capture.data.ThemeName
import fartingaround.capture.ui.components.Eyebrow
import fartingaround.capture.ui.components.Muted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen() {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()

	val liveTheme by remember { app.settings.flow.map { it.theme } }.collectAsStateWithLifecycle(initialValue = ThemeName.AUTO)
	var s by remember { mutableStateOf<CaptureSettings?>(null) }
	LaunchedEffect(Unit) { s = app.settings.load() }
	val settings = s ?: return

	var saved by remember { mutableStateOf(false) }
	var testing by remember { mutableStateOf(false) }
	var testResult by remember { mutableStateOf<String?>(null) }

	// The author's name lives on the site, not on this phone: read it once the
	// saved site and token are known, and save it straight back there.
	var authorName by remember { mutableStateOf("") }
	var nameLoaded by remember { mutableStateOf(false) }
	var nameBusy by remember { mutableStateOf(false) }
	var nameStatus by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(Unit) {
		if (settings.apiToken.isBlank()) return@LaunchedEffect
		try {
			authorName = app.api.session(settings.base, settings.apiToken).authorName()
			nameLoaded = true
		} catch (e: Exception) {
			nameStatus = "Couldn't read your name from the site: ${e.message}"
		}
	}

	fun edit(transform: (CaptureSettings) -> CaptureSettings) {
		s = transform(settings); saved = false
	}

	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp)
	) {
		Text("Settings", style = MaterialTheme.typography.headlineLarge)

		Eyebrow("Sync target")
		OutlinedTextField(
			value = settings.apiBase, onValueChange = { v -> edit { it.copy(apiBase = v) } },
			label = { Text("API base") }, placeholder = { Text("https://fartingaround.fun") },
			singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
			modifier = Modifier.fillMaxWidth()
		)
		OutlinedTextField(
			value = settings.apiToken, onValueChange = { v -> edit { it.copy(apiToken = v) } },
			label = { Text("Capture token") }, singleLine = true,
			visualTransformation = PasswordVisualTransformation(),
			modifier = Modifier.fillMaxWidth()
		)
		val tok = settings.apiToken.trim()
		Muted(
			if (tok.isEmpty()) "The CAPTURE_API_TOKEN secret on the site. Generate one with `openssl rand -hex 32`."
			else "Token on this phone: ${tok.take(4)}…${tok.takeLast(4)} (${tok.length} chars) — compare with /admin/api/diag on the site."
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(enabled = !testing, onClick = {
				scope.launch {
					testing = true; testResult = null
					testResult = try {
						val n = app.api.session(settings.base, settings.apiToken).countStories()
						"OK — $n stories on the site (drafts included)."
					} catch (e: Exception) {
						"Failed: ${e.message}"
					}
					testing = false
				}
			}) { Text(if (testing) "Testing…" else "Test connection") }
		}
		testResult?.let { Muted(it) }

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		Eyebrow("You")
		OutlinedTextField(
			value = authorName, onValueChange = { authorName = it.take(80); nameStatus = null },
			label = { Text("Your name") }, placeholder = { Text("Author") }, singleLine = true,
			enabled = nameLoaded && !nameBusy,
			modifier = Modifier.fillMaxWidth()
		)
		Muted(
			if (settings.apiToken.isBlank()) "Set the capture token above and save, then come back to set your name."
			else "Shown on your own comments, next to the \"author\" mark. It is kept on the site, so it's the same name everywhere. Left blank, your comments are signed \"Author\"."
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(enabled = nameLoaded && !nameBusy, onClick = {
				scope.launch {
					nameBusy = true; nameStatus = null
					nameStatus = try {
						authorName = app.api.session(settings.base, settings.apiToken).setAuthorName(authorName.trim())
						"Saved on the site."
					} catch (e: Exception) {
						"Failed: ${e.message}"
					}
					nameBusy = false
				}
			}) { Text(if (nameBusy) "Saving…" else "Save name") }
		}
		nameStatus?.let { Muted(it) }

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		Eyebrow("Transcription (ElevenLabs)")
		OutlinedTextField(
			value = settings.elevenLabsApiKey, onValueChange = { v -> edit { it.copy(elevenLabsApiKey = v) } },
			label = { Text("API key") }, singleLine = true,
			visualTransformation = PasswordVisualTransformation(),
			modifier = Modifier.fillMaxWidth()
		)
		OutlinedTextField(
			value = settings.elevenLabsModel, onValueChange = { v -> edit { it.copy(elevenLabsModel = v) } },
			label = { Text("Model") }, placeholder = { Text("scribe_v1") }, singleLine = true,
			modifier = Modifier.fillMaxWidth()
		)
		Muted("Your key stays on this phone; audio goes straight from the device to ElevenLabs.")

		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

		Eyebrow("Theme")
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			// Theme applies immediately, no Save needed.
			FilterChip(selected = liveTheme == ThemeName.AUTO, onClick = { scope.launch { app.settings.setTheme(ThemeName.AUTO) } }, label = { Text("Auto") })
			FilterChip(selected = liveTheme == ThemeName.PAPER, onClick = { scope.launch { app.settings.setTheme(ThemeName.PAPER) } }, label = { Text("Paper") })
			FilterChip(selected = liveTheme == ThemeName.INK, onClick = { scope.launch { app.settings.setTheme(ThemeName.INK) } }, label = { Text("Ink") })
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			FilterChip(selected = liveTheme == ThemeName.ASTRO, onClick = { scope.launch { app.settings.setTheme(ThemeName.ASTRO) } }, label = { Text("Night vision") })
		}
		Muted("Auto follows the phone: paper by day, ink (dark) by night.")
		Muted("Night vision turns everything dim red, photos included, so the screen doesn't wreck dark-adapted eyes.")

		Button(onClick = { scope.launch { app.settings.save(settings); saved = true } }, modifier = Modifier.fillMaxWidth()) {
			Text(if (saved) "Saved ✓" else "Save")
		}
	}
}
