package fartingaround.capture.consume

import fartingaround.capture.model.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parses the three feed formats into one shape. Hand-rolled on XmlPullParser
 * (in the platform) rather than a library: we need a dozen fields and the
 * tolerant handling matters more than completeness — real-world feeds are
 * sloppy, so unknown elements are skipped and missing dates fall back.
 */
object FeedParser {

	data class ParsedFeed(
		val title: String,
		val siteUrl: String?,
		val description: String?,
		val iconUrl: String?,
		val format: String,
		/** Feed-level `_fartingaround` object, when this is a JSON Feed carrying the extension. */
		val extension: JsonObject?,
		val items: List<ParsedItem>
	)

	data class ParsedItem(
		val guid: String,
		val url: String?,
		val title: String,
		val author: String?,
		val summary: String?,
		val contentHtml: String?,
		val imageUrl: String?,
		val publishedAt: Long?,
		/** Item-level `_fartingaround` object, verbatim JSON — or an atlas plate reference, wrapped by [AtlasRef.wrap]. */
		val extensionJson: String?
	)

	/** Sniff the body and dispatch. Throws on anything unparseable. */
	fun parse(body: String, contentType: String?): ParsedFeed {
		val trimmed = body.trimStart('﻿', ' ', '\n', '\r', '\t')
		return when {
			trimmed.startsWith("{") || contentType?.contains("json") == true -> parseJsonFeed(trimmed)
			else -> parseXml(trimmed)
		}
	}

	// ── JSON Feed ───────────────────────────────────────────────────────────

	private fun parseJsonFeed(body: String): ParsedFeed {
		val root = AppJson.parseToJsonElement(body).jsonObject
		fun str(o: JsonObject, k: String) = o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
		val items = (root["items"] as? JsonArray ?: JsonArray(emptyList())).map { el ->
			val o = el.jsonObject
			val id = str(o, "id") ?: str(o, "url") ?: str(o, "title") ?: ""
			ParsedItem(
				guid = id,
				url = str(o, "url") ?: str(o, "external_url"),
				title = str(o, "title") ?: "Untitled",
				author = (o["authors"] as? JsonArray)?.firstOrNull()?.jsonObject?.let { str(it, "name") } ?: o["author"]?.jsonObject?.let { str(it, "name") },
				summary = str(o, "summary"),
				contentHtml = str(o, "content_html") ?: str(o, "content_text")?.let { "<p>${escape(it)}</p>" },
				imageUrl = str(o, "image") ?: str(o, "banner_image"),
				publishedAt = parseDate(str(o, "date_published")),
				// A story's blocks, or — from an atlas feed — the plate this item is (AtlasRef).
				extensionJson = o["_fartingaround"]?.let { it.jsonObject.toString() }
					?: (o["_atlas"] as? JsonObject)?.let { AtlasRef.wrap(it) }
			)
		}
		return ParsedFeed(
			title = str(root, "title") ?: "Untitled feed",
			siteUrl = str(root, "home_page_url"),
			description = str(root, "description"),
			iconUrl = str(root, "icon") ?: str(root, "favicon"),
			format = "json",
			extension = root["_fartingaround"] as? JsonObject,
			items = items
		)
	}

	// ── RSS 2.0 / Atom ──────────────────────────────────────────────────────

	private fun parseXml(body: String): ParsedFeed {
		val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
		p.setInput(StringReader(body))
		var feedTitle = "Untitled feed"
		var siteUrl: String? = null
		var description: String? = null
		var iconUrl: String? = null
		var format = "rss"
		val items = mutableListOf<ParsedItem>()

		var event = p.eventType
		while (event != XmlPullParser.END_DOCUMENT) {
			if (event == XmlPullParser.START_TAG) {
				when (p.name) {
					"feed" -> format = "atom"
					"item", "entry" -> items += parseEntry(p, format)
					"title" -> if (p.depth <= 3) feedTitle = p.nextText().trim().ifEmpty { feedTitle }
					"link" -> if (p.depth <= 3) {
						if (format == "atom") {
							val rel = p.getAttributeValue(null, "rel") ?: "alternate"
							val href = p.getAttributeValue(null, "href")
							if (rel == "alternate" && href != null) siteUrl = href
						} else {
							siteUrl = p.nextText().trim()
						}
					}
					"description", "subtitle" -> if (p.depth <= 3) description = p.nextText().trim()
					"image" -> if (p.depth <= 3 && format == "rss") iconUrl = readRssImage(p)
					"icon", "logo" -> if (p.depth <= 3 && format == "atom") iconUrl = p.nextText().trim()
				}
			}
			event = p.next()
		}
		return ParsedFeed(feedTitle, siteUrl, description, iconUrl, format, null, items)
	}

