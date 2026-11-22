package fartingaround.capture.data

import android.content.Context
import fartingaround.capture.model.AppJson
import fartingaround.capture.model.LocalStory
import fartingaround.capture.model.PendingMedia
import fartingaround.capture.model.StoryDoc
import fartingaround.capture.model.SyncState
import fartingaround.capture.model.addPhotos
import fartingaround.capture.model.appendBlock
import fartingaround.capture.model.newId
import fartingaround.capture.model.nowIso
import fartingaround.capture.model.obj
import fartingaround.capture.model.objs
import fartingaround.capture.model.str
import fartingaround.capture.model.strs
import fartingaround.capture.model.int
import fartingaround.capture.model.long
import fartingaround.capture.model.with
import fartingaround.capture.model.withStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Local-first story storage: one JSON file per story under filesDir/stories/.
 *
 * Tens of documents, a few KB each — read into memory on start and exposed
 * as a StateFlow. Writes are atomic (temp file + rename) and serialized
 * through a mutex because the UI and the background SyncWorker both touch
 * the same story. Prefer [update] over [save] for read-modify-write.
 */
class StoryStore(context: Context, private val blobs: BlobStore) {
	private val filesDir = context.filesDir
	private val dir = File(filesDir, "stories").apply { mkdirs() }
	private val mutex = Mutex()
	private val _stories = MutableStateFlow<Map<String, LocalStory>>(emptyMap())
	private var loaded = false

	val stories: StateFlow<Map<String, LocalStory>> = _stories.asStateFlow()

	/** Most recently touched first. */
	fun sorted() = stories.map { m -> m.values.sortedByDescending { it.updatedAt } }

	suspend fun ensureLoaded() = mutex.withLock {
		if (loaded) return@withLock
		withContext(Dispatchers.IO) {
			migrateLegacyDrafts()
			_stories.value = dir.listFiles { f -> f.extension == "json" }
				?.mapNotNull { f -> runCatching { AppJson.decodeFromString(LocalStory.serializer(), f.readText()) }.getOrNull() }
				?.associateBy { it.localId }
				?: emptyMap()
		}
		loaded = true
	}

	suspend fun get(localId: String): LocalStory? {
		ensureLoaded()
		return _stories.value[localId]
	}

	suspend fun bySlug(slug: String): LocalStory? {
		ensureLoaded()
		return _stories.value.values.firstOrNull { it.slug == slug }
	}

	suspend fun save(story: LocalStory): LocalStory {
		ensureLoaded()
		val stamped = story.copy(updatedAt = nowIso())
		mutex.withLock {
			writeFile(stamped)
			_stories.value = _stories.value + (stamped.localId to stamped)
		}
		return stamped
	}

	suspend fun update(localId: String, transform: (LocalStory) -> LocalStory): LocalStory? {
		ensureLoaded()
		return mutex.withLock {
			val current = _stories.value[localId] ?: return@withLock null
			val next = transform(current).copy(updatedAt = nowIso())
			writeFile(next)
			_stories.value = _stories.value + (localId to next)
			next
		}
	}

	/** Mark the document edited (anything the user changed by hand). */
	suspend fun edit(localId: String, transform: (JsonObject) -> JsonObject): LocalStory? =
		update(localId) { s -> s.copy(doc = transform(s.doc), docSync = SyncState.LOCAL, syncError = null) }

	/** Forget the local copy and any bytes it still holds. The server copy is untouched. */
	suspend fun delete(localId: String) {
		ensureLoaded()
		mutex.withLock {
			_stories.value[localId]?.let { s ->
				s.pending.forEach { blobs.delete(it.blobId) }
				s.localFiles.values.forEach { blobs.delete(it) }
			}
			withContext(Dispatchers.IO) { File(dir, "$localId.json").delete() }
			_stories.value = _stories.value - localId
		}
	}

	/**
	 * Take a story from the server. A local copy with the same slug that has
	 * nothing unsynced is replaced; one with local edits is left alone (the
	 * next sync will raise the conflict properly). Returns the local story.
	 */
	suspend fun upsertFromServer(server: JsonObject): LocalStory? {
		ensureLoaded()
		val slug = server.str("slug") ?: return null
		val updatedAt = server.str("updatedAt")
		val now = nowIso()
		return mutex.withLock {
			val existing = _stories.value.values.firstOrNull { it.slug == slug }
			val next = when {
				existing == null -> LocalStory(localId = newId(), slug = slug, baseUpdatedAt = updatedAt, doc = server, docSync = SyncState.SYNCED, createdAt = server.str("createdAt") ?: now, updatedAt = now)
				existing.isDirty -> return@withLock existing
				else -> existing.copy(doc = server, baseUpdatedAt = updatedAt, docSync = SyncState.SYNCED, syncError = null, conflict = null, updatedAt = now)
			}
			writeFile(next)
			_stories.value = _stories.value + (next.localId to next)
			next
		}
	}

