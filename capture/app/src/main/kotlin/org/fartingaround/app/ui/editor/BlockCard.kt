package org.fartingaround.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.fartingaround.app.captureApp
import org.fartingaround.app.data.BlobStore
import org.fartingaround.app.model.LocalStory
import org.fartingaround.app.model.PendingMedia
import org.fartingaround.app.model.StoryDoc
import org.fartingaround.app.model.SyncState
import org.fartingaround.app.model.obj
import org.fartingaround.app.model.objs
import org.fartingaround.app.model.long
import org.fartingaround.app.model.removeDumpPhoto
import org.fartingaround.app.model.str
import org.fartingaround.app.model.updateBlock
import org.fartingaround.app.model.updateDumpPhoto
import org.fartingaround.app.model.with
import org.fartingaround.app.sync.Transcribe
import org.fartingaround.app.ui.components.ErrorText
import org.fartingaround.app.ui.components.Eyebrow
import org.fartingaround.app.ui.components.Muted
import org.fartingaround.app.ui.components.fmtDuration
import org.fartingaround.app.ui.theme.LocalCaptureColors
import org.fartingaround.app.ui.theme.imageFilter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * One block on the story editor. Edits are expressed as document transforms
 * handed to [onEdit]; new recordings (a dump photo's spoken caption) go
 * through [onPending] so the editor can register the bytes for upload.
 */
@Composable
fun BlockCard(
	story: LocalStory,
	block: JsonObject,
	imgBase: String,
	onEdit: ((JsonObject) -> JsonObject) -> Unit,
	onPending: (PendingMedia) -> Unit,
	onSetCover: (String) -> Unit,
	onRemove: () -> Unit,
	onMove: (Int) -> Unit
) {
	val c = LocalCaptureColors.current
	val blobs = LocalContext.current.captureApp.blobStore
	val type = block.str("type") ?: "?"
	val id = block.str("id") ?: return
	val label = when (type) {
		"text" -> "Text"; "photo" -> "Photo"; "dump" -> "Photo dump"; "audio" -> "Audio"; "video" -> "Video"
		"gallery" -> "Astro gallery (view only)"; "diptych" -> "Diptych"; "pull" -> "Pull quote"; "aside" -> "Aside"; "divider" -> "Divider"
		"heading" -> "Heading"; "code" -> "Code"
		else -> type
	}

	Column(
		Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(4.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
			.padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			Eyebrow(label)
			Row {
				TextButton(onClick = { onMove(-1) }) { Text("↑") }
				TextButton(onClick = { onMove(1) }) { Text("↓") }
				TextButton(onClick = onRemove) { Text("Remove", color = c.wax) }
			}
		}

		when (type) {
			"text", "pull", "aside" -> DebouncedField(
				key = id, value = block.str("body") ?: "", label = if (type == "text") "Body" else label,
				singleLine = false, minLines = if (type == "text") 4 else 2, modifier = Modifier.fillMaxWidth(),
				onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("body", v) } } }
			)
		}
		// An aside may carry a short label above it ("Note", "Good to know").
		if (type == "aside") {
			DebouncedField(key = "$id-label", value = block.str("label") ?: "", label = "Label above it (optional)", modifier = Modifier.fillMaxWidth(),
				onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("label", v.ifBlank { null }) } } })
		}
		when (type) {
			"text", "pull", "aside" -> {}
			"heading" -> DebouncedField(
				key = id, value = block.str("body") ?: "", label = "Section title", modifier = Modifier.fillMaxWidth(),
				onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("body", v) } } }
			)
			"code" -> {
				DebouncedField(
					key = id, value = block.str("body") ?: "", label = "Shown exactly as typed",
					singleLine = false, minLines = 3, modifier = Modifier.fillMaxWidth(),
					onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("body", v) } } }
				)
				DebouncedField(key = "$id-cap", value = block.str("caption") ?: "", label = "Note under it", modifier = Modifier.fillMaxWidth(),
					onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("caption", v.ifBlank { null }) } } })
			}
			"photo" -> {
				AsyncImage(
					model = imageModel(story, block.str("imageId"), imgBase, 720, blobs),
					contentDescription = block.str("caption"), contentScale = ContentScale.Fit, colorFilter = imageFilter(),
					modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f).clip(RoundedCornerShape(2.dp))
				)
				DebouncedField(key = "$id-cap", value = block.str("caption") ?: "", label = "Caption", modifier = Modifier.fillMaxWidth(),
					onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("caption", v.ifBlank { null }) } } })
				CoverAction(story, block.str("imageId"), onSetCover)
			}
			"dump" -> DumpBody(story, block, imgBase, onEdit, onPending, onSetCover)
			"audio" -> AudioBody(story, id, block, imgBase, onEdit)
			"video" -> {
				val local = localFile(story, block.str("r2Key"), blobs)
				if (local != null) {
					var playing by remember { mutableStateOf(false) }
					Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surface).clickable { playing = true }, contentAlignment = Alignment.Center) {
						AsyncImage(model = local, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(160.dp))
						Text("▶", style = MaterialTheme.typography.headlineLarge)
					}
					if (playing) VideoDialog(file = local, onDismiss = { playing = false })
				} else Muted("Video on the site · ${fmtDuration(block.long("durationMs"))}")
				DebouncedField(key = "$id-cap", value = block.str("caption") ?: "", label = "Caption", modifier = Modifier.fillMaxWidth(),
					onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("caption", v.ifBlank { null }) } } })
			}
			"divider" -> Muted(listOfNotNull(block.str("place"), block.str("date")).joinToString(" · ").ifBlank { "— · —" })
			else -> Muted("Edit this block on the site.")
		}
	}
}

