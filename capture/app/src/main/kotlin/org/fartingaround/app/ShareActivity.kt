package org.fartingaround.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import org.fartingaround.app.data.MediaInfo
import org.fartingaround.app.model.LocalStory
import org.fartingaround.app.model.PendingMedia
import org.fartingaround.app.model.StoryDoc
import org.fartingaround.app.model.addPhotos
import org.fartingaround.app.model.appendBlock
import org.fartingaround.app.model.newBlockId
import org.fartingaround.app.model.nowIso
import org.fartingaround.app.model.with
import org.fartingaround.app.model.withNotes
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Share-sheet target. Receives ACTION_SEND / ACTION_SEND_MULTIPLE, copies each
 * shared item into the blob store and builds a new draft story around it:
 * three or more photos become one photo dump, fewer become photo blocks,
 * audio and video become their blocks, shared text becomes the notes. Then
 * opens that story in MainActivity.
 *
 * Has a transparent theme and no UI of its own — it's done in well under a
 * second for photos; a long video copy shows a toast so it doesn't feel dead.
 */
class ShareActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val uris: List<Uri> = when (intent.action) {
			Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
			Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
			else -> emptyList()
		}
		val text = intent.getStringExtra(Intent.EXTRA_TEXT)
		val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

		if (uris.isEmpty() && text.isNullOrBlank()) {
			finish(); return
		}
		if (uris.size > 1 || uris.any { (contentResolver.getType(it) ?: "").startsWith("video/") }) {
			Toast.makeText(this, "Adding to a new draft…", Toast.LENGTH_SHORT).show()
		}

		lifecycleScope.launch {
			val app = captureApp
			val now = nowIso()
			val pending = mutableListOf<PendingMedia>()
			val photos = mutableListOf<JsonObject>()
			var doc = StoryDoc.empty(now).with("title", subject ?: "").withNotes(text ?: "")

			for (uri in uris) {
				val mime = contentResolver.getType(uri) ?: "application/octet-stream"
				val blobId = runCatching { app.blobStore.importUri(uri) }.getOrNull() ?: continue
				val file = app.blobStore.file(blobId)
				when {
					mime.startsWith("image/") -> {
						val id = newBlockId("photo")
						val dims = MediaInfo.imageDims(file)
						pending += PendingMedia(id, "photo", blobId, mime)
						photos += with(StoryDoc) { dumpPhoto(PendingMedia.placeholderFor(id), dims?.w, dims?.h).takenAt(MediaInfo.gps(file)) }
					}
					mime.startsWith("video/") -> {
						val id = newBlockId("video")
						val dur = MediaInfo.durationMs(file)
						pending += PendingMedia(id, "video", blobId, mime, dur)
						doc = doc.appendBlock(StoryDoc.videoBlock(id, PendingMedia.placeholderFor(id), mime, dur))
					}
					mime.startsWith("audio/") -> {
						val id = newBlockId("audio")
						val dur = MediaInfo.durationMs(file)
						pending += PendingMedia(id, "audio", blobId, mime, dur)
						doc = doc.appendBlock(StoryDoc.audioBlock(id, PendingMedia.placeholderFor(id), mime, dur))
					}
					else -> app.blobStore.delete(blobId)
				}
			}
			doc = doc.addPhotos(photos)

			val story = app.storyStore.save(LocalStory.empty(now).copy(doc = doc, pending = pending))

			startActivity(Intent(this@ShareActivity, MainActivity::class.java).apply {
				putExtra(MainActivity.EXTRA_DRAFT_ID, story.localId)
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			})
			finish()
		}
	}
}
