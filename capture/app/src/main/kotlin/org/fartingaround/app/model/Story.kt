package org.fartingaround.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The phone's working copy of one story (published or draft — a draft is just
 * a story with `draft: true`).
 *
 * The document itself is kept as raw JSON (`doc`) rather than typed classes
 * so that a round trip through the phone never drops a field the web editor
 * wrote: gallery layers, cover focus, word timings, whatever comes next.
 * Typed access goes through the extension functions in StoryDoc.kt.
 *
 * Media captured on the phone is recorded in [pending] and referenced from
 * the document by a placeholder string, `pending:<id>` (see [PendingMedia]),
 * until SyncEngine uploads it and substitutes the real `imageId` / `key`.
 */
@Serializable
data class LocalStory(
	/** Device-side key (file name). Stable even before the server knows the story. */
	val localId: String,
	/** Set once the server has the story (after the first successful sync, or when pulled). */
	val slug: String? = null,
	/** The server `updatedAt` this document is based on — sent as If-Match. */
	val baseUpdatedAt: String? = null,
	val doc: JsonObject,
	val pending: List<PendingMedia> = emptyList(),
	/** Media uploaded from this device whose bytes we kept (audio: for playback and transcription). media id → blob id. */
	val localFiles: Map<String, String> = emptyMap(),
	val docSync: SyncState = SyncState.LOCAL,
	val syncError: String? = null,
	/** The server's copy, when our last PUT was refused with 409. Resolved by SyncEngine.resolveConflict. */
	val conflict: JsonObject? = null,
	val createdAt: String,
	val updatedAt: String
) {
	val isDirty: Boolean get() = docSync != SyncState.SYNCED || pending.isNotEmpty()
	val title: String get() = doc.str("title") ?: ""
	val isDraft: Boolean get() = doc.bool("draft") ?: true
	/** Members only. Absent means the site decides (its default for a new story, or what it already stored). */
	val isPrivate: Boolean get() = doc.str("visibility") == "private"

	companion object {
		fun empty(now: String = nowIso()): LocalStory = LocalStory(
			localId = newId(),
			doc = StoryDoc.empty(now),
			createdAt = now,
			updatedAt = now
		)
	}
}

/**
 * Bytes on the device that still need to reach the server. [id] is the
 * placeholder token in the document; for audio and video it is also the block
 * id and the server-side media id (so the public playback URL is predictable).
 */
@Serializable
data class PendingMedia(
	val id: String,
	/** photo | audio | video */
	val kind: String,
	val blobId: String,
	val mimeType: String,
	val durationMs: Long? = null,
	val sync: SyncState = SyncState.LOCAL,
	val error: String? = null
) {
	val placeholder: String get() = placeholderFor(id)

	companion object {
		fun placeholderFor(id: String) = "pending:$id"
		fun isPlaceholder(v: String?) = v != null && v.startsWith("pending:")
	}
}
