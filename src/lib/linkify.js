/**
 * Turn markdown-style links `[text](url)` and bare URLs into anchor tags.
 * Input must already be HTML-escaped (`&`, `<`, `>`); output is HTML.
 */

/**
 * Allow http/https/mailto and site-relative hrefs; reject everything else
 * (javascript:, data:, etc.). Returns an attribute-safe href or null.
 * @param {string} url
 * @returns {string | null}
 */
const safeHref = (url) => {
	if (/^(https?:|mailto:)/i.test(url) || /^[/#.]/.test(url)) {
		return url.replace(/"/g, '&quot;');
	}
	return null;
};

/**
 * @param {string} href attribute-safe href
 * @param {string} text already-escaped link text
 */
const anchor = (href, text) => {
	const external = /^https?:/i.test(href);
	const attrs = external ? ' target="_blank" rel="noopener noreferrer"' : '';
	return `<a href="${href}"${attrs}>${text}</a>`;
};

// Alternation: markdown-style [text](url) first, then bare http(s) URLs.
// The bare-URL pattern won't swallow trailing punctuation like `.` or `)`.
const LINK_RE = /\[([^\]]+)\]\(([^)\s]+)\)|https?:\/\/[^\s<]*[^\s<.,:;!?)"'\]]/g;

/**
 * @param {string} escaped HTML-escaped plain text
 * @returns {string} HTML with links transformed into anchors
 */
export function linkify(escaped) {
	return escaped.replace(LINK_RE, (match, text, url) => {
		if (text !== undefined) {
			const href = safeHref(url);
			return href ? anchor(href, text) : match;
		}
		const href = safeHref(match);
		return href ? anchor(href, match) : match;
	});
}
