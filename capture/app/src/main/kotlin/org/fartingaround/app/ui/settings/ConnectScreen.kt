package org.fartingaround.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.fartingaround.app.captureApp
import org.fartingaround.app.data.normalizeSiteAddress
import org.fartingaround.app.ui.components.ErrorText
import org.fartingaround.app.ui.components.Muted

/**
 * Shown in place of Create's screens until the app knows which site it writes
 * to. The app ships with no site built in: every install points at its
 * owner's own. Connecting checks the address and token against the site
 * before saving, so a typo shows up here rather than at the first sync.
 */
@Composable
fun ConnectScreen() {
	val app = LocalContext.current.captureApp
	val scope = rememberCoroutineScope()

	var address by rememberSaveable { mutableStateOf("") }
	var token by rememberSaveable { mutableStateOf("") }
	var busy by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }

	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp)
	) {
		Text("Connect your site", style = MaterialTheme.typography.headlineLarge)
		Muted("Create writes stories to a fartingaround site you run. Enter its address and its capture token. Both can be changed later in Settings.")

		OutlinedTextField(
			value = address, onValueChange = { address = it; error = null },
			label = { Text("Site address") }, placeholder = { Text("https://example.org") },
			singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
			enabled = !busy, modifier = Modifier.fillMaxWidth()
		)
		OutlinedTextField(
			value = token, onValueChange = { token = it; error = null },
			label = { Text("Capture token") }, singleLine = true,
			visualTransformation = PasswordVisualTransformation(),
			enabled = !busy, modifier = Modifier.fillMaxWidth()
		)
		Muted("The token is the site's CAPTURE_API_TOKEN. On the server, `fa env get CAPTURE_API_TOKEN` prints it.")

		error?.let { ErrorText(it) }

		Button(
			enabled = !busy && address.isNotBlank() && token.isNotBlank(),
			onClick = {
				scope.launch {
					busy = true; error = null
					val base = normalizeSiteAddress(address)
					if (base == null) {
						error = "That doesn't look like a site address."
					} else {
						try {
							val tok = token.trim()
							app.api.session(base, tok).countStories()
							// Saving flips the settings flow, and Create's own screens take over.
							app.settings.save(app.settings.load().copy(apiBase = base, apiToken = tok))
						} catch (e: Exception) {
							error = "Couldn't connect to $base: ${e.message}"
						}
					}
					busy = false
				}
			},
			modifier = Modifier.fillMaxWidth()
		) { Text(if (busy) "Connecting…" else "Connect") }

		Muted("Only here to read? Switch to Consume at the top. The reader doesn't need a site.")
	}
}
