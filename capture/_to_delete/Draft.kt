package fartingaround.capture.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/*
 * Draft schema. Mirrors app/src/lib/schema.ts in the Capacitor app and the
 * server-side JSDoc in src/lib/server/drafts.js — the JSON on the wire must
 * stay identical, because the same /api/drafts endpoints serve both apps.
 *
 * Conventions:
 *  - Optional fields are nullable with a null default. `AppJson` is configured
 *    with explicitNulls = false, so a null is simply omitted when encoding and
 *    a missing key decodes to null — same as `undefined` in the TS app.
 *  - `localBlobId` points at a file in BlobStore while the bytes still live on
 *    the device. Sync clears it once the server has the media.
 */

/** JSON codec shared by storage and the API client. */
val AppJson: Json = Json {
	ignoreUnknownKeys = true   // the server may add fields we don't model yet
	explicitNulls = false      // omit nulls on encode, tolerate missing on decode
	encodeDefaults = true      // always write `sync`, `source`, etc.
	classDiscriminator = "kind" // Attachment subtype is picked by its `kind` field
}

@Serializable
data class Coords(
	val lat: Double,
	val lon: Double,
	val accuracy: Double? = null
)

/** Weather at capture time, from Open-Meteo. */
@Serializable
data class WeatherSnapshot(
	val tempC: Double,
	val tempF: Int,
	val conditions: String,
	val windKph: Double? = null,
	val humidity: Double? = null,
	val source: String = "open-meteo",
	val fetchedAt: String
)

/** Moon + sun state at capture time and place; computed locally. */
@Serializable
data class AstroSnapshot(
	val moonPhase: Double,
	val moonPhaseLabel: String,
	val moonIllumination: Double,
	val sunrise: String,
	val sunset: String,
	val astronomicalDawn: String? = null,
	val astronomicalDusk: String? = null
)

@Serializable
enum class SyncState {
	@SerialName("local") LOCAL,
	@SerialName("syncing") SYNCING,
	@SerialName("synced") SYNCED,
	@SerialName("error") ERROR
}

@Serializable
enum class DraftStatus {
	@SerialName("open") OPEN,
	@SerialName("promoted") PROMOTED,
	@SerialName("archived") ARCHIVED
}

/** Full-resolution photo stored in R2 with pre-generated derivatives. */
@Serializable
data class R2Image(
	val base: String,
	val widths: List<Int>,
	val ext: String,
	val w: Int,
	val h: Int
)

/**
 * One piece of media on a draft. Serialized polymorphically: the `kind` field
 * selects the subclass ("photo" | "audio" | "video").
 */
@Serializable
sealed class Attachment {
	abstract val id: String
	abstract val createdAt: String
	abstract val caption: String?
	abstract val sync: SyncState
	abstract val syncError: String?
	abstract val localBlobId: String?
	abstract val mimeType: String

	/** The discriminator as a plain string, for labels and the API's `kind` form field. */
	val kind: String
		get() = when (this) {
			is PhotoAttachment -> "photo"
			is AudioAttachment -> "audio"
			is VideoAttachment -> "video"
		}

	/** True while anything on this attachment still needs to reach the server. */
	val needsSync: Boolean
		get() = localBlobId != null || (this is PhotoAttachment && annotationLocalBlobId != null)
}

@Serializable
@SerialName("photo")
data class PhotoAttachment(
	override val id: String,
	override val createdAt: String,
	override val caption: String? = null,
	override val sync: SyncState = SyncState.LOCAL,
	override val syncError: String? = null,
	override val localBlobId: String? = null,
	override val mimeType: String = "image/jpeg",
	/** Cloudflare Images id once uploaded. */
	val imageId: String? = null,
	val publicUrl: String? = null,
	val thumbUrl: String? = null,
	val width: Int? = null,
	val height: Int? = null,

	// Plate-solved star map layered over the photo (astro galleries).
	val annotationLocalBlobId: String? = null,
	val annotationImageId: String? = null,
	val annotationPublicUrl: String? = null,
	val annotationThumbUrl: String? = null,
	val annotationSync: SyncState? = null,
	val annotationSyncError: String? = null,

	// Full-resolution path: skip Cloudflare Images (10 MB cap), upload the
	// original + derivatives straight to R2.
	val fullRes: Boolean? = null,
	val r2: R2Image? = null
) : Attachment()

@Serializable
@SerialName("audio")
data class AudioAttachment(
	override val id: String,
	override val createdAt: String,
	override val caption: String? = null,
	override val sync: SyncState = SyncState.LOCAL,
	override val syncError: String? = null,
	override val localBlobId: String? = null,
	override val mimeType: String = "audio/mp4",
	val durationMs: Long? = null,
	/** R2 object key once uploaded. */
	val r2Key: String? = null,
	val transcript: String? = null,
	val transcriptModel: String? = null
) : Attachment()

@Serializable
@SerialName("video")
data class VideoAttachment(
	override val id: String,
	override val createdAt: String,
	override val caption: String? = null,
	override val sync: SyncState = SyncState.LOCAL,
	override val syncError: String? = null,
	override val localBlobId: String? = null,
	override val mimeType: String = "video/mp4",
	val durationMs: Long? = null,
	val r2Key: String? = null
) : Attachment()

@Serializable
data class Draft(
	val id: String,
	val title: String = "",
	val body: String = "",
	val tags: List<String> = emptyList(),
	val location: String? = null,
	val coords: Coords? = null,
	val weather: WeatherSnapshot? = null,
	val astro: AstroSnapshot? = null,
	val capturedAt: String,
	val createdAt: String,
	val updatedAt: String,
	val attachments: List<Attachment> = emptyList(),
	val status: DraftStatus = DraftStatus.OPEN,
	val promotedToSlug: String? = null,
	/** Sync state of the draft document itself (not its attachments). */
	val docSync: SyncState = SyncState.LOCAL,
	val docSyncError: String? = null
) {
	/** Anything on this draft that hasn't reached the server yet? */
	val isDirty: Boolean
		get() = docSync != SyncState.SYNCED || attachments.any { it.needsSync }

	companion object {
		fun empty(now: String = nowIso()): Draft = Draft(
			id = newId(),
			capturedAt = now,
			createdAt = now,
			updatedAt = now
		)
	}
}

/** Mutate one attachment by id, leaving the others untouched. */
inline fun Draft.mapAttachment(id: String, transform: (Attachment) -> Attachment): Draft =
	copy(attachments = attachments.map { if (it.id == id) transform(it) else it })

/** Convenience copy() for fields common to every Attachment subclass. */
fun Attachment.withSync(sync: SyncState, error: String? = null, clearLocalBlob: Boolean = false): Attachment =
	when (this) {
		is PhotoAttachment -> copy(sync = sync, syncError = error, localBlobId = if (clearLocalBlob) null else localBlobId)
		is AudioAttachment -> copy(sync = sync, syncError = error, localBlobId = if (clearLocalBlob) null else localBlobId)
		is VideoAttachment -> copy(sync = sync, syncError = error, localBlobId = if (clearLocalBlob) null else localBlobId)
	}

fun Attachment.withCaption(caption: String?): Attachment = when (this) {
	is PhotoAttachment -> copy(caption = caption)
	is AudioAttachment -> copy(caption = caption)
	is VideoAttachment -> copy(caption = caption)
}

fun nowIso(): String = Instant.now().toString()

fun newId(): String = UUID.randomUUID().toString()
