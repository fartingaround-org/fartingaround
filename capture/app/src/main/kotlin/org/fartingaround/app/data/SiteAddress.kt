package org.fartingaround.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What someone typed as their site, as scheme://host[:port]. A bare
 * "example.org" becomes https; a pasted page link loses its path. Null when it
 * can't be read as a web address.
 */
fun normalizeSiteAddress(input: String): String? {
	val t = input.trim()
	if (t.isEmpty()) return null
	val withScheme = if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
	val url = withScheme.toHttpUrlOrNull() ?: return null
	val host = if (url.host.contains(':')) "[${url.host}]" else url.host
	val defaultPort = if (url.scheme == "https") 443 else 80
	return "${url.scheme}://$host" + if (url.port == defaultPort) "" else ":${url.port}"
}

/** What a scanned connect code holds: the site and the token to sign requests with. */
data class ConnectLink(val site: String, val token: String)

/**
 * Reads the link a site shows as a QR code under Admin → Settings → Phone app:
 *
 *     fartingaround://connect?site=https%3A%2F%2Fexample.org&token=…
 *
 * (written by src/lib/app-link.js on the site). Null for anything else, so a
 * code from somewhere else is never taken for one.
 */
fun parseConnectLink(raw: String): ConnectLink? {
	val t = raw.trim()
	val prefix = "fartingaround://connect?"
	if (!t.startsWith(prefix, ignoreCase = true)) return null
	val params = t.substring(prefix.length).split('&').mapNotNull { part ->
		val i = part.indexOf('=')
		if (i <= 0) null
		else runCatching { part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrNull()
	}.toMap()
	val site = normalizeSiteAddress(params["site"] ?: return null) ?: return null
	val token = params["token"]?.trim().orEmpty()
	if (token.isEmpty()) return null
	return ConnectLink(site, token)
}
