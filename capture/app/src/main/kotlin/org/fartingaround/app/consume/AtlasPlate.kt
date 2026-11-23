package org.fartingaround.app.consume

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private val atlasJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.obj(k: String) = this[k] as? JsonObject
private fun JsonObject.arr(k: String) = (this[k] as? JsonArray) ?: JsonArray(emptyList())
private fun JsonObject.objects(k: String) = arr(k).mapNotNull { it as? JsonObject }

/**
 * The item-level `_atlas` object of an Outer Spiral Arm–style atlas feed
 * (its docs/reader-api.md, v1): this item is a plate, and [plateUrl] is where
 * its reader document lives. Stored in `Item.extensionJson` wrapped as
 * `{"_atlas": {…}}`, which a `_fartingaround` story can never be mistaken for.
 */
data class AtlasRef(
	val slug: String,
	val plateUrl: String,
	val thumb: String?,
	val ra: Double?,
	val dec: Double?,
	val fovDeg: Double?
) {
	companion object {
		private const val KEY = "_atlas"

		/** What the feed parser stores for an item that carries the extension. */
		fun wrap(itemExtension: JsonObject): String = JsonObject(mapOf(KEY to itemExtension)).toString()

		/**
		 * The plate an item points at, or null when it is not a plate — or when
		 * the plate document is not on the feed's own origin, which is the only
		 * place the feed is trusted to send us.
		 */
		fun parse(extensionJson: String?, feedUrl: String): AtlasRef? = runCatching {
			val o = atlasJson.parseToJsonElement(extensionJson ?: return null).jsonObject.obj(KEY) ?: return null
			val plate = o.str("plate") ?: return null
			if (!sameOrigin(plate, feedUrl)) return null
			AtlasRef(o.str("slug") ?: return null, plate, o.str("thumb"), o.num("ra"), o.num("dec"), o.num("fovDeg"))
		}.getOrNull()

		private fun sameOrigin(a: String, b: String): Boolean = runCatching {
			val ua = java.net.URI(a); val ub = java.net.URI(b)
			ua.scheme == ub.scheme && ua.host.equals(ub.host, ignoreCase = true) && ua.port == ub.port
		}.getOrDefault(false)
	}
}

/**
 * One plate as the atlas's reader API sends it: the images of each version,
 * every annotation with its mark sized and its info card written, the spoken
 * note. Geometry is fractions of the frame — [Annotation.x]/[Annotation.y]
 * of its width and height, radii of its WIDTH — so nothing here is in pixels
 * except [width] × [height], which only give the plate's shape.
 */
