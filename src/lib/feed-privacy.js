/**
 * Feed links that are really keys. A personal feed link (`…/feed.json?token=…`)
 * lets whoever holds it read someone's private stories, so it must never
 * appear in the public Following list, however it got into the subscription
 * list. Pure functions: tested in tests/feed-privacy.test.js.
 */

/** Query parameters that mean "this URL carries a credential". */
const SECRET_PARAMS = new Set([
	'token', 'access_token', 'auth', 'auth_token', 'key', 'api_key', 'apikey',
	'secret', 'sig', 'signature', 'password', 'pass', 'pw'
]);

/**
 * True when a feed URL carries a credential: a secret-looking query
 * parameter, or a username/password before the host. Anything that cannot
 * be parsed as a URL counts as secret, so it is left out rather than shown.
 * @param {string} url
 */
export function isSecretFeedUrl(url) {
	let u;
	try {
		u = new URL(url);
	} catch {
		return true;
	}
	if (u.username || u.password) return true;
	for (const name of u.searchParams.keys()) if (SECRET_PARAMS.has(name.toLowerCase())) return true;
	return false;
}

/** @param {string} s */
function decodeEntities(s) {
	return s
		.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#39;/g, "'")
		.replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
		.replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
		.replace(/&amp;/g, '&');
}

/**
 * Remove every feed whose link is a key from an OPML document, leaving the
 * rest (and any folders) as they were.
 * @param {string} opml
 */
export function stripSecretFeeds(opml) {
	return opml.replace(/[ \t]*<outline\b([^>]*?)(\/>|>\s*<\/outline>)[ \t]*\r?\n?/gi, (whole, attrs) => {
		const m = attrs.match(/\bxmlUrl\s*=\s*"([^"]*)"/i) || attrs.match(/\bxmlUrl\s*=\s*'([^']*)'/i);
		return m && isSecretFeedUrl(decodeEntities(m[1])) ? '' : whole;
	});
}
