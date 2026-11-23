package org.fartingaround.app.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.ZoneId

/*
 * Typed, immutable access to a Story kept as JsonObject. Every mutator
 * returns a new object and leaves everything it doesn't know about intact.
 * Field names follow src/lib/types.js on the web side exactly.
 */

// ── Reading ─────────────────────────────────────────────────────────────────

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray) ?: emptyList()
fun JsonObject.objs(k: String): List<JsonObject> = arr(k).mapNotNull { it as? JsonObject }
fun JsonObject.strs(k: String): List<String> = arr(k).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** Copy with one key set, or removed when [v] is null. */
fun JsonObject.with(k: String, v: JsonElement?): JsonObject =
	JsonObject(if (v == null || v is JsonNull) this - k else this + (k to v))

fun JsonObject.with(k: String, v: String?) = with(k, v?.let(::JsonPrimitive))
fun JsonObject.with(k: String, v: Number?) = with(k, v?.let(::JsonPrimitive))
fun JsonObject.with(k: String, v: Boolean?) = with(k, v?.let(::JsonPrimitive))
fun JsonObject.withStrings(k: String, v: List<String>) = with(k, JsonArray(v.map(::JsonPrimitive)))

/** Serialize one of our typed snapshots into the document. */
inline fun <reified T> JsonObject.withTyped(k: String, v: T?): JsonObject =
	with(k, v?.let { AppJson.encodeToJsonElement(kotlinx.serialization.serializer<T>(), it) })

inline fun <reified T> JsonObject.typed(k: String): T? =
	this[k]?.let { runCatching { AppJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), it) }.getOrNull() }

// ── Story-level ─────────────────────────────────────────────────────────────

object StoryDoc {
	/** A fresh draft dated today, nothing in it. */
	fun empty(now: String = nowIso()): JsonObject = JsonObject(
		mapOf(
			"title" to JsonPrimitive(""),
			"date" to JsonPrimitive(LocalDate.now(ZoneId.systemDefault()).toString()),
			"location" to JsonPrimitive(""),
			"summary" to JsonPrimitive(""),
			"tags" to JsonArray(emptyList()),
			"blocks" to JsonArray(emptyList()),
			"draft" to JsonPrimitive(true),
			"createdAt" to JsonPrimitive(now),
			"updatedAt" to JsonPrimitive(now)
		)
	)

	// Block builders. Shapes per types.js; ids per the server's convention.
	fun textBlock(body: String) = JsonObject(mapOf("type" to JsonPrimitive("text"), "id" to JsonPrimitive(newBlockId("text")), "body" to JsonPrimitive(body)))
	fun pullBlock(body: String) = JsonObject(mapOf("type" to JsonPrimitive("pull"), "id" to JsonPrimitive(newBlockId("pull")), "body" to JsonPrimitive(body)))
	fun dividerBlock() = JsonObject(mapOf("type" to JsonPrimitive("divider"), "id" to JsonPrimitive(newBlockId("divider"))))

	fun photoBlock(imageIdOrPlaceholder: String, caption: String? = null) =
		JsonObject(mapOf("type" to JsonPrimitive("photo"), "id" to JsonPrimitive(newBlockId("photo")), "imageId" to JsonPrimitive(imageIdOrPlaceholder)))
			.with("caption", caption)

	fun dumpPhoto(imageIdOrPlaceholder: String, w: Int?, h: Int?, caption: String? = null) =
		JsonObject(mapOf("imageId" to JsonPrimitive(imageIdOrPlaceholder))).with("w", w).with("h", h).with("caption", caption)

	/**
	 * The same photo, noting where its file says it was taken. Kept in `exif`,
	 * as the site does: private to the author, never shown to readers, there so
	 * the editor can offer it as the story's location.
	 */
	fun JsonObject.takenAt(place: Pair<Double, Double>?): JsonObject =
		if (place == null) this else with("exif", JsonObject((obj("exif") ?: JsonObject(emptyMap())) + mapOf("lat" to JsonPrimitive(place.first), "lon" to JsonPrimitive(place.second))))

	fun dumpBlock(photos: List<JsonObject>) = JsonObject(
		mapOf(
			"type" to JsonPrimitive("dump"), "id" to JsonPrimitive(newBlockId("dump")),
			"photos" to JsonArray(photos), "size" to JsonPrimitive("wide"), "rowSize" to JsonPrimitive("m"), "map" to JsonPrimitive("none")
		)
	)

