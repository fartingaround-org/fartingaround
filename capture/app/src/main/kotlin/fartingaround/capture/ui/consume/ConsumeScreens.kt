package fartingaround.capture.ui.consume

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import fartingaround.capture.captureApp
import fartingaround.capture.consume.FeedWithCounts
import fartingaround.capture.consume.Item
import fartingaround.capture.consume.Story
import fartingaround.capture.ui.components.ErrorText
import fartingaround.capture.ui.components.Eyebrow
import fartingaround.capture.ui.components.Muted
import fartingaround.capture.ui.components.fmtPublished
import fartingaround.capture.ui.theme.LocalCaptureColors
import fartingaround.capture.ui.theme.imageFilter
import kotlinx.coroutines.launch
import java.time.Instant

// ── Timeline ────────────────────────────────────────────────────────────────

/**
 * All items newest-first, or only unread. `feedUrl` narrows to one feed.
 * No ViewModel: the repository's Flows are the state, collected here.
 */
@Composable
fun TimelineScreen(feedUrl: String? = null, starredOnly: Boolean = false, onOpen: (String) -> Unit) {
	val context = LocalContext.current
	val repo = context.captureApp.consume
	val scope = rememberCoroutineScope()
	var unreadOnly by rememberSaveable { mutableStateOf(feedUrl == null && !starredOnly) }

	val flow = remember(feedUrl, starredOnly, unreadOnly) {
		when {
			starredOnly -> repo.starred()
			feedUrl != null -> repo.itemsFor(feedUrl)
			else -> repo.timeline(unreadOnly)
		}
	}
	val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
	val refreshing by repo.refreshing.collectAsStateWithLifecycle()
	val feedTitles by remember { repo.feeds }.collectAsStateWithLifecycle(initialValue = emptyList())
	val titleOf = remember(feedTitles) { feedTitles.associate { it.url to it.title } }
	val baseOf = remember(feedTitles) { feedTitles.associate { it.url to it.enhancedBase } }
	val shown = if (feedUrl != null && unreadOnly) items.filter { !it.read } else items

	// The side padding is on each part, not the whole screen, so a note's content can run edge to edge.
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			Text(
				when {
					starredOnly -> "Saved"
					feedUrl != null -> titleOf[feedUrl] ?: "Feed"
					else -> "Timeline"
				},
				style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
			)
			if (!starredOnly) {
				TextButton(onClick = { scope.launch { if (feedUrl != null) repo.markFeedRead(feedUrl) else repo.markAllRead() } }) { Text("Mark read") }
				TextButton(enabled = !refreshing, onClick = { scope.launch { if (feedUrl != null) repo.refresh(feedUrl) else repo.refreshAll() } }) {
					Text(if (refreshing) "…" else "Refresh")
				}
			}
		}
		if (!starredOnly) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
				FilterChip(selected = unreadOnly, onClick = { unreadOnly = true }, label = { Text("Unread") })
				FilterChip(selected = !unreadOnly, onClick = { unreadOnly = false }, label = { Text("All") })
			}
		}
		if (shown.isEmpty()) {
			Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Text(if (unreadOnly && !starredOnly) "All caught up." else "Nothing here yet.", style = MaterialTheme.typography.headlineSmall)
				if (feedTitles.isEmpty()) Muted("Add a feed on the Feeds tab, or import an OPML file.")
			}
		} else {
			LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
				items(shown, key = { it.id }) { item ->
					// A story with no title from a ✦ feed is a note: its content goes right here.
					val base = baseOf[item.feedUrl]
					val note = remember(item.id, item.extensionJson, base) {
						if (Story.isNoteTitle(item.title) && item.extensionJson != null && base != null) Story.parse(item.extensionJson, base, item.feedUrl)?.takeIf { it.hasInlineLead() } else null
					}
					if (note != null) NoteRow(item, note, titleOf[item.feedUrl]) { onOpen(item.id) }
					else Box(Modifier.padding(horizontal = 16.dp)) { ItemRow(item, titleOf[item.feedUrl]) { onOpen(item.id) } }
				}
			}
		}
	}
}

/**
 * A note in the timeline: who it is from, the content itself, then a line
 * with the date that opens the note (where its comments are).
 */
