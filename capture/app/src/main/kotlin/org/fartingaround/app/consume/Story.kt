package org.fartingaround.app.consume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The item-level `_fartingaround` object (spec: docs/feed-extension.md v1),
 * decoded by hand so unknown fields and block types are skipped rather than
 * failing the whole story. Media URLs are built against [base], which the
 * repository has already checked is the feed's own origin.
 */
data class Story(
	val base: String,
	val slug: String,
	val date: String,
	val location: String?,
	val coords: Coords?,
	val weather: Weather?,
	val astro: Astro?,
	val coverImageId: String?,
	val coverFocus: Focus?,
	val blocks: List<Block>,
	/**
	 * Set for a private story from a personal feed: the reader token from
	 * the feed URL, which every media request for this story must carry
	 * (docs/feed-extension.md, "Private stories and personal feeds").
	 */
	val token: String? = null,
	/**
	 * Media that should load from this phone instead of the site: an image id or
	 * an audio/video block id → a file:// URI. Only the draft preview sets it,
	 * for photos and recordings that have not been uploaded yet.
	 */
	val local: Map<String, String> = emptyMap(),
	/** Request headers for this story's audio and video. Only the draft preview sets it (the capture token). */
	val mediaHeaders: Map<String, String> = emptyMap()
) {
	/** The first block that carries content: what a note (a story with no title) shows where it is listed. */
	val lead: Block? get() = blocks.firstOrNull { it !is Block.Divider }

	private val q: String get() = token?.let { "?token=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""

	fun img(id: String, params: String) = local[id] ?: "$base/img/$id/$params$q"
	fun audio(blockId: String) = local[blockId] ?: "$base/api/stories/$slug/audio/$blockId$q"
	fun video(blockId: String) = local[blockId] ?: "$base/api/stories/$slug/video/$blockId$q"
	fun r2(r: R2, width: Int) = "$base/media/${r.base}/w${r.widths.minByOrNull { kotlin.math.abs(it - width) } ?: r.widths.last()}.jpg$q"

	data class Coords(val lat: Double, val lon: Double)
	data class Focus(val x: Double, val y: Double)
	data class Weather(val tempC: Double?, val tempF: Double?, val conditions: String?)
	data class Astro(val moonPhase: Double?, val moonPhaseLabel: String?, val moonIllumination: Double?)
	data class R2(val base: String, val widths: List<Int>, val ext: String, val w: Int?, val h: Int?)
	data class Pic(val imageId: String?, val r2: R2?, val caption: String?, val alt: String?, val annotationId: String? = null, val overlayId: String? = null, val starlessId: String? = null)
	/** One transcript word with its start and end, in seconds into the clip. */
	data class Word(val t: String, val s: Double, val e: Double)
	/** A dump photo's spoken caption; the clip plays from [Story.audio] with this id. */
	data class PhotoAudio(val id: String, val durationMs: Long?, val transcript: String?, val words: List<Word>)
	data class DumpPhoto(val imageId: String, val w: Int?, val h: Int?, val caption: String?, val alt: String?, val takenAt: String?, val coords: Coords?, /** Title of a section starting at this photo. */ val section: String? = null, val audio: PhotoAudio? = null)

	sealed class Block(val id: String) {
		class Text(id: String, val body: String) : Block(id)
		class Photo(id: String, val imageId: String, val caption: String?, val alt: String?, val size: String?, val aspect: Float?) : Block(id)
		class Diptych(id: String, val left: Pic, val right: Pic, val caption: String?, val aspect: Float?) : Block(id)
		class Gallery(id: String, val items: List<Pic>, val caption: String?, val aspect: Float?, val size: String? = null) : Block(id)
		class Dump(id: String, val photos: List<DumpPhoto>, val caption: String?, val rowSize: Int, val slideshowSeconds: Int, val map: String) : Block(id)
		class Pull(id: String, val body: String) : Block(id)
		class Aside(id: String, val body: String, val label: String?) : Block(id)
		class Divider(id: String, val place: String?, val date: String?) : Block(id)
		class Audio(id: String, val mimeType: String?, val durationMs: Long?, val caption: String?, val transcript: String?, val words: List<Word> = emptyList()) : Block(id)
		class Video(id: String, val mimeType: String?, val durationMs: Long?, val caption: String?, val posterImageId: String?, val aspect: Float?) : Block(id)
	}

	companion object {
		private val json = Json { ignoreUnknownKeys = true; isLenient = true }

		/**
		 * A story with no title is a note: no headline, no cover, its content shown
		 * where it is listed (specs/notes-titleless-posts.md). "Untitled" is what a
		 * missing title used to be filled in with, so it counts as none.
		 */
		fun isNoteTitle(title: String?): Boolean = title.isNullOrBlank() || title.trim().equals("Untitled", ignoreCase = true)

		/** [feedUrl] is where the item came from; a personal feed's URL carries the reader token. */
		fun parse(extensionJson: String, base: String, feedUrl: String? = null): Story? = runCatching {
			val o = json.parseToJsonElement(extensionJson).jsonObject
			val isPrivate = (o["private"] as? JsonPrimitive)?.booleanOrNull == true
			Story(
				base = base.trimEnd('/'),
				slug = o.str("slug") ?: return null,
				date = o.str("date") ?: "",
				location = o.str("location"),
				coords = o.obj("coords")?.coords(),
				weather = o.obj("weather")?.let { Weather(it.num("tempC"), it.num("tempF"), it.str("conditions")) },
				astro = o.obj("astro")?.let { Astro(it.num("moonPhase"), it.str("moonPhaseLabel"), it.num("moonIllumination")) },
				// The site stores "no cover" as an empty string.
				coverImageId = o.str("coverImageId")?.takeIf { it.isNotBlank() },
				coverFocus = o.obj("coverFocus")?.let { f -> Focus(f.num("x") ?: 0.5, f.num("y") ?: 0.5) },
				blocks = (o["blocks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::block) } ?: emptyList(),
				token = if (isPrivate) feedUrl?.toHttpUrlOrNull()?.queryParameter("token") else null
			)
		}.getOrNull()

		/**
		 * A story straight from the phone's working copy, for the draft preview.
		 * The document has the same shape as the feed extension; a draft that has
		 * never been synced has no slug yet, so one is filled in.
		 */
		fun preview(doc: JsonObject, base: String, slug: String?, local: Map<String, String>, mediaHeaders: Map<String, String> = emptyMap()): Story? =
			parse(JsonObject(doc + ("slug" to JsonPrimitive(slug ?: "draft"))).toString(), base)?.copy(local = local, mediaHeaders = mediaHeaders)

		private fun block(b: JsonObject): Block? {
			val id = b.str("id") ?: return null
			return when (b.str("type")) {
				"text" -> Block.Text(id, b.str("body") ?: "")
				"photo" -> Block.Photo(id, b.str("imageId") ?: return null, b.str("caption"), b.str("alt"), b.str("size"), b.aspect())
				"diptych" -> Block.Diptych(id, b.obj("left")?.pic() ?: return null, b.obj("right")?.pic() ?: return null, b.str("caption"), b.aspect())
				"gallery" -> Block.Gallery(id, b.arr("items").mapNotNull { (it as? JsonObject)?.pic() }, b.str("caption"), b.aspect(), b.str("size"))
				"dump" -> Block.Dump(
					id,
					b.arr("photos").mapNotNull { (it as? JsonObject)?.dumpPhoto() },
					b.str("caption"), b.int("rowSize") ?: 3, b.int("slideshowSeconds") ?: 4, b.str("map") ?: "none"
				)
				"pull" -> Block.Pull(id, b.str("body") ?: "")
				"aside" -> Block.Aside(id, b.str("body") ?: "", b.str("label"))
				"divider" -> Block.Divider(id, b.str("place"), b.str("date"))
				"audio" -> Block.Audio(
					id, b.str("mimeType"), b.long("durationMs"), b.str("caption"), b.str("transcript"),
					b.words()
				)
				"video" -> Block.Video(id, b.str("mimeType"), b.long("durationMs"), b.str("caption"), b.str("posterImageId"), b.aspect())
				else -> null // unknown block types are skipped, per spec
			}
		}

		private fun JsonObject.pic(): Pic? {
			val r2 = obj("r2")?.let { r -> R2(r.str("base") ?: return@let null, r.arr("widths").mapNotNull { (it as? JsonPrimitive)?.intOrNull }, r.str("ext") ?: "jpg", r.int("w"), r.int("h")) }
			val imageId = str("imageId")
			if (imageId == null && r2 == null) return null
			return Pic(imageId, r2, str("caption"), str("alt"), str("annotationId"), str("overlayId"), str("starlessId"))
		}
		private fun JsonObject.dumpPhoto(): DumpPhoto? {
			val imageId = str("imageId") ?: return null
			return DumpPhoto(imageId, int("w"), int("h"), str("caption"), str("alt"), str("takenAt"), obj("coords")?.coords(), str("section")?.trim()?.takeIf { it.isNotEmpty() },
				obj("audio")?.let { a -> a.str("id")?.let { id -> PhotoAudio(id, a.long("durationMs"), a.str("transcript"), a.words()) } })
		}
		/** `words: [{ t, s, e }]` on an audio block or a dump photo's audio; entries missing a field are skipped. */
		private fun JsonObject.words(): List<Word> = arr("words").mapNotNull { w ->
			(w as? JsonObject)?.let { o -> Word(o.str("t") ?: return@let null, o.num("s") ?: return@let null, o.num("e") ?: return@let null) }
		}
		private fun JsonObject.coords(): Coords? {
			val lat = num("lat") ?: return null; val lon = num("lon") ?: return null
			return Coords(lat, lon)
		}
		/** "4/5" → 0.8 (width/height). */
		private fun JsonObject.aspect(): Float? = str("aspect")?.split('/')?.takeIf { it.size == 2 }
			?.let { (w, h) -> w.trim().toFloatOrNull()?.let { a -> h.trim().toFloatOrNull()?.let { b -> if (b > 0) a / b else null } } }

		private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
		private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
		private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
		private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
		private fun JsonObject.obj(k: String) = this[k] as? JsonObject
		private fun JsonObject.arr(k: String) = (this[k] as? JsonArray) ?: JsonArray(emptyList())
	}
}
