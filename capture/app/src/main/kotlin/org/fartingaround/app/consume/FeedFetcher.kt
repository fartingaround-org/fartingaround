package org.fartingaround.app.consume

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a feed over HTTP with conditional requests, and discovers a feed
 * URL from a site URL (looks for <link rel="alternate"> in the HTML, then
 * tries the usual well-known paths). JSON Feed is preferred when offered.
 */
class FeedFetcher(private val http: OkHttpClient) {

	sealed class Result {
		/** 304 — nothing changed. */
		data object NotModified : Result()
		data class Fetched(val parsed: FeedParser.ParsedFeed, val etag: String?, val lastModified: String?, val finalUrl: String) : Result()
	}

	private val ua = "fartingaround-app/0.1 (+https://github.com/fartingaround-org/fartingaround; feed reader)"

	suspend fun fetch(url: String, etag: String? = null, lastModified: String? = null): Result = withContext(Dispatchers.IO) {
		val req = Request.Builder().url(url)
			.header("User-Agent", ua)
			.header("Accept", "application/feed+json, application/json;q=0.9, application/rss+xml, application/atom+xml, application/xml;q=0.8, text/xml;q=0.8, */*;q=0.5")
			.apply {
				if (etag != null) header("If-None-Match", etag)
				if (lastModified != null) header("If-Modified-Since", lastModified)
			}
			.build()
		http.newCall(req).execute().use { res ->
			if (res.code == 304) return@withContext Result.NotModified
			if (!res.isSuccessful) throw FetchException("HTTP ${res.code}")
			val body = res.body?.string() ?: throw FetchException("empty response")
			val parsed = FeedParser.parse(body, res.header("Content-Type"))
			Result.Fetched(parsed, res.header("ETag"), res.header("Last-Modified"), res.request.url.toString())
		}
	}

	/**
	 * Turn whatever the user typed into a feed URL. Tries, in order: the URL
	 * as a feed; <link rel=alternate> tags in the page (JSON Feed first);
	 * /feed.json, /rss.xml, /feed, /atom.xml, /index.xml.
	 * Returns the feed URL plus its parsed contents (so adding is one round trip).
	 */
	suspend fun discover(input: String): Pair<String, FeedParser.ParsedFeed> = withContext(Dispatchers.IO) {
		var url = input.trim()
		if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"

		// 1. Is it already a feed?
		val first = getText(url)
		if (first != null && looksLikeFeed(first.body, first.contentType)) {
			return@withContext url to FeedParser.parse(first.body, first.contentType)
		}

		// 2. Autodiscovery links in the HTML.
		val candidates = mutableListOf<String>()
		if (first != null) {
			val base = first.finalUrl
			for (m in linkRe.findAll(first.body)) {
				val tag = m.value
				val type = attr(tag, "type")?.lowercase() ?: continue
				val href = attr(tag, "href") ?: continue
				if (!attr(tag, "rel").orEmpty().contains("alternate")) continue
				if (type.contains("feed+json") || type.contains("json")) candidates.add(0, resolve(base, href))
				else if (type.contains("rss") || type.contains("atom") || type.contains("xml")) candidates.add(resolve(base, href))
			}
		}
		// 3. Well-known paths.
		val origin = Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: url.trimEnd('/')
		for (path in listOf("/feed.json", "/rss.xml", "/feed", "/atom.xml", "/index.xml", "/feed.xml", "/rss")) candidates.add("$origin$path")

		for (c in candidates.distinct()) {
			val r = getText(c) ?: continue
			if (looksLikeFeed(r.body, r.contentType)) {
				val parsed = runCatching { FeedParser.parse(r.body, r.contentType) }.getOrNull() ?: continue
				return@withContext c to parsed
			}
		}
		throw FetchException("No feed found at $input")
	}

	private data class Text(val body: String, val contentType: String?, val finalUrl: String)

	private fun getText(url: String): Text? = runCatching {
		http.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { res ->
			if (!res.isSuccessful) return null
			val ct = res.header("Content-Type")
			// Don't slurp a multi-megabyte binary while probing.
			val body = res.body?.let { b -> b.source().use { src -> src.request(2_000_000); src.buffer.readUtf8() } } ?: return null
			Text(body, ct, res.request.url.toString())
		}
	}.getOrNull()

	private fun looksLikeFeed(body: String, contentType: String?): Boolean {
		val head = body.trimStart('﻿', ' ', '\n', '\r', '\t').take(600)
		if (head.startsWith("{")) return head.contains("jsonfeed.org")
		if (contentType?.contains("html") == true && !head.contains("<rss") && !head.contains("<feed")) return false
		return head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:RDF")
	}

	private val linkRe = Regex("""<link\s[^>]*>""", RegexOption.IGNORE_CASE)
	private fun attr(tag: String, name: String): String? =
		Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

	private fun resolve(base: String, href: String): String = runCatching {
		java.net.URI(base).resolve(href).toString()
	}.getOrDefault(href)
}

class FetchException(message: String) : Exception(message)