@Composable
private fun NoteRow(item: Item, story: Story, feedTitle: String?, onOpen: () -> Unit) {
	val c = LocalCaptureColors.current
	Column(Modifier.fillMaxWidth()) {
		Row(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
			Text("✦", color = c.wax, style = MaterialTheme.typography.labelSmall)
			Eyebrow(feedTitle ?: item.feedUrl)
			if (!item.read) Text("NEW", color = c.wax, style = MaterialTheme.typography.labelSmall)
		}
		NoteLead(story, onOpen)
		Row(
			Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
		) {
			Muted(listOfNotNull(fmtPublished(item.publishedAt), story.location).joinToString(" · "), Modifier.weight(1f))
			Text(if (story.blocks.size > 1) "More →" else "Open →", color = c.wax, style = MaterialTheme.typography.labelMedium)
		}
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	}
}

@Composable
private fun ItemRow(item: Item, feedTitle: String?, onOpen: () -> Unit) {
	val c = LocalCaptureColors.current
	// An item with no title (a short post from any feed) is called by its summary.
	val untitled = Story.isNoteTitle(item.title)
	Row(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
			.background(if (item.read) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant)
			.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
			.clickable(onClick = onOpen).padding(12.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
	) {
		Column(Modifier.weight(1f)) {
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
				if (item.extensionJson != null) Text("✦", color = c.wax, style = MaterialTheme.typography.labelSmall)
				Eyebrow(feedTitle ?: item.feedUrl)
			}
			if (!untitled) {
				Text(
					item.title, style = MaterialTheme.typography.titleMedium,
					fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
					maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
				)
			}
			item.summary?.takeIf { it.isNotBlank() }?.let {
				Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (untitled) 4 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
			}
			Muted(fmtPublished(item.publishedAt), Modifier.padding(top = 4.dp))
		}
		if (item.imageUrl != null) {
			AsyncImage(model = item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = imageFilter(), modifier = Modifier.size(64.dp).clip(RoundedCornerShape(3.dp)))
		}
	}
}

// ── Feeds ───────────────────────────────────────────────────────────────────

