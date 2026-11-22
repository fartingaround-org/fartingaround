package fartingaround.capture.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import fartingaround.capture.captureApp
import fartingaround.capture.model.Attachment
import fartingaround.capture.model.AudioAttachment
import fartingaround.capture.model.PhotoAttachment
import fartingaround.capture.model.SyncState
import fartingaround.capture.model.VideoAttachment
import fartingaround.capture.model.mapAttachment
import fartingaround.capture.model.withCaption
import fartingaround.capture.sync.Transcribe
import fartingaround.capture.ui.components.ErrorText
import fartingaround.capture.ui.components.Eyebrow
import fartingaround.capture.ui.components.Muted
import fartingaround.capture.ui.components.SyncBadge
import fartingaround.capture.ui.components.fmtDuration
import fartingaround.capture.ui.theme.LocalCaptureColors
import fartingaround.capture.ui.theme.imageFilter
import kotlinx.coroutines.launch
import java.io.File

/** One attachment on the editor: preview, caption, per-kind extras. */
@Composable
fun AttachmentCard(draftId: String, attachment: Attachment, onRemove: () -> Unit) {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()
	val c = LocalCaptureColors.current

	/** Persist a change to this attachment and re-dirty the draft document. */
	fun patch(transform: (Attachment) -> Attachment) {
		scope.launch {
			app.draftStore.update(draftId) { d -> d.mapAttachment(attachment.id, transform).copy(docSync = SyncState.LOCAL) }
		}
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
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				Eyebrow(attachment.kind)
				SyncBadge(attachment.sync)
			}
			IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
				Icon(Icons.Filled.Close, contentDescription = "Remove", tint = c.pencil)
			}
		}
		attachment.syncError?.let { ErrorText(it) }

		when (attachment) {
			is PhotoAttachment -> PhotoBody(draftId, attachment, ::patch)
			is AudioAttachment -> AudioBody(draftId, attachment, ::patch)
			is VideoAttachment -> VideoBody(attachment)
		}

		var caption by remember(attachment.id) { mutableStateOf(attachment.caption ?: "") }
		OutlinedTextField(
			value = caption,
			onValueChange = { v -> caption = v; patch { it.withCaption(v.ifBlank { null }) } },
			label = { Eyebrow("Caption") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth()
		)
	}
}

// ── Photo ────────────────────────────────────────────────────────────────────

@Composable
private fun PhotoBody(draftId: String, att: PhotoAttachment, patch: ((Attachment) -> Attachment) -> Unit) {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()
	val local: File? = att.localBlobId?.let { app.blobStore.file(it) }?.takeIf { it.isFile }
	val model: Any? = local ?: att.publicUrl
	val ratio = if (att.width != null && att.height != null && att.height > 0) att.width.toFloat() / att.height else 4f / 3f

	if (model != null) {
		AsyncImage(
			model = model,
			contentDescription = att.caption,
			contentScale = ContentScale.Fit,
			colorFilter = imageFilter(),
			modifier = Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.4f, 3f)).clip(RoundedCornerShape(3.dp))
		)
	} else {
		Placeholder("Photo (synced — open admin to view)")
	}

	// Full-resolution path: original + derivatives to R2 instead of Cloudflare Images.
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text("Full-res (R2)", style = MaterialTheme.typography.bodyMedium)
			Muted(if (att.r2 != null) "Uploaded: ${att.r2.w}×${att.r2.h}, ${att.r2.widths.size} sizes" else "For astro frames past the 10 MB Images cap")
		}
		Switch(
			checked = att.fullRes == true,
			enabled = att.localBlobId != null, // can't change the path once it's uploaded
			onCheckedChange = { on -> patch { (it as PhotoAttachment).copy(fullRes = if (on) true else null) } }
		)
	}

	// Star map (plate-solved annotation) slot.
	val starLocal = att.annotationLocalBlobId?.let { app.blobStore.file(it) }?.takeIf { it.isFile }
	val starModel: Any? = starLocal ?: att.annotationThumbUrl ?: att.annotationPublicUrl
	val pickStarMap = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
		if (uri != null) scope.launch {
			att.annotationLocalBlobId?.let { app.blobStore.delete(it) }
			val blobId = app.blobStore.importUri(uri)
			patch {
				(it as PhotoAttachment).copy(
					annotationLocalBlobId = blobId, annotationSync = SyncState.LOCAL, annotationSyncError = null,
					annotationImageId = null, annotationPublicUrl = null, annotationThumbUrl = null
				)
			}
		}
	}
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
		if (starModel != null) {
			AsyncImage(
				model = starModel, contentDescription = "Star map", contentScale = ContentScale.Crop,
				colorFilter = imageFilter(),
				modifier = Modifier.size(64.dp).clip(RoundedCornerShape(3.dp))
			)
		}
		Column(Modifier.weight(1f)) {
			Text("Star map", style = MaterialTheme.typography.bodyMedium)
			Muted(
				when {
					starModel == null -> "Optional plate-solved overlay, shown on hover on the site"
					att.annotationSync == SyncState.SYNCED || att.annotationImageId != null -> "Synced"
					att.annotationSync == SyncState.ERROR -> att.annotationSyncError ?: "Error"
					else -> "Local — will upload with the next sync"
				}
			)
		}
		TextButton(onClick = { pickStarMap.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
			Text(if (starModel == null) "Attach" else "Replace")
		}
		if (starModel != null) {
			TextButton(onClick = {
				scope.launch {
					att.annotationLocalBlobId?.let { app.blobStore.delete(it) }
					patch {
						(it as PhotoAttachment).copy(
							annotationLocalBlobId = null, annotationImageId = null, annotationPublicUrl = null,
							annotationThumbUrl = null, annotationSync = null, annotationSyncError = null
						)
					}
				}
			}) { Text("Remove", color = LocalCaptureColors.current.wax) }
		}
	}
}

