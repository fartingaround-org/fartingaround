package fartingaround.capture.sync

import fartingaround.capture.data.BlobStore
import fartingaround.capture.data.MediaInfo
import fartingaround.capture.data.Settings
import fartingaround.capture.data.StoryStore
import fartingaround.capture.model.LocalStory
import fartingaround.capture.model.PendingMedia
import fartingaround.capture.model.SyncState
import fartingaround.capture.model.bool
import fartingaround.capture.model.forServer
import fartingaround.capture.model.mapObjects
import fartingaround.capture.model.obj
import fartingaround.capture.model.str
import fartingaround.capture.model.with
import fartingaround.capture.net.ApiClient
import fartingaround.capture.net.ApiException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Pushes a story to the site. Order, per story:
 *   1. every [PendingMedia]: upload (photo → multipart; audio/video → sign,
 *      PUT, confirm), then substitute its `pending:<id>` placeholder in the
 *      document with the real `imageId` / `key`;
 *   2. the document: POST when the server has never seen it (it picks the
 *      slug), otherwise a conditional PUT with If-Match = baseUpdatedAt.
 *      A 409 is stored on the story as [LocalStory.conflict] for the UI to
 *      resolve with [resolveConflict]; nothing is overwritten silently.
 *
 * Every step persists through StoryStore.update so a crash mid-way leaves a
 * resumable state and the UI (observing the store) shows progress live.
 */
