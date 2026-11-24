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
import androidx.compose.material3.OutlinedButton
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import org.fartingaround.app.captureApp
import org.fartingaround.app.data.normalizeSiteAddress
import org.fartingaround.app.data.parseConnectLink
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
	val context = LocalContext.current

	// Checks the address and token against the site, and saves them only if the site accepts them.
	fun connect(base: String, tok: String) {
		scope.launch {
			busy = true; error = null
			try {
				app.api.session(base, tok).countStories()
				// Saving flips the settings flow, and Create's own screens take over.
				app.settings.save(app.settings.load().copy(apiBase = base, apiToken = tok))
			} catch (e: Exception) {
				error = "Couldn't connect to $base: ${e.message}"
			}
			busy = false
		}
	}

	// The site shows a code under Admin → Settings → Phone app. Google's scanner
	// screen opens, reads one code and comes back with its text.
	fun scan() {
		error = null
		val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
		GmsBarcodeScanning.getClient(context, options).startScan()
			.addOnSuccessListener { code ->
				val link = parseConnectLink(code.rawValue.orEmpty())
				if (link == null) {
					error = "That isn't a connect code. On your site, open Admin, then Settings, then Phone app, and choose Show the code."
				} else {
					address = link.site; token = link.token
					connect(link.site, link.token)
				}
			}
			.addOnFailureListener { e -> error = "Couldn't open the scanner (${e.message ?: "it needs Google Play services"}). Type the address and token instead." }
		// Backing out of the scanner is not an error: nothing to say.
	}

	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp)
	) {
		Text("Connect your site", style = MaterialTheme.typography.headlineLarge)
		Muted("Write posts stories to a FartingAround site you run. The quick way: on your site, open Admin, then Settings, then Phone app, show the code there and scan it.")
		Button(enabled = !busy, onClick = { scan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan the code") }
		Muted("Or type them in. Both can be changed later in Settings.")

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

		OutlinedButton(
			enabled = !busy && address.isNotBlank() && token.isNotBlank(),
			onClick = {
				val base = normalizeSiteAddress(address)
				if (base == null) error = "That doesn't look like a site address."
				else connect(base, token.trim())
			},
			modifier = Modifier.fillMaxWidth()
		) { Text(if (busy) "Connecting…" else "Connect") }

		Muted("Only here to read? Switch to Read at the top. It doesn't need a site.")
	}
}
