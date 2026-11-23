package org.fartingaround.app.ui.drafts

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.fartingaround.app.captureApp
import org.fartingaround.app.consume.Story
import org.fartingaround.app.data.CaptureSettings
import org.fartingaround.app.ui.components.Eyebrow
import org.fartingaround.app.ui.components.Muted
import org.fartingaround.app.ui.consume.StoryView
import kotlinx.coroutines.flow.map

/**
 * The story as a reader of the app will see it: the same enhanced view Consume
 * uses, fed from the phone's working copy instead of a feed. Photos and
 * recordings that have not been uploaded yet are shown from this phone.
 */
@Composable
fun StoryPreviewScreen(localId: String, onBack: () -> Unit) {
	val app = LocalContext.current.captureApp
	val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = CaptureSettings())
	val story by remember(localId) { app.storyStore.stories.map { it[localId] } }.collectAsStateWithLifecycle(initialValue = null)
	LaunchedEffect(localId) { app.storyStore.ensureLoaded() }

	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth().padding(end = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			TextButton(onClick = onBack) { Text("← Edit", style = MaterialTheme.typography.labelMedium) }
			Eyebrow("Preview")
		}
		val s = story
		if (s == null) {
			Muted("Loading…", Modifier.padding(16.dp))
			return@Column
		}
		val preview = remember(s.doc, s.pending, s.localFiles, s.slug, settings.base, settings.apiToken) {
			val local = buildMap {
				// Not uploaded yet: the document refers to these by placeholder
				// (photos) or by the pending item's own id (audio and video blocks).
				for (p in s.pending) {
					val f = app.blobStore.file(p.blobId)
					if (f.isFile) { val uri = Uri.fromFile(f).toString(); put(p.placeholder, uri); put(p.id, uri) }
				}
				// Uploaded, but the bytes were kept here: play them locally, since
				// the site will not serve a draft's audio to an anonymous player.
				for ((mediaId, blobId) in s.localFiles) {
					val f = app.blobStore.file(blobId)
					if (f.isFile) put(mediaId, Uri.fromFile(f).toString())
				}
			}
			// Uploaded media of a draft is only served to the phone's own token. It is
			// sent to this site's audio/video addresses and nowhere else.
			val headers = if (settings.apiToken.isNotBlank()) mapOf("Authorization" to "Bearer ${settings.apiToken}") else emptyMap()
			Story.preview(s.doc, settings.base, s.slug, local, headers)
		}
		if (preview == null) {
			Muted("Nothing to preview yet.", Modifier.padding(16.dp))
		} else {
			StoryView(
				preview,
				// Blank stays blank: the preview shows a note the way readers will see it.
				title = s.title,
				feedTitle = settings.base.removePrefix("https://").removePrefix("http://"),
				modifier = Modifier.fillMaxSize()
			)
		}
	}
}
