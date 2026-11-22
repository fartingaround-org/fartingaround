package fartingaround.capture.data

import android.content.Context
import fartingaround.capture.model.AppJson
import fartingaround.capture.model.Draft
import fartingaround.capture.model.nowIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local-first draft storage: one JSON file per draft under filesDir/drafts/.
 *
 * This is the same shape the Capacitor app had with idb-keyval (key = draft
 * id, value = the whole document) — deliberately not a relational database.
 * A personal capture app holds tens of drafts, each a few KB; reading them all
 * into memory on start and exposing them as a StateFlow is simpler than Room
 * and has no schema migrations to maintain.
 *
 * Writes are atomic (write to a temp file, then rename) and serialized through
 * a mutex, because the UI and the background SyncWorker can both touch the
 * same draft. Prefer [update] over [save] when the change is a transformation
 * of the current state, so concurrent edits don't clobber each other.
 */
class DraftStore(context: Context, private val blobs: BlobStore) {
	private val dir = File(context.filesDir, "drafts").apply { mkdirs() }
	private val mutex = Mutex()
	private val _drafts = MutableStateFlow<Map<String, Draft>>(emptyMap())
	private var loaded = false

	/** All drafts, newest capture first. */
	val drafts: StateFlow<Map<String, Draft>> = _drafts.asStateFlow()

	fun sorted() = drafts.map { m -> m.values.sortedByDescending { it.capturedAt } }

	/** Load everything from disk once. Safe to call repeatedly. */
	suspend fun ensureLoaded() = mutex.withLock {
		if (loaded) return@withLock
		withContext(Dispatchers.IO) {
			val all = dir.listFiles { f -> f.extension == "json" }
				?.mapNotNull { f ->
					runCatching { AppJson.decodeFromString(Draft.serializer(), f.readText()) }
						.getOrNull() // a corrupt file is skipped rather than killing the app
				}
				?.associateBy { it.id }
				?: emptyMap()
			_drafts.value = all
		}
		loaded = true
	}

	suspend fun get(id: String): Draft? {
		ensureLoaded()
		return _drafts.value[id]
	}

	/** Persist a draft as given (its updatedAt is bumped). */
	suspend fun save(draft: Draft): Draft {
		ensureLoaded()
		val stamped = draft.copy(updatedAt = nowIso())
		mutex.withLock {
			writeFile(stamped)
			_drafts.value = _drafts.value + (stamped.id to stamped)
		}
		return stamped
	}

	/**
	 * Read-modify-write under the lock. Returns the new draft, or null if the
	 * draft no longer exists (e.g. deleted while a sync was running).
	 */
	suspend fun update(id: String, transform: (Draft) -> Draft): Draft? {
		ensureLoaded()
		return mutex.withLock {
			val current = _drafts.value[id] ?: return@withLock null
			val next = transform(current).copy(updatedAt = nowIso())
			writeFile(next)
			_drafts.value = _drafts.value + (id to next)
			next
		}
	}

	/** Delete the draft and any media blobs it still holds locally. */
	suspend fun delete(id: String) {
		ensureLoaded()
		mutex.withLock {
			val d = _drafts.value[id]
			if (d != null) {
				for (a in d.attachments) {
					a.localBlobId?.let { blobs.delete(it) }
					if (a is fartingaround.capture.model.PhotoAttachment) {
						a.annotationLocalBlobId?.let { blobs.delete(it) }
					}
				}
			}
			withContext(Dispatchers.IO) { File(dir, "$id.json").delete() }
			_drafts.value = _drafts.value - id
		}
	}

	private suspend fun writeFile(d: Draft) = withContext(Dispatchers.IO) {
		val target = File(dir, "${d.id}.json")
		val tmp = File(dir, "${d.id}.json.tmp")
		tmp.writeText(AppJson.encodeToString(Draft.serializer(), d))
		if (!tmp.renameTo(target)) {
			// rename() can refuse to overwrite on some filesystems — fall back.
			target.delete()
			tmp.renameTo(target)
		}
	}
}