class SyncEngine(
	private val stories: StoryStore,
	private val blobs: BlobStore,
	private val settings: Settings,
	private val api: ApiClient
) {
	class SyncException(message: String, val status: Int? = null) : Exception(message)
	/** Thrown when the PUT was refused with 409; the conflict is already saved on the story. */
	class ConflictException(val localId: String) : Exception("The story was changed on the site. Choose which version to keep.")

	private suspend fun session(): ApiClient.Session {
		val s = settings.load()
		if (s.apiToken.isBlank()) throw SyncException("No capture token set. Open Settings.")
		return api.session(s.base, s.apiToken)
	}

	suspend fun syncStory(localId: String): LocalStory {
		val session = session()
		var story = stories.update(localId) { it.copy(docSync = SyncState.SYNCING, syncError = null) }
			?: throw SyncException("Story no longer exists")

		// 1. Media.
		for (p in story.pending) {
			stories.update(localId) { s -> s.copy(pending = s.pending.map { if (it.id == p.id) it.copy(sync = SyncState.SYNCING, error = null) else it }) }
			try {
				story = uploadPending(session, localId, p)
			} catch (e: Exception) {
				val msg = e.message ?: "upload failed"
				stories.update(localId) { s ->
					s.copy(docSync = SyncState.ERROR, syncError = msg, pending = s.pending.map { if (it.id == p.id) it.copy(sync = SyncState.ERROR, error = msg) else it })
				}
				throw wrap(e)
			}
		}

		// 2. Document.
		return pushDoc(session, localId, force = false)
	}

	/** After a 409: keep what's on the phone (overwrite the site) or take the site's copy. */
	suspend fun resolveConflict(localId: String, keepMine: Boolean): LocalStory {
		if (keepMine) return pushDoc(session(), localId, force = true)
		val before = stories.get(localId) ?: throw SyncException("Story no longer exists")
		val server = before.conflict ?: return before
		val next = stories.update(localId) { s ->
			s.copy(doc = server, baseUpdatedAt = server.str("updatedAt"), pending = emptyList(), conflict = null, docSync = SyncState.SYNCED, syncError = null)
		} ?: throw SyncException("Story no longer exists")
		// Taking theirs means any media we hadn't uploaded yet has nowhere to go.
		before.pending.forEach { blobs.delete(it.blobId) }
		return next
	}

	/**
	 * Fetch the site's stories into the local store — drafts by default, or
	 * everything with [includePublished]. A local copy with unsynced edits is
	 * never clobbered; one already at the server's `updatedAt` is skipped.
	 *
	 * Also the place a story deleted on the site leaves the phone: a local copy
	 * that came from the site, has nothing unsynced, and is no longer in the
	 * site's list is forgotten. One with unsynced work is kept.
	 */
	suspend fun pullDrafts(includePublished: Boolean = false): Int {
		val session = session()
		val asked = java.time.Instant.now()
		// The whole list, always: only that can say a story is gone.
		val all = session.listStories()
		var n = 0
		for (summary in all) {
			if (!includePublished && summary.bool("draft") != true) continue
			val slug = summary.str("slug") ?: continue
			val local = stories.bySlug(slug)
			if (local != null && (local.isDirty || local.baseUpdatedAt == summary.str("updatedAt"))) continue
			stories.upsertFromServer(session.getStory(slug))
			n++
		}
		val onSite = all.mapNotNull { it.str("slug") }.toSet()
		for (local in stories.stories.value.values.toList()) {
			val slug = local.slug ?: continue // never synced: only the phone has it
			if (slug in onSite || local.isDirty) continue
			// Touched after the list was asked for (a sync that just created it): not in that list yet.
			val touched = runCatching { java.time.Instant.parse(local.updatedAt) }.getOrNull()
			if (touched == null || !touched.isBefore(asked)) continue
			stories.delete(local.localId)
		}
		return n
	}

	private suspend fun pushDoc(session: ApiClient.Session, localId: String, force: Boolean): LocalStory {
		val story = stories.get(localId) ?: throw SyncException("Story no longer exists")
		if (story.pending.isNotEmpty()) throw SyncException("Some media hasn't uploaded yet.")
		try {
			val saved: JsonObject = if (story.slug == null) {
				session.createStory(story.doc.forServer())
			} else {
				when (val r = session.putStory(story.slug, story.doc.forServer(), story.baseUpdatedAt, force)) {
					is ApiClient.PutResult.Saved -> r.story
					is ApiClient.PutResult.Conflict -> {
						stories.update(localId) { it.copy(conflict = r.current, docSync = SyncState.ERROR, syncError = "Changed on the site since you last synced.") }
						throw ConflictException(localId)
					}
				}
			}
			return stories.update(localId) {
				it.copy(doc = saved, slug = saved.str("slug"), baseUpdatedAt = saved.str("updatedAt"), docSync = SyncState.SYNCED, syncError = null, conflict = null)
			} ?: throw SyncException("Story deleted during sync")
		} catch (e: ConflictException) {
			throw e
		} catch (e: Exception) {
			stories.update(localId) { it.copy(docSync = SyncState.ERROR, syncError = e.message) }
			throw wrap(e)
		}
	}

	// ── Media ────────────────────────────────────────────────────────────────

	private suspend fun uploadPending(session: ApiClient.Session, localId: String, p: PendingMedia): LocalStory {
		val file = blobs.file(p.blobId)
		if (!file.isFile) {
			// The flag says local but the bytes are gone: drop the reference rather than retry forever.
			return stories.update(localId) { s ->
				s.copy(
					pending = s.pending.filterNot { it.id == p.id },
					// …including the cover, when it was this photo.
					doc = dropPlaceholder(s.doc, p.placeholder).let { d -> if (d.str("coverImageId") == p.placeholder) JsonObject(d - "coverImageId") else d }
				)
			}!!
		}
		return when (p.kind) {
			"photo" -> {
				val r = session.uploadPhoto(file, p.mimeType, "${p.id}.${MediaInfo.extFor(p.mimeType)}")
				val imageId = r.str("imageId") ?: throw SyncException("server returned no imageId")
				val done = stories.update(localId) { s ->
					s.copy(pending = s.pending.filterNot { it.id == p.id }, doc = s.doc.mapObjects { o ->
						if (o.str("imageId") != p.placeholder) o
						else {
							var next = o.with("imageId", imageId)
							// A dump photo (no `type`) wants the layout + ordering data; a photo block just the id.
							if (o.str("type") == null) {
								next = next.with("w", r["w"]).with("h", r["h"]).with("takenAt", r["takenAt"])
							}
							// What the file says about where it was taken (private to the author):
							// the site's reading, else what this phone read when the photo was added.
							next = next.with("exif", r["exif"] ?: o["exif"])
							next
						}
					// The story's cover can be this same photo (or a cover-only upload): point it at the real image too.
					}.let { d -> if (d.str("coverImageId") == p.placeholder) d.with("coverImageId", imageId) else d })
				}!!
				blobs.delete(p.blobId)
				done
			}
			"audio", "video" -> {
				// Trust the file's own header over the label it arrived with: a shared
				// .mp4 can come in as "video/mpeg", which the site refuses (bad_mime).
				val mime = MediaInfo.containerMime(file, p.kind, p.mimeType)
				val signed = session.signMedia(p.kind, mime, p.id)
				val key = signed.str("key") ?: throw SyncException("server returned no key")
				api.signedPut(signed.str("url")!!, file, mime)
				session.confirmMedia(listOf(key))
				val keepLocal = p.kind == "audio" // small, and wanted for playback/transcription offline
				val done = stories.update(localId) { s ->
					s.copy(
						pending = s.pending.filterNot { it.id == p.id },
						localFiles = if (keepLocal) s.localFiles + (p.id to p.blobId) else s.localFiles,
						doc = s.doc.mapObjects { o ->
							when (p.placeholder) {
								// Also correct the stored type, so playback isn't served with the wrong label.
								o.str("r2Key") -> o.with("r2Key", key).with("mimeType", mime)
								o.str("key") -> o.with("key", key).with("mimeType", mime)
								else -> o
							}
						}
					)
				}!!
				if (!keepLocal) blobs.delete(p.blobId)
				done
			}
			else -> throw SyncException("unknown media kind ${p.kind}")
		}
	}

	/** Remove whatever referenced a placeholder whose bytes vanished: the dump photo, the block, or the photo's audio caption. */
	private fun dropPlaceholder(doc: JsonObject, placeholder: String): JsonObject = doc.mapObjects { o ->
		var next = o
		(o["photos"] as? JsonArray)?.let { photos ->
			next = next.with("photos", JsonArray(photos.filter { (it as? JsonObject)?.str("imageId") != placeholder }))
		}
		(o["blocks"] as? JsonArray)?.let { blocks ->
			next = next.with("blocks", JsonArray(blocks.filter { b ->
				val bo = b as? JsonObject
				bo?.str("imageId") != placeholder && bo?.str("r2Key") != placeholder
			}))
		}
		if (o.obj("audio")?.str("key") == placeholder) next = JsonObject(next - "audio")
		next
	}

	private fun wrap(e: Exception) = when (e) {
		is SyncException, is ConflictException -> e
		is ApiException -> SyncException(e.message ?: "Sync failed", e.status)
		else -> SyncException(e.message ?: e.toString())
	}
}