	private suspend fun writeFile(s: LocalStory) = withContext(Dispatchers.IO) {
		val target = File(dir, "${s.localId}.json")
		val tmp = File(dir, "${s.localId}.json.tmp")
		tmp.writeText(AppJson.encodeToString(LocalStory.serializer(), s))
		if (!tmp.renameTo(target)) {
			target.delete()
			tmp.renameTo(target)
		}
	}

	// ── One-time: the old Draft files → stories ──────────────────────────────

	/**
	 * Before Create mode the app stored `Draft` documents (flat attachments)
	 * under filesDir/drafts/. Turn each into a draft story with the same
	 * rules the server's migrate-drafts-to-stories.mjs used, keeping any
	 * media that never left the phone as pending uploads. Drafts the server
	 * already has (status promoted, or synced with no local bytes) are
	 * skipped — they were migrated server-side and will arrive on the next
	 * pull. The old folder is renamed, not deleted.
	 */
	private fun migrateLegacyDrafts() {
		val legacy = File(filesDir, "drafts")
		if (!legacy.isDirectory) return
		val files = legacy.listFiles { f -> f.extension == "json" } ?: emptyArray()
		for (f in files) {
			val d = runCatching { AppJson.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: continue
			val atts = d.objs("attachments")
			val hasLocalBytes = atts.any { it.str("localBlobId") != null }
			val onServer = d.str("docSync") == "synced"
			if (d.str("status") == "promoted" || (onServer && !hasLocalBytes)) continue
			val story = legacyDraftToStory(d) ?: continue
			runCatching { File(dir, "${story.localId}.json").writeText(AppJson.encodeToString(LocalStory.serializer(), story)) }
		}
		legacy.renameTo(File(filesDir, "drafts-migrated"))
	}

	private fun legacyDraftToStory(d: JsonObject): LocalStory? {
		val now = nowIso()
		val pending = mutableListOf<PendingMedia>()
		var doc = StoryDoc.empty(now)
			.with("title", d.str("title") ?: "")
			.with("date", (d.str("capturedAt") ?: d.str("createdAt") ?: now).take(10))
			.with("location", d.str("location") ?: "")
			.withStrings("tags", d.strs("tags"))
		for (k in listOf("coords", "weather", "astro")) d.obj(k)?.let { doc = doc.with(k, it) }
		d.str("body")?.takeIf { it.isNotBlank() }?.let { doc = doc.appendBlock(StoryDoc.textBlock(it.trim())) }

		val photos = mutableListOf<JsonObject>()
		for (a in d.objs("attachments")) {
			val id = a.str("id") ?: newId()
			val blob = a.str("localBlobId")?.takeIf { blobs.exists(it) }
			val mime = a.str("mimeType") ?: ""
			when (a.str("kind")) {
				"photo" -> {
					val imageId = a.str("imageId")
					val ref = when {
						blob != null -> { pending += PendingMedia(id, "photo", blob, mime.ifBlank { "image/jpeg" }); PendingMedia.placeholderFor(id) }
						imageId != null -> imageId
						else -> continue
					}
					photos += StoryDoc.dumpPhoto(ref, a.int("width"), a.int("height"), a.str("caption"))
				}
				"audio", "video" -> {
					val kind = a.str("kind")!!
					val key = a.str("r2Key")
					val ref = when {
						blob != null -> { pending += PendingMedia(id, kind, blob, mime.ifBlank { if (kind == "audio") "audio/mp4" else "video/mp4" }, a.long("durationMs")); PendingMedia.placeholderFor(id) }
						key != null -> key
						else -> continue
					}
					var block = if (kind == "audio") StoryDoc.audioBlock(id, ref, mime, a.long("durationMs")) else StoryDoc.videoBlock(id, ref, mime, a.long("durationMs"))
					block = block.with("caption", a.str("caption")).with("transcript", a.str("transcript")).with("transcriptModel", a.str("transcriptModel"))
					doc = doc.appendBlock(block)
				}
			}
		}
		doc = doc.addPhotos(photos)
		return LocalStory(localId = d.str("id") ?: newId(), doc = doc, pending = pending, createdAt = d.str("createdAt") ?: now, updatedAt = now)
	}
}