// ── Photo dump ───────────────────────────────────────────────────────────────

@Composable
private fun DumpBody(story: LocalStory, block: JsonObject, imgBase: String, onEdit: ((JsonObject) -> JsonObject) -> Unit, onPending: (PendingMedia) -> Unit, onSetCover: (String) -> Unit) {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()
	val c = LocalCaptureColors.current
	val blobs = app.blobStore
	val id = block.str("id")!!
	val photos = block.objs("photos")
	var selected by remember(id) { mutableIntStateOf(-1) }
	var error by remember { mutableStateOf<String?>(null) }
	if (selected >= photos.size) selected = -1

	Muted("${photos.size} ${if (photos.size == 1) "photo" else "photos"} · tap one to caption it")
	LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		itemsIndexed(photos, key = { i, p -> (p.str("imageId") ?: "") + i }) { i, p ->
			Box(
				Modifier
					.size(72.dp)
					.clip(RoundedCornerShape(2.dp))
					.border(if (selected == i) 2.dp else 0.dp, if (selected == i) c.wax else MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
					.clickable { selected = if (selected == i) -1 else i }
			) {
				AsyncImage(model = imageModel(story, p.str("imageId"), imgBase, 240, blobs), contentDescription = null, contentScale = ContentScale.Crop, colorFilter = imageFilter(), modifier = Modifier.size(72.dp))
				if (p.obj("audio") != null) Text("🔊", modifier = Modifier.align(Alignment.TopStart).padding(2.dp))
				if (p.str("caption") != null) Text("¶", modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp), color = MaterialTheme.colorScheme.onSurface)
			}
		}
	}

	if (selected >= 0) {
		val p = photos[selected]
		val audio = p.obj("audio")
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			AsyncImage(
				model = imageModel(story, p.str("imageId"), imgBase, 720, blobs), contentDescription = null,
				contentScale = ContentScale.Fit, colorFilter = imageFilter(), modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f)
			)
			DebouncedField(key = "$id-$selected", value = p.str("caption") ?: "", label = "Caption · photo ${selected + 1}", modifier = Modifier.fillMaxWidth(),
				onCommit = { v -> onEdit { it.updateDumpPhoto(id, selected) { ph -> ph.with("caption", v.ifBlank { null }) } } })

			// Spoken caption: recorded here attaches to this photo directly.
			if (audio == null) {
				RecordButton(label = "🎙 Record a spoken caption", modifier = Modifier.fillMaxWidth(), onError = { error = it }, onRecorded = { r ->
					scope.launch {
						val blobId = app.blobStore.adoptFile(r.file)
						val mediaId = org.fartingaround.app.model.newBlockId("audio")
						onPending(PendingMedia(mediaId, "audio", blobId, r.mimeType, r.durationMs))
						onEdit { it.updateDumpPhoto(id, selected) { ph -> ph.with("audio", StoryDoc.photoAudio(mediaId, PendingMedia.placeholderFor(mediaId), r.mimeType, r.durationMs)) } }
					}
				})
			} else {
				val aid = audio.str("id") ?: ""
				Eyebrow("Spoken caption · ${fmtDuration(audio.long("durationMs"))}")
				val local = localFile(story, audio.str("key"), blobs) ?: story.localFiles[aid]?.let { app.blobStore.file(it) }?.takeIf { it.isFile }
				when {
					local != null -> AudioPlayer(file = local)
					!story.isDraft && story.slug != null -> AudioPlayer(uri = android.net.Uri.parse("$imgBase/api/stories/${story.slug}/audio/$aid"))
					else -> Muted("On the site (plays once published)")
				}
				TranscribeRow(story, aid, local, audio.str("mimeType") ?: "audio/mp4", audio.str("transcript")) { res ->
					onEdit { it.updateDumpPhoto(id, selected) { ph -> ph.with("audio", (ph.obj("audio") ?: audio).with("transcript", res.text).with("words", res.wordsJson()).with("transcriptModel", res.model)) } }
				}
				audio.str("transcript")?.let { t -> Text(t, style = MaterialTheme.typography.bodyMedium) }
				TextButton(onClick = { onEdit { it.updateDumpPhoto(id, selected) { ph -> JsonObject(ph - "audio") } } }) { Text("Remove spoken caption", color = c.wax) }
			}
			CoverAction(story, p.str("imageId"), onSetCover)
			TextButton(onClick = { onEdit { it.removeDumpPhoto(id, selected) }; selected = -1 }) { Text("Remove photo from dump", color = c.wax) }
		}
	}
	error?.let { ErrorText(it) }

	DebouncedField(key = "$id-dcap", value = block.str("caption") ?: "", label = "Dump caption", modifier = Modifier.fillMaxWidth(),
		onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("caption", v.ifBlank { null }) } } })
}