	/** `id` doubles as the server media id; `r2Key` holds the placeholder until uploaded. */
	fun audioBlock(id: String, keyOrPlaceholder: String, mimeType: String, durationMs: Long?) =
		JsonObject(mapOf("type" to JsonPrimitive("audio"), "id" to JsonPrimitive(id), "r2Key" to JsonPrimitive(keyOrPlaceholder), "mimeType" to JsonPrimitive(mimeType)))
			.with("durationMs", durationMs)

	fun videoBlock(id: String, keyOrPlaceholder: String, mimeType: String, durationMs: Long?) =
		JsonObject(mapOf("type" to JsonPrimitive("video"), "id" to JsonPrimitive(id), "r2Key" to JsonPrimitive(keyOrPlaceholder), "mimeType" to JsonPrimitive(mimeType), "size" to JsonPrimitive("wide")))
			.with("durationMs", durationMs)

	/** A dump photo's spoken caption, pre-upload. */
	fun photoAudio(id: String, keyOrPlaceholder: String, mimeType: String, durationMs: Long?) =
		JsonObject(mapOf("id" to JsonPrimitive(id), "key" to JsonPrimitive(keyOrPlaceholder), "mimeType" to JsonPrimitive(mimeType)))
			.with("durationMs", durationMs)
}

val JsonObject.blocks: List<JsonObject> get() = objs("blocks")
fun JsonObject.withBlocks(blocks: List<JsonObject>): JsonObject = with("blocks", JsonArray(blocks))
fun JsonObject.appendBlock(block: JsonObject): JsonObject = withBlocks(blocks + block)
fun JsonObject.removeBlock(id: String): JsonObject = withBlocks(blocks.filterNot { it.str("id") == id })
fun JsonObject.updateBlock(id: String, f: (JsonObject) -> JsonObject): JsonObject =
	withBlocks(blocks.map { if (it.str("id") == id) f(it) else it })

/** Move a block up (-1) or down (+1); no-op at the ends. */
fun JsonObject.moveBlock(id: String, delta: Int): JsonObject {
	val list = blocks.toMutableList()
	val i = list.indexOfFirst { it.str("id") == id }
	val j = i + delta
	if (i < 0 || j < 0 || j >= list.size) return this
	val b = list.removeAt(i); list.add(j, b)
	return withBlocks(list)
}

/** The leading text block is "notes" in the capture UI: read it… */
val JsonObject.notes: String get() = blocks.firstOrNull { it.str("type") == "text" }?.str("body") ?: ""

/** …and write it (created at the top when missing, removed when blanked). */
fun JsonObject.withNotes(text: String): JsonObject {
	val first = blocks.firstOrNull { it.str("type") == "text" }
	return when {
		first != null && text.isBlank() -> removeBlock(first.str("id")!!)
		first != null -> updateBlock(first.str("id")!!, { it.with("body", text) })
		text.isBlank() -> this
		else -> withBlocks(listOf(StoryDoc.textBlock(text)) + blocks)
	}
}

// ── Notes: stories with no title (specs/notes-titleless-posts.md) ────────────

private fun clipLabel(text: String?, max: Int = 80): String? {
	val line = text?.trim()?.lineSequence()?.firstOrNull()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
	if (line.isEmpty()) return null
	if (line.length <= max) return line
	val cut = line.take(max - 1)
	val at = cut.lastIndexOf(' ')
	return (if (at > max * 0.6) cut.take(at) else cut).trimEnd() + "…"
}

/**
 * What to call a story where a title is needed (lists). The title when there
 * is one; otherwise, as on the site: the summary, a caption, the first line of
 * text, the start of a transcript, or the kind and date.
 */
val JsonObject.label: String get() {
	str("title")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Untitled", ignoreCase = true) }?.let { return it }
	clipLabel(str("summary"))?.let { return it }
	val bs = blocks
	bs.firstNotNullOfOrNull { b -> clipLabel(b.str("caption")) ?: if (b.str("type") == "dump") b.objs("photos").firstNotNullOfOrNull { clipLabel(it.str("caption")) } else null }?.let { return it }
	bs.firstNotNullOfOrNull { b -> if (b.str("type") in setOf("text", "pull", "aside")) clipLabel(b.str("body")) else null }?.let { return it }
	bs.firstNotNullOfOrNull { b -> if (b.str("type") == "audio") clipLabel(b.str("transcript")) else null }?.let { return it }
	val kind = when (bs.firstOrNull { it.str("type") != "divider" }?.str("type")) {
		"photo" -> "Photo"
		"dump", "gallery", "diptych" -> "Photos"
		"audio" -> "Recording"
		"video" -> "Video"
		else -> "Note"
	}
	val day = runCatching { LocalDate.parse(str("date") ?: "").format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) }.getOrNull()
	return if (day != null) "$kind · $day" else kind
}

