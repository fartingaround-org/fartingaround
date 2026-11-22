package fartingaround.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fartingaround.capture.data.CaptureSettings
import fartingaround.capture.ui.AppNav
import fartingaround.capture.ui.theme.CaptureTheme

class MainActivity : ComponentActivity() {

	/** A draft id handed over by ShareActivity (or a notification) to open directly. */
	private var openDraftId by mutableStateOf<String?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		// Only on a fresh start: after a rotation the same launch intent is still
		// attached, and re-reading it would pull the user back to that draft.
		if (savedInstanceState == null) openDraftId = intent?.getStringExtra(EXTRA_DRAFT_ID)

		setContent {
			val settings by captureApp.settings.flow.collectAsStateWithLifecycle(initialValue = CaptureSettings())
			CaptureTheme(theme = settings.theme) {
				AppNav(
					openDraftId = openDraftId,
					onOpenedDraft = { openDraftId = null }
				)
			}
		}
	}

	// singleTop: a share while we're already running arrives here instead of onCreate.
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		intent.getStringExtra(EXTRA_DRAFT_ID)?.let { openDraftId = it }
	}

	companion object {
		const val EXTRA_DRAFT_ID = "draftId"
	}
}
