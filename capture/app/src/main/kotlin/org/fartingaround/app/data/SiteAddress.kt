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