/**
 * Add photos the way a trip wants them: into the existing dump if there is
 * one; otherwise, once there are three or more photos in total (counting
 * loose photo blocks already in the story), gather them all into one dump
 * where the first photo sat; below that, as photo blocks.
 */
fun JsonObject.addPhotos(photos: List<JsonObject>): JsonObject {
	if (photos.isEmpty()) return this
	val dump = blocks.lastOrNull { it.str("type") == "dump" }
	if (dump != null) return updateBlock(dump.str("id")!!) { d -> d.with("photos", JsonArray(d.objs("photos") + photos)) }

	val loose = blocks.filter { it.str("type") == "photo" }
	if (loose.size + photos.size >= 3) {
		val merged = loose.map { b -> StoryDoc.dumpPhoto(b.str("imageId") ?: "", b.int("w"), b.int("h"), b.str("caption")).with("exif", b["exif"]) } + photos
		val firstIdx = blocks.indexOfFirst { it.str("type") == "photo" }
		val rest = blocks.filterNot { it.str("type") == "photo" }
		val at = if (firstIdx < 0) rest.size else minOf(firstIdx, rest.size)
		return withBlocks(rest.take(at) + StoryDoc.dumpBlock(merged) + rest.drop(at))
	}
	return photos.fold(this) { doc, p -> doc.appendBlock(StoryDoc.photoBlock(p.str("imageId")!!, p.str("caption")).with("w", p.int("w")).with("h", p.int("h")).with("exif", p["exif"])) }
}

/**
 * Where a photo in this story was taken, for "use the photo's location": the
 * first photo that knows. A dump photo's chosen location (`coords`) counts as
 * well as what any photo's file said (`exif`).
 */
fun JsonObject.photoLocation(): Coords? {
	fun JsonObject?.place(): Coords? { val lat = this?.dbl("lat") ?: return null; val lon = this?.dbl("lon") ?: return null; return Coords(lat, lon) }
	for (b in blocks) when (b.str("type")) {
		"photo" -> b.obj("exif").place()?.let { return it }
		"dump" -> for (p in b.objs("photos")) (p.obj("coords").place() ?: p.obj("exif").place())?.let { return it }
	}
	return null
}

/** Update one photo inside a dump block by index. */
fun JsonObject.updateDumpPhoto(blockId: String, index: Int, f: (JsonObject) -> JsonObject): JsonObject =
	updateBlock(blockId) { d -> d.with("photos", JsonArray(d.objs("photos").mapIndexed { i, p -> if (i == index) f(p) else p })) }

fun JsonObject.removeDumpPhoto(blockId: String, index: Int): JsonObject =
	updateBlock(blockId) { d -> d.with("photos", JsonArray(d.objs("photos").filterIndexed { i, _ -> i != index })) }

/** 📷 / 🎙 / 🎬 counts for list rows. */
data class MediaCounts(val photos: Int, val audio: Int, val video: Int)
fun JsonObject.mediaCounts(): MediaCounts {
	var p = 0; var a = 0; var v = 0
	for (b in blocks) when (b.str("type")) {
		"photo" -> p++
		"diptych" -> p += 2
		"gallery" -> p += b.objs("items").size
		"dump" -> { val ph = b.objs("photos"); p += ph.size; a += ph.count { it.obj("audio") != null } }
		"audio" -> a++
		"video" -> v++
	}
	return MediaCounts(p, a, v)
}

/** Every string value equal to a `pending:` placeholder, anywhere in the document. */
fun JsonObject.placeholders(): Set<String> {
	val out = mutableSetOf<String>()
	fun walk(e: JsonElement) {
		when (e) {
			is JsonObject -> e.values.forEach(::walk)
			is JsonArray -> e.forEach(::walk)
			is JsonPrimitive -> e.contentOrNull?.let { if (PendingMedia.isPlaceholder(it)) out += it }
		}
	}
	walk(this)
	return out
}

/**
 * Deep-map every object in the document. [f] receives each JsonObject (after
 * its children were mapped) and returns a replacement. Used to swap a
 * placeholder for the real server value once media is uploaded.
 */
fun JsonObject.mapObjects(f: (JsonObject) -> JsonObject): JsonObject {
	fun walk(e: JsonElement): JsonElement = when (e) {
		is JsonObject -> f(JsonObject(e.mapValues { (_, v) -> walk(v) }))
		is JsonArray -> JsonArray(e.map(::walk))
		else -> e
	}
	return walk(this) as JsonObject
}

/** What goes over the wire: the document minus server-owned timestamps. */
fun JsonObject.forServer(): JsonObject = JsonObject(this - "createdAt" - "updatedAt")
