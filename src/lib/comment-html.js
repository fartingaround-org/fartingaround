import { linkify } from '#lib/linkify.js';

const escape = (/** @type {string} */ s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/**
 * A comment's plain text as safe HTML: paragraphs, line breaks, links.
 * @param {string} body
 */
export function commentHtml(body) {
	return body
		.split(/\n{2,}/)
		.map((p) => `<p>${linkify(escape(p)).replace(/\n/g, '<br>')}</p>`)
		.join('');
}

/** @param {string} iso */
export function commentDate(iso) {
	try { return new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' }); } catch { return iso; }
}
