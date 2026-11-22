package fartingaround.capture.ui.drafts

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fartingaround.capture.captureApp
import fartingaround.capture.model.LocalStory
import fartingaround.capture.model.SyncState
import fartingaround.capture.model.label
import fartingaround.capture.model.mediaCounts
import fartingaround.capture.model.notes
import fartingaround.capture.model.str
import fartingaround.capture.ui.components.fmtDay
import fartingaround.capture.ui.components.Eyebrow
import fartingaround.capture.ui.components.ErrorText
import fartingaround.capture.ui.components.Muted
import fartingaround.capture.ui.components.SyncBadge
import fartingaround.capture.ui.components.rememberSyncLauncher
import fartingaround.capture.ui.theme.LocalCaptureColors
import kotlinx.coroutines.launch

/** Create mode home: the stories on this phone, drafts first. */
@Composable
fun StoriesListScreen(onOpen: (String) -> Unit) {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()
	val stories by remember { app.storyStore.sorted() }.collectAsStateWithLifecycle(initialValue = emptyList())
	val sync = rememberSyncLauncher()
	var pulling by remember { mutableStateOf(false) }
	var pullError by remember { mutableStateOf<String?>(null) }

	fun pull(includePublished: Boolean, quiet: Boolean = false) {
		scope.launch {
			pulling = true; if (!quiet) pullError = null
			try { app.syncEngine.pullDrafts(includePublished) } catch (e: Exception) { if (!quiet) pullError = e.message ?: "Couldn't reach the site" }
			pulling = false
		}
	}

	// Drafts started on the site (or in /admin) should just be here: refresh
	// quietly every time the list opens, when a token is configured.
	LaunchedEffect(Unit) {
		app.storyStore.ensureLoaded()
		if (app.settings.load().apiToken.isNotBlank()) pull(includePublished = false, quiet = true)
	}

	val drafts = stories.filter { it.isDraft }
	val published = stories.filterNot { it.isDraft }

	Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
		Row(
			Modifier.fillMaxWidth().padding(vertical = 12.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text("Stories", style = MaterialTheme.typography.headlineLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				if (stories.any { it.isDirty }) {
					OutlinedButton(onClick = { sync(stories.filter { it.isDirty }.map { it.localId }) }) { Text("Sync all") }
				}
				// Drafts refresh on their own; this also brings published stories down for editing.
				OutlinedButton(enabled = !pulling, onClick = { pull(includePublished = true) }) { Text(if (pulling) "…" else "↓ Site") }
				Button(onClick = {
					scope.launch { onOpen(app.storyStore.save(LocalStory.empty()).localId) }
				}) { Text("+ New") }
			}
		}
		pullError?.let { ErrorText(it) }

		if (stories.isEmpty()) {
			Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Text("Nothing here yet.", style = MaterialTheme.typography.headlineSmall)
				Muted("Tap + New, share photos here from any app, or ↓ Site to pull your stories down.")
			}
		} else {
			LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
				items(drafts, key = { it.localId }) { s -> StoryCard(s, onOpen = { onOpen(s.localId) }, onSync = { sync(listOf(s.localId)) }) }
				if (published.isNotEmpty()) {
					item { Eyebrow("Published", Modifier.padding(top = 12.dp)) }
					items(published, key = { it.localId }) { s -> StoryCard(s, onOpen = { onOpen(s.localId) }, onSync = { sync(listOf(s.localId)) }) }
				}
			}
		}
	}
}

@Composable
private fun StoryCard(s: LocalStory, onOpen: () -> Unit, onSync: () -> Unit) {
	val c = LocalCaptureColors.current
	val counts = s.doc.mediaCounts()
	val notes = s.doc.notes
	// No title: called by a caption or its first line, set quieter than a title.
	val titled = s.title.isNotBlank()
	val label = s.doc.label

	Row(
		Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(4.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
			.clickable(onClick = onOpen),
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(Modifier.weight(1f).padding(14.dp)) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
				Text(
					label,
					style = if (titled) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
					maxLines = 2, overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f)
				)
				Spacer(Modifier.width(8.dp))
				if (s.conflict != null) {
					Text("CONFLICT", style = MaterialTheme.typography.labelSmall, color = c.wax)
				} else {
					SyncBadge(s.docSync, dirty = s.isDirty)
				}
			}
			// Not when the label above is already those same words.
			if (notes.isNotBlank() && (titled || !notes.trim().startsWith(label.trimEnd('…')))) {
				Text(
					notes, style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2, overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 4.dp)
				)
			}
			// Media counts sit in the text as small line icons, so they take the
			// text's colour (and astro's red) instead of being colour emoji.
			val meta = buildAnnotatedString {
				var first = true
				fun sep() { if (!first) append(" · "); first = false }
				sep(); append(fmtDay(s.doc.str("date") ?: ""))
				s.doc.str("location")?.takeIf { it.isNotBlank() }?.let { sep(); append(it) }
				if (counts.photos > 0) { sep(); appendInlineContent("photo", "photos"); append(" ${counts.photos}") }
				if (counts.audio > 0) { sep(); appendInlineContent("audio", "recordings"); append(" ${counts.audio}") }
				if (counts.video > 0) { sep(); appendInlineContent("video", "videos"); append(" ${counts.video}") }
				if (!s.isDraft) { sep(); append("published") }
				if (s.isPrivate) { sep(); append("private") }
			}
			Text(meta, inlineContent = metaIcons(c.pencil), style = MaterialTheme.typography.bodySmall, color = c.pencil, modifier = Modifier.padding(top = 6.dp))
		}
		// Only rows with something unsent offer a Sync; "Sync all" covers the rest.
		if (s.isDirty && s.conflict == null) {
			Box(Modifier.padding(end = 6.dp)) {
				TextButton(onClick = onSync, enabled = s.docSync != SyncState.SYNCING) {
					Text(if (s.docSync == SyncState.SYNCING) "…" else "Sync", style = MaterialTheme.typography.labelMedium)
				}
			}
		}
	}
}

/** The inline icons the meta line refers to by id. */
private fun metaIcons(tint: Color): Map<String, InlineTextContent> {
	fun icon(image: ImageVector) = InlineTextContent(Placeholder(14.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)) {
		Icon(image, contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
	}
	return mapOf("photo" to icon(Icons.Filled.PhotoCamera), "audio" to icon(Icons.Filled.Mic), "video" to icon(Icons.Filled.Videocam))
}