// ── Cover ────────────────────────────────────────────────────────────────────

/** Make a photo already in the story its cover, or say that it is. The photo stays where it is. */
@Composable
private fun CoverAction(story: LocalStory, imageId: String?, onSetCover: (String) -> Unit) {
	if (imageId.isNullOrBlank()) return
	if (story.doc.str("coverImageId") == imageId) Muted("This is the cover photo.")
	else TextButton(onClick = { onSetCover(imageId) }) { Text("Use as cover photo") }
}

// ── Audio block ──────────────────────────────────────────────────────────────

@Composable
private fun AudioBody(story: LocalStory, id: String, block: JsonObject, imgBase: String, onEdit: ((JsonObject) -> JsonObject) -> Unit) {
	val app = LocalContext.current.captureApp
	val local = localFile(story, block.str("r2Key"), app.blobStore) ?: story.localFiles[id]?.let { app.blobStore.file(it) }?.takeIf { it.isFile }
	when {
		local != null -> AudioPlayer(file = local)
		!story.isDraft && story.slug != null -> AudioPlayer(uri = android.net.Uri.parse("$imgBase/api/stories/${story.slug}/audio/$id"))
		else -> Muted("On the site (plays once published)")
	}
	Muted(fmtDuration(block.long("durationMs")))
	block.str("transcript")?.let { t ->
		Eyebrow("Transcript (${block.str("transcriptModel") ?: "?"})")
		Text(t, style = MaterialTheme.typography.bodyMedium)
	}
	TranscribeRow(story, id, local, block.str("mimeType") ?: "audio/mp4", block.str("transcript")) { res ->
		onEdit { it.updateBlock(id) { b -> b.with("transcript", res.text).with("words", res.wordsJson()).with("transcriptModel", res.model) } }
	}
	DebouncedField(key = "$id-cap", value = block.str("caption") ?: "", label = "Caption", modifier = Modifier.fillMaxWidth(),
		onCommit = { v -> onEdit { it.updateBlock(id) { b -> b.with("caption", v.ifBlank { null }) } } })
}

/** "Transcribe with ElevenLabs" using the phone's own key; pulls the clip back from the site when it's no longer on the device. */
@Composable
private fun TranscribeRow(story: LocalStory, mediaId: String, local: File?, mimeType: String, existing: String?, onResult: (Transcribe.Result) -> Unit) {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()
	var busy by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	OutlinedButton(enabled = !busy, onClick = {
		scope.launch {
			busy = true; error = null
			try {
				val settings = app.settings.load()
				val file = local ?: run {
					val slug = story.slug ?: throw IllegalStateException("Sync first — the clip isn't on this phone any more.")
					if (settings.apiToken.isBlank()) throw IllegalStateException("No capture token set. Open Settings.")
					val tmp = File(context.cacheDir, "transcribe-$mediaId")
					app.api.session(settings.base, settings.apiToken).fetchAudio(slug, mediaId, tmp)
					tmp
				}
				val result = Transcribe.run(app.api.http, settings, file, mimeType)
				if (file !== local) file.delete()
				onResult(result)
			} catch (e: Exception) {
				error = e.message ?: "Transcription failed"
			} finally {
				busy = false
			}
		}
	}) { Text(if (busy) "Transcribing…" else if (existing == null) "Transcribe with ElevenLabs" else "Re-transcribe") }
	error?.let { ErrorText(it) }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/** Where to load an image from: the pending local file, or the site's resizer. */
internal fun imageModel(story: LocalStory, imageId: String?, imgBase: String, width: Int, blobs: BlobStore): Any? {
	if (imageId == null) return null
	localFile(story, imageId, blobs)?.let { return it }
	return "$imgBase/img/$imageId/w=$width,q=80,fit=scale-down"
}

/** The local file behind a `pending:` placeholder, if the bytes are still here. */
internal fun localFile(story: LocalStory, ref: String?, blobs: BlobStore): File? {
	if (!PendingMedia.isPlaceholder(ref)) return null
	val id = ref!!.removePrefix("pending:")
	val p = story.pending.firstOrNull { it.id == id } ?: return null
	return blobs.file(p.blobId).takeIf { it.isFile }
}
