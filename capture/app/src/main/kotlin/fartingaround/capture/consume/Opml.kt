package fartingaround.capture.consume

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * OPML 2.0 — the thirty-year-old standard for subscription lists. Export so
 * the list can go to another reader or a cloud drive; import to bring one in
 * (from Feedly, NetNewsWire, anything). Flat: folders are read but ignored.
 */
object Opml {
	data class Outline(val xmlUrl: String, val title: String, val htmlUrl: String?)

	/**
	 * A personal feed link (`…?token=…`) from a site that invited this
	 * reader. It is a key to that site's private stories, so it must never
	 * be published: it is left out of the backup that feeds the public
	 * blogroll. A file the user exports for themselves keeps it.
	 */
	fun isPersonal(feedUrl: String): Boolean {
		// Same rule as the site (src/lib/feed-privacy.js): anything unparseable counts as a key.
		val u = feedUrl.toHttpUrlOrNull() ?: return true
		if (u.username.isNotEmpty() || u.password.isNotEmpty()) return true
		return u.queryParameterNames.any { it.lowercase() in SECRET_PARAMS }
	}

	private val SECRET_PARAMS = setOf(
		"token", "access_token", "auth", "auth_token", "key", "api_key", "apikey",
		"secret", "sig", "signature", "password", "pass", "pw"
	)

	fun export(feeds: List<Feed>, ownerName: String? = null): String {
		val sb = StringBuilder()
		sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
		sb.append("""<opml version="2.0">""").append('\n')
		sb.append("  <head>\n")
		sb.append("    <title>Subscriptions</title>\n")
		sb.append("    <dateCreated>").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("</dateCreated>\n")
		if (ownerName != null) sb.append("    <ownerName>").append(esc(ownerName)).append("</ownerName>\n")
		sb.append("  </head>\n  <body>\n")
		for (f in feeds) {
			sb.append("""    <outline type="rss" text="${esc(f.title)}" title="${esc(f.title)}" xmlUrl="${esc(f.url)}"""")
			if (f.siteUrl != null) sb.append(""" htmlUrl="${esc(f.siteUrl)}"""")
			sb.append(" />\n")
		}
		sb.append("  </body>\n</opml>\n")
		return sb.toString()
	}

	fun import(xml: String): List<Outline> {
		val p = XmlPullParserFactory.newInstance().newPullParser()
		p.setInput(StringReader(xml))
		val out = mutableListOf<Outline>()
		var event = p.eventType
		while (event != XmlPullParser.END_DOCUMENT) {
			if (event == XmlPullParser.START_TAG && p.name == "outline") {
				val xmlUrl = p.getAttributeValue(null, "xmlUrl")
				if (!xmlUrl.isNullOrBlank()) {
					val title = p.getAttributeValue(null, "title") ?: p.getAttributeValue(null, "text") ?: xmlUrl
					out += Outline(xmlUrl.trim(), title, p.getAttributeValue(null, "htmlUrl"))
				}
			}
			event = p.next()
		}
		return out.distinctBy { it.xmlUrl }
	}

	private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
