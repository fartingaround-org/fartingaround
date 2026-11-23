/**
 * Render a story's blocks to plain, self-contained HTML for feeds — what an
 * ordinary RSS/JSON Feed reader shows. Every URL is absolute (feeds are read
 * off-site), images use a 1200px variant, and nothing depends on the site's
 * CSS or JavaScript. The aware reader ignores this and renders the blocks in
 * the `_fartingaround` extension natively instead.
 */

import { imageUrl } from '#lib/image.js';
import { linkify } from '#lib/linkify.js';

/** @param {string} s */
export function escapeHtml(s) {
	return String(s ?? '')
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;');
}

/**
 * @param {import('#lib/types.js').Story} story
 * @param {string} origin   e.g. https://fartingaround.fun
 */
export function storyToHtml(story, origin) {
	const img = (/** @type {string | undefined} */ id, /** @type {number} */ w = 1200) =>
		id ? origin + imageUrl(id, { w, q: 85 }) : '';
	const figure = (/** @type {string} */ src, /** @type {string | undefined} */ caption, /** @type {string | undefined} */ alt) =>
		src
			? `<figure><img src="${src}" alt="${escapeHtml(alt || caption || '')}" loading="lazy">${caption ? `<figcaption>${escapeHtml(caption)}</figcaption>` : ''}</figure>`
			: '';

	const parts = [];
	for (const b of story.blocks || []) {
		switch (b.type) {
			case 'text':
				parts.push(
					b.body
						.split(/\n{2,}/)
						.map((p) => p.trim())
						.filter(Boolean)
						.map((p) => `<p>${linkify(escapeHtml(p)).replace(/\n/g, '<br>')}</p>`)
						.join('\n')
				);
				break;
			case 'photo':
				parts.push(figure(img(b.imageId), b.caption, b.alt));
				break;
			case 'diptych':
				parts.push(
					`<div class="diptych">${figure(img(b.left?.imageId, 800), b.left?.caption, b.left?.alt)}${figure(img(b.right?.imageId, 800), b.right?.caption, b.right?.alt)}</div>` +
						(b.caption ? `<p><em>${escapeHtml(b.caption)}</em></p>` : '')
				);
				break;
			case 'gallery':
				parts.push(
					(b.items || [])
						.map((it) => figure(it.r2 ? `${origin}/media/${it.r2.base}/w${largest(it.r2.widths)}.jpg` : img(it.imageId), it.caption, it.alt))
						.join('\n') + (b.caption ? `<p><em>${escapeHtml(b.caption)}</em></p>` : '')
				);
				break;
			case 'dump':
				parts.push(
					(b.photos || [])
						.map(
							(p) =>
								(p.section ? `<h3>${escapeHtml(p.section)}</h3>` : '') +
								figure(img(p.imageId, 960), p.caption, p.alt) +
								(p.audio
									? `<p><audio controls preload="none" src="${origin}/api/stories/${story.slug}/audio/${p.audio.id}"></audio>` +
										(p.audio.transcript ? ` <em>${escapeHtml(p.audio.transcript)}</em>` : '') +
										`</p>`
									: '')
						)
						.join('\n') +
						(b.caption ? `<p><em>${escapeHtml(b.caption)}</em></p>` : '')
				);
				break;
			case 'pull':
				parts.push(`<blockquote><p>${escapeHtml(b.body)}</p></blockquote>`);
				break;
			case 'aside':
				parts.push(`<aside>${b.label ? `<strong>${escapeHtml(b.label)}</strong> ` : ''}${escapeHtml(b.body)}</aside>`);
				break;
			case 'divider':
				parts.push(`<hr>${b.place || b.date ? `<p><small>${escapeHtml([b.place, b.date].filter(Boolean).join(' · '))}</small></p>` : ''}`);
				break;
			case 'heading':
				if (b.body) parts.push(`<h2>${escapeHtml(b.body)}</h2>`);
				break;
			case 'code':
				if (b.body) parts.push(`<pre><code>${escapeHtml(b.body)}</code></pre>${b.caption ? `<p><em>${escapeHtml(b.caption)}</em></p>` : ''}`);
				break;
			case 'audio':
				parts.push(
					`<p><audio controls preload="none" src="${origin}/api/stories/${story.slug}/audio/${b.id}"></audio></p>` +
						(b.caption ? `<p><em>${escapeHtml(b.caption)}</em></p>` : '') +
						(b.transcript ? `<details><summary>Transcript</summary><p>${escapeHtml(b.transcript)}</p></details>` : '')
				);
				break;
			case 'video':
				parts.push(
					`<p><video controls preload="none" src="${origin}/api/stories/${story.slug}/video/${b.id}"${b.posterImageId ? ` poster="${img(b.posterImageId)}"` : ''}></video></p>` +
						(b.caption ? `<p><em>${escapeHtml(b.caption)}</em></p>` : '')
				);
				break;
		}
	}
	return parts.filter(Boolean).join('\n');
}

/** Plain-text summary: the summary field, else the first text block trimmed. */
export function storySummary(/** @type {import('#lib/types.js').Story} */ s) {
	if (s.summary) return s.summary;
	const firstText = (s.blocks || []).find((b) => b.type === 'text');
	if (firstText && 'body' in firstText && firstText.body) {
		const body = firstText.body.trim().replace(/\s+/g, ' ');
		return body.length > 280 ? body.slice(0, 277) + '…' : body;
	}
	return '';
}

function largest(/** @type {number[] | undefined} */ widths) {
	return widths?.length ? Math.max(...widths) : 960;
}

/**
 * For a member's personal feed: point every media URL of a private story at
 * its /private address and add their token, so a feed reader with no cookies
 * can load it. Works on HTML or on a single URL.
 * @param {string} text @param {string} origin @param {string} token
 */
export function withReaderToken(text, origin, token) {
	const q = `?token=${encodeURIComponent(token)}`;
	const base = origin.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
	return text.replace(new RegExp(`${base}/(img|media|api/stories)/[^"'<>\\s]+`, 'g'), (u, kind) =>
		(kind === 'api/stories' ? u : u.replace(`${origin}/${kind}/`, `${origin}/private/${kind}/`)) + q
	);
}