data class AtlasPlate(
	val slug: String,
	val title: String,
	val subtitle: String?,
	val plateNo: Int?,
	val notes: String?,
	val url: String?,
	val width: Int,
	val height: Int,
	val facts: List<Fact>,
	val live: String,
	val versions: List<Version>,
	val annotations: List<Annotation>,
	val audio: Audio?,
	/** The explanations this plate's cards point at, by [GlossRef.key]. */
	val glossary: Map<String, Gloss> = emptyMap()
) {
	/** A word on a card that opens an explanation: [key] into [glossary]; [forThis] is the sentence written from this object's own value. */
	data class GlossRef(val key: String, val forThis: String?)
	/** One explanation, in the atlas's voice: paragraphs, and sometimes a link to read further. */
	data class Gloss(val title: String, val body: List<String>, val moreLabel: String?, val moreUrl: String?)
	/** [gloss] explains the label; [valueGloss] the value (only an object's "Type" has one). Both null on the plate's own facts. */
	data class Fact(val label: String, val value: String, val gloss: GlossRef? = null, val valueGloss: GlossRef? = null)
	/** One segment of a card's eyebrow ("Messier object · the classic catalogues" is two). */
	data class Part(val text: String, val gloss: GlossRef?)
	data class Images(val src: String?, val thumb: String?, val full: String?, val starless: String?)
	/** A version's own pixel size, when it is a different crop from the plate's. */
	data class Frame(val width: Int, val height: Int)
	/**
	 * [frame] and [annotations] are null for a version that shares the plate's solve.
	 * [facts] are the observing-log rows while this version is on screen — its own
	 * subs and nights where it has them; null from an atlas that does not send them.
	 */
	data class Version(val id: String, val label: String, val image: Images, val frame: Frame?, val annotations: List<Annotation>?, val facts: List<Fact>? = null)
	data class Ellipse(val rx: Double, val ry: Double, val angle: Double)
	data class Wiki(val extract: String?, val url: String?)
	data class Card(val eyebrow: String?, val name: String, val blurb: String?, val facts: List<Fact>, val wiki: Wiki?, val eyebrowParts: List<Part> = emptyList())
	/** [tier] 0: always · 1: the quiet default · 2: only in "everything", its label only once zoomed in. */
	data class Annotation(
		val name: String, val cat: String?, val tier: Int,
		val x: Double, val y: Double, val lx: Double, val ly: Double,
		val r: Double?, val ellipse: Ellipse?, val card: Card
	)
	data class Audio(val src: String, val durationMs: Long?, val transcript: String?, val words: List<Story.Word>)

	/** Width over height. */
	val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1.5f

	companion object {
		/** The reader API version this app understands. */
		const val VERSION = 1

		fun parse(body: String): AtlasPlate? = runCatching {
			val o = atlasJson.parseToJsonElement(body).jsonObject
			if ((o.int("version") ?: 0) > VERSION) return null // a newer contract: let the caller fall back to the article
			val versions = o.objects("versions").mapNotNull { it.version() }
			if (versions.isEmpty()) return null
			AtlasPlate(
				slug = o.str("slug") ?: return null,
				title = o.str("title") ?: "Untitled plate",
				subtitle = o.str("subtitle")?.takeIf { it.isNotBlank() },
				plateNo = o.int("plateNo"),
				notes = o.str("notes")?.takeIf { it.isNotBlank() },
				url = o.str("url"),
				width = o.int("width") ?: 0,
				height = o.int("height") ?: 0,
				facts = o.objects("facts").mapNotNull { it.fact() },
				live = o.str("live") ?: versions.first().id,
				versions = versions,
				annotations = o.objects("annotations").mapNotNull { it.annotation() },
				audio = o.obj("audio")?.let { a ->
					Audio(
						a.str("src") ?: return@let null, a.long("durationMs"), a.str("transcript")?.takeIf { it.isNotBlank() },
						a.objects("words").mapNotNull { w -> Story.Word(w.str("t") ?: return@mapNotNull null, w.num("s") ?: return@mapNotNull null, w.num("e") ?: return@mapNotNull null) }
					)
				},
				glossary = o.obj("glossary")?.mapNotNull { (key, v) -> (v as? JsonObject)?.gloss()?.let { key to it } }?.toMap() ?: emptyMap()
			)
		}.getOrNull()

		/** Fetches and parses a plate document. Throws [IOException] with a line fit to show. */
		suspend fun fetch(http: OkHttpClient, url: String): AtlasPlate = withContext(Dispatchers.IO) {
			val req = Request.Builder().url(url).header("Accept", "application/json").build()
			http.newCall(req).execute().use { res ->
				if (!res.isSuccessful) throw IOException("the atlas answered ${res.code}")
				parse(res.body?.string() ?: "") ?: throw IOException("the atlas sent something this app cannot read")
			}
		}

		private fun JsonObject.fact(): Fact? {
			val label = str("label") ?: return null
			val value = str("value") ?: return null
			return Fact(label, value, obj("gloss")?.glossRef(), obj("valueGloss")?.glossRef())
		}

		private fun JsonObject.glossRef(): GlossRef? {
			val key = str("key") ?: return null
			return GlossRef(key, str("forThis")?.takeIf { it.isNotBlank() })
		}

		private fun JsonObject.gloss(): Gloss? {
			val title = str("title") ?: return null
			val body = arr("body").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
			val more = obj("more")
			return Gloss(title, body, more?.str("label"), more?.str("url"))
		}

		private fun JsonObject.part(): Part? {
			val text = str("text") ?: return null
			return Part(text, obj("gloss")?.glossRef())
		}

		private fun JsonObject.version(): Version? {
			val img = obj("image") ?: return null
			val images = Images(img.str("src"), img.str("thumb"), img.str("full"), img.str("starless"))
			if (images.src == null) return null
			val id = str("id") ?: return null
			val frame = obj("frame")?.let { f -> Frame(f.int("width") ?: return@let null, f.int("height") ?: return@let null) }?.takeIf { it.width > 0 && it.height > 0 }
			val own = (this["annotations"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.annotation() }
			val rows = (this["facts"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.fact() }
			return Version(id, str("label") ?: id, images, frame, own, rows)
		}

		private fun JsonObject.annotation(): Annotation? {
			val name = str("name") ?: return null
			val x = num("x") ?: return null
			val y = num("y") ?: return null
			val c = obj("card")
			val card = Card(
				eyebrow = c?.str("eyebrow"),
				name = c?.str("name") ?: name,
				blurb = c?.str("blurb"),
				facts = c?.objects("facts")?.mapNotNull { it.fact() } ?: emptyList(),
				wiki = c?.obj("wiki")?.let { w -> Wiki(w.str("extract"), w.str("url")) }?.takeIf { it.extract != null || it.url != null },
				eyebrowParts = c?.objects("eyebrowParts")?.mapNotNull { it.part() } ?: emptyList()
			)
			val ellipse = obj("ellipse")?.let { e -> Ellipse(e.num("rx") ?: return@let null, e.num("ry") ?: return@let null, e.num("angle") ?: 0.0) }
			return Annotation(name, str("cat"), int("tier") ?: 0, x, y, num("lx") ?: x, num("ly") ?: y, num("r"), ellipse, card)
		}
	}
}