@Composable
fun FeedsScreen(onOpenFeed: (String) -> Unit) {
	val context = LocalContext.current
	val repo = context.captureApp.consume
	val scope = rememberCoroutineScope()
	val c = LocalCaptureColors.current
	val feeds by remember { repo.feedsWithCounts }.collectAsStateWithLifecycle(initialValue = emptyList())

	var input by rememberSaveable { mutableStateOf("") }
	var adding by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	var notice by remember { mutableStateOf<String?>(null) }
	var confirmRemove by remember { mutableStateOf<FeedWithCounts?>(null) }

	fun add(v: String = input.trim()) {
		if (v.isEmpty() || adding) return
		scope.launch {
			adding = true; error = null; notice = null
			try { val f = repo.subscribe(v); input = ""; notice = "Subscribed to ${f.title}" }
			catch (e: Exception) { error = e.message ?: "Couldn't add that feed" }
			finally { adding = false }
		}
	}

	// A feed handed over as a QR code. Google's scanner screen opens, reads one
	// code and comes back with its text; only a web address is acted on.
	fun scan() {
		error = null; notice = null
		val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
		GmsBarcodeScanning.getClient(context, options).startScan()
			.addOnSuccessListener { code ->
				val v = code.rawValue?.trim().orEmpty()
				if (v.startsWith("https://", ignoreCase = true) || v.startsWith("http://", ignoreCase = true)) { input = v; add(v) }
				else { error = "That code isn't a web address, so there is no feed to add." }
			}
			.addOnFailureListener { e -> error = "Couldn't open the scanner (${e.message ?: "it needs Google Play services"}). Paste the address instead." }
		// Backing out of the scanner is not an error: nothing to say.
	}

	// OPML out: the brewtuner-style "save a file wherever you like" export.
	val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
		if (uri != null) scope.launch {
			runCatching {
				val xml = repo.exportOpml()
				context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) }
				notice = "Exported ${feeds.size} feeds"
			}.onFailure { error = it.message }
		}
	}
	val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) scope.launch {
			runCatching {
				val xml = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Couldn't read file")
				val n = repo.importOpml(xml)
				notice = "Imported $n new feeds — refreshing"
				repo.refreshAll()
			}.onFailure { error = it.message }
		}
	}

	Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
		Text("Feeds", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(vertical = 12.dp))
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			OutlinedTextField(
				value = input, onValueChange = { input = it }, placeholder = { Text("Site or feed URL") }, singleLine = true,
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
				modifier = Modifier.weight(1f)
			)
			Button(onClick = { add() }, enabled = !adding && input.isNotBlank()) { Text(if (adding) "…" else "Add") }
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
			OutlinedButton(onClick = { scan() }, enabled = !adding) { Text("Scan QR") }
			OutlinedButton(onClick = { importOpml.launch(arrayOf("*/*")) }) { Text("Import OPML") }
			OutlinedButton(onClick = { exportOpml.launch("subscriptions.opml") }, enabled = feeds.isNotEmpty()) { Text("Export OPML") }
		}
		error?.let { ErrorText(it) }
		notice?.let { Muted(it) }

		if (feeds.isEmpty()) {
			Muted("Paste a site address or scan its QR code — the feed is found automatically. fartingaround-style sites get the enhanced view (✦).", Modifier.padding(top = 24.dp))
		}
		LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
			items(feeds, key = { it.url }) { f ->
				Row(
					Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
						.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
						.clickable { onOpenFeed(f.url) }.padding(12.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
				) {
					if (f.iconUrl != null) {
						AsyncImage(model = f.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = imageFilter(), modifier = Modifier.size(36.dp).clip(RoundedCornerShape(3.dp)))
					}
					Column(Modifier.weight(1f)) {
						Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
							if (f.enhanced) Text("✦", color = c.wax, style = MaterialTheme.typography.labelMedium)
							Text(f.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
						}
						// The address tells apart several feeds from one site (RSS vs JSON Feed).
						// Anything after "?" is dropped: a personal feed's link carries a reader token.
						Text(
							f.url.substringBefore('?').removePrefix("https://").removePrefix("http://"),
							style = MaterialTheme.typography.bodySmall, color = c.pencil, maxLines = 1, overflow = TextOverflow.Ellipsis
						)
						Muted(if (f.lastError != null) "Error: ${f.lastError}" else "${f.unread} unread · ${f.total} items")
					}
					TextButton(onClick = { confirmRemove = f }) { Text("Remove", color = c.pencil, style = MaterialTheme.typography.labelMedium) }
				}
			}
			if (feeds.any { it.enhanced }) {
				item("legend") { Muted("✦ Enhanced view: stories from fartingaround-style sites open natively, with photo dumps, galleries and audio.", Modifier.padding(top = 4.dp)) }
			}
		}
	}

	confirmRemove?.let { f ->
		AlertDialog(
			onDismissRequest = { confirmRemove = null },
			title = { Text("Unsubscribe from ${f.title}?") },
			text = { Text("Its items are removed from this phone.") },
			confirmButton = { TextButton(onClick = { confirmRemove = null; scope.launch { repo.unsubscribe(f.url) } }) { Text("Remove", color = c.wax) } },
			dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } }
		)
	}
}

// ── Shared item header bits used by the reader screen ───────────────────────

@Composable
fun ItemActions(item: Item, onBack: () -> Unit) {
	val context = LocalContext.current
	val repo = context.captureApp.consume
	val scope = rememberCoroutineScope()
	val c = LocalCaptureColors.current
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
		TextButton(onClick = onBack) { Text("← Back", style = MaterialTheme.typography.labelMedium) }
		Row(verticalAlignment = Alignment.CenterVertically) {
			IconButton(onClick = { scope.launch { repo.setStarred(item.id, !item.starred) } }) {
				Icon(if (item.starred) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = if (item.starred) "Unsave" else "Save", tint = if (item.starred) c.wax else c.pencil)
			}
			TextButton(onClick = { scope.launch { repo.setRead(item.id, !item.read) } }) { Text(if (item.read) "Unread" else "Read", style = MaterialTheme.typography.labelMedium) }
			if (item.url != null) {
				TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }) { Text("Open ↗", style = MaterialTheme.typography.labelMedium) }
			}
			Spacer(Modifier.width(4.dp))
		}
	}
}