	/** Reads one <item>/<entry>, leaving the parser on its END_TAG. */
	private fun parseEntry(p: XmlPullParser, format: String): ParsedItem {
		val entryTag = p.name
		val entryDepth = p.depth
		var guid: String? = null
		var url: String? = null
		var title = "Untitled"
		var author: String? = null
		var summary: String? = null
		var content: String? = null
		var image: String? = null
		var date: Long? = null

		var event = p.next()
		while (!(event == XmlPullParser.END_TAG && p.name == entryTag && p.depth == entryDepth)) {
			if (event == XmlPullParser.START_TAG && p.depth == entryDepth + 1) {
				val ns = p.namespace ?: ""
				when (p.name) {
					"title" -> title = p.nextText().trim().ifEmpty { title }
					"guid", "id" -> guid = p.nextText().trim()
					"link" -> if (format == "atom") {
						val rel = p.getAttributeValue(null, "rel") ?: "alternate"
						val href = p.getAttributeValue(null, "href")
						if (rel == "alternate" && href != null) url = href
						if (rel == "enclosure" && (p.getAttributeValue(null, "type") ?: "").startsWith("image/") && href != null && image == null) image = href
						skip(p)
					} else url = p.nextText().trim()
					"description", "summary" -> summary = p.nextText()
					"encoded" -> if (ns.contains("content")) content = p.nextText() else skip(p) // content:encoded
					"content" -> if (format == "atom") content = p.nextText() else if (ns.contains("mrss") || ns.contains("media")) {
						val u = p.getAttributeValue(null, "url"); if (u != null && image == null) image = u; skip(p)
					} else skip(p)
					"author" -> author = if (format == "atom") readAtomAuthor(p) else p.nextText().trim()
					"creator" -> author = p.nextText().trim() // dc:creator
					"pubDate", "published", "updated", "date" -> {
						val d = parseDate(p.nextText().trim())
						if (d != null && (date == null || p.name != "updated")) date = d
					}
					"enclosure" -> {
						val type = p.getAttributeValue(null, "type") ?: ""
						val u = p.getAttributeValue(null, "url")
						if (type.startsWith("image/") && u != null && image == null) image = u
						skip(p)
					}
					"thumbnail" -> { // media:thumbnail
						val u = p.getAttributeValue(null, "url"); if (u != null) image = u; skip(p)
					}
					else -> skip(p)
				}
			}
			event = p.next()
		}
		val html = content ?: summary
		// Many feeds put the only text in <description>; if it's HTML, use it as content too.
		val summaryText = (summary ?: content)?.let { stripTags(it).trim().take(300) }
		return ParsedItem(
			guid = guid ?: url ?: (title + date),
			url = url,
			title = title,
			author = author?.takeIf { it.isNotBlank() },
			summary = summaryText,
			contentHtml = html?.takeIf { it.isNotBlank() },
			imageUrl = image ?: html?.let { firstImg(it) },
			publishedAt = date,
			extensionJson = null
		)
	}

	private fun readRssImage(p: XmlPullParser): String? {
		val depth = p.depth
		var url: String? = null
		var event = p.next()
		while (!(event == XmlPullParser.END_TAG && p.depth == depth)) {
			if (event == XmlPullParser.START_TAG && p.name == "url") url = p.nextText().trim()
			else if (event == XmlPullParser.START_TAG) skip(p)
			event = p.next()
		}
		return url
	}

	private fun readAtomAuthor(p: XmlPullParser): String? {
		val depth = p.depth
		var name: String? = null
		var event = p.next()
		while (!(event == XmlPullParser.END_TAG && p.depth == depth)) {
			if (event == XmlPullParser.START_TAG && p.name == "name") name = p.nextText().trim()
			else if (event == XmlPullParser.START_TAG) skip(p)
			event = p.next()
		}
		return name
	}

	/** Skip the current element and everything inside it. */
	private fun skip(p: XmlPullParser) {
		if (p.eventType != XmlPullParser.START_TAG) return
		var depth = 1
		while (depth > 0) {
			when (p.next()) {
				XmlPullParser.END_TAG -> depth--
				XmlPullParser.START_TAG -> depth++
			}
		}
	}

	// ── Dates ───────────────────────────────────────────────────────────────

	private val rfc1123Lenient = DateTimeFormatter.ofPattern("[EEE, ]d MMM yyyy HH:mm[:ss] [zzz][XX][X]", Locale.US)

	/** RFC 822/1123 (RSS), ISO-8601 (Atom, JSON Feed), and a few sloppy variants. */
	fun parseDate(s: String?): Long? {
		if (s.isNullOrBlank()) return null
		val t = s.trim()
		runCatching { return Instant.parse(t).toEpochMilli() }
		runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
		runCatching { return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
		runCatching { return ZonedDateTime.parse(t, rfc1123Lenient).toInstant().toEpochMilli() }
		// Date only ("2026-05-01")
		runCatching { return Instant.parse("${t.take(10)}T12:00:00Z").toEpochMilli() }
		return null
	}

	// ── Text helpers ────────────────────────────────────────────────────────

	private val tagRe = Regex("<[^>]+>")
	private val imgRe = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

	fun stripTags(html: String): String =
		html.replace(tagRe, " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ")

	private fun firstImg(html: String): String? = imgRe.find(html)?.groupValues?.get(1)

	private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