// ── Audio ────────────────────────────────────────────────────────────────────

@Composable
private fun AudioBody(draftId: String, att: AudioAttachment, patch: ((Attachment) -> Attachment) -> Unit) {
	val context = LocalContext.current
	val app = context.captureApp
	val scope = rememberCoroutineScope()
	val local: File? = att.localBlobId?.let { app.blobStore.file(it) }?.takeIf { it.isFile }
	var transcribing by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }

	if (local != null) AudioPlayer(file = local) else Placeholder("Audio (on server)")
	Muted(fmtDuration(att.durationMs))

	if (att.transcript != null) {
		Column {
			Eyebrow("Transcript (${att.transcriptModel ?: "?"})")
			Text(att.transcript, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
		}
	} else {
		OutlinedButton(enabled = !transcribing, onClick = {
			scope.launch {
				transcribing = true; error = null
				try {
					val settings = app.settings.load()
					// Prefer the local file; once synced the only copy is on the
					// server, so pull it back through the authenticated media proxy.
					val file = local ?: run {
						if (att.r2Key == null) throw IllegalStateException("Audio not available locally or on server. Sync first or re-record.")
						if (settings.apiToken.isBlank()) throw IllegalStateException("No capture token set. Open Settings.")
						val tmp = File(context.cacheDir, "transcribe-${att.id}")
						app.api.session(settings.base, settings.apiToken).fetchMedia(draftId, att.id, tmp)
						tmp
					}
					val result = Transcribe.run(app.api.http, settings, file, att.mimeType)
					if (file !== local) file.delete()
					patch { (it as AudioAttachment).copy(transcript = result.text, transcriptModel = result.model) }
				} catch (e: Exception) {
					error = e.message ?: "Transcription failed"
				} finally {
					transcribing = false
				}
			}
		}) { Text(if (transcribing) "Transcribing…" else "Transcribe with ElevenLabs") }
		error?.let { ErrorText(it) }
	}
}

// ── Video ────────────────────────────────────────────────────────────────────

@Composable
private fun VideoBody(att: VideoAttachment) {
	val context = LocalContext.current
	val app = context.captureApp
	val local: File? = att.localBlobId?.let { app.blobStore.file(it) }?.takeIf { it.isFile }
	var playing by remember { mutableStateOf(false) }

	if (local != null) {
		Box(
			Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(3.dp)).clickable { playing = true },
			contentAlignment = Alignment.Center
		) {
			// Blob files have no extension, so Coil can't sniff the MIME type and
			// wouldn't pick its video decoder on its own — force it for this request.
			AsyncImage(
				model = ImageRequest.Builder(context).data(local).decoderFactory(VideoFrameDecoder.Factory()).build(),
				contentDescription = null, contentScale = ContentScale.Crop, colorFilter = imageFilter(), modifier = Modifier.fillMaxWidth()
			)
			Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(56.dp))
		}
		if (playing) VideoDialog(file = local, onDismiss = { playing = false })
	} else {
		Placeholder("Video (on server)")
	}
	Muted(fmtDuration(att.durationMs))
}

@Composable
private fun Placeholder(text: String) {
	Box(
		Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.background),
		contentAlignment = Alignment.Center
	) { Muted(text) }
}
