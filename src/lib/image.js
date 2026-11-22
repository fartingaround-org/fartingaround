/**
 * Image URL builder.
 *
 *   /img/<image_id>/w=800,q=85,fit=cover,gravity=0.5x0.3
 *
 * The parameter grammar is Cloudflare's flexible-variant one, kept because
 * every component already speaks it; the server (src/routes/img) renders and
 * caches each variant on demand with sharp. Relative URLs so the same build
 * serves any domain; the RSS feed absolutizes them itself.
 */

const BASE = '/img';

/**
 * @typedef {Object} Focus
 * @property {number} x   0..1
 * @property {number} y   0..1
 */

/**
 * @param {string | undefined} imageId
 * @param {{
 *   w?: number,
 *   h?: number,
 *   q?: number,
 *   fit?: 'cover'|'contain'|'scale-down'|'crop'|'pad',
 *   aspect?: string,
 *   format?: 'auto'|'avif'|'webp'|'json',
 *   sharpen?: number,
 *   gravity?: Focus | 'auto' | 'face' | 'left' | 'right' | 'top' | 'bottom',
 *   variant?: string
 * }} [opts]
 */
export function imageUrl(imageId, opts = {}) {
	if (!imageId) return '';
	const { variant, ...flex } = opts;

	if (variant) {
		return `${BASE}/${imageId}/${variant}`;
	}

	// Derive a height from an aspect string (e.g. "4/5") — mirrors imageSrcset,
	// which crops to the same aspect for every srcset candidate.
	let h = flex.h;
	let fit = flex.fit;
	if (!h && flex.aspect && flex.w) {
		const [aw, ah] = flex.aspect.split('/').map(Number);
		if (aw && ah) {
			h = Math.round((flex.w * ah) / aw);
			fit = fit ?? 'cover';
		}
	}

	const parts = [];
	if (flex.w) parts.push(`w=${flex.w}`);
	if (h) parts.push(`h=${h}`);
	if (flex.q) parts.push(`q=${flex.q}`);
	if (fit) parts.push(`fit=${fit}`);
	if (flex.format) parts.push(`format=${flex.format}`);
	if (flex.sharpen) parts.push(`sharpen=${flex.sharpen}`);
	if (flex.gravity) {
		if (typeof flex.gravity === 'string') {
			parts.push(`gravity=${flex.gravity}`);
		} else {
			const x = clamp01(flex.gravity.x);
			const y = clamp01(flex.gravity.y);
			parts.push(`gravity=${x}x${y}`);
		}
	}

	const variantPath = parts.length ? parts.join(',') : 'public';
	return `${BASE}/${imageId}/${variantPath}`;
}

/**
 * Build a srcset string from a list of widths.
 * @param {string | undefined} imageId
 * @param {number[]} widths
 * @param {{ q?: number, fit?: 'cover'|'contain'|'scale-down', aspect?: string, gravity?: Focus | string, format?: 'auto'|'avif'|'webp' }} [opts]
 */
export function imageSrcset(imageId, widths, opts = {}) {
	if (!imageId) return '';
	const q = opts.q ?? 82;
	const fit = opts.fit ?? 'cover';
	return widths
		.map((w) => {
			/** @type {any} */
			const params = { w, q, fit };
			// Transparent overlays need an alpha-preserving format (avif/webp via auto).
			if (opts.format) params.format = opts.format;
			if (opts.aspect) {
				const [aw, ah] = opts.aspect.split('/').map(Number);
				if (aw && ah) params.h = Math.round((w * ah) / aw);
			}
			if (opts.gravity) params.gravity = opts.gravity;
			return `${imageUrl(imageId, params)} ${w}w`;
		})
		.join(', ');
}

/** @param {number} n */
function clamp01(n) {
	if (!Number.isFinite(n)) return 0.5;
	return Math.round(Math.max(0, Math.min(1, n)) * 1000) / 1000;
}

/* -------------------------------------------------------------------------
   R2-backed full-resolution images (astrophotography gallery photos).

   Unlike Cloudflare Images, R2 doesn't resize on the fly — derivatives are
   pre-generated at upload time and stored alongside the original. These
   helpers build the public /media/<key> URLs the gallery renders. See the
   R2Image typedef in types.js for the key layout.
   ------------------------------------------------------------------------- */

/** @param {string} key */
export function mediaUrl(key) {
	return key ? `/media/${key}` : '';
}

/** Full-resolution original. @param {import('#lib/types.js').R2Image} r2 */
export function r2OriginalUrl(r2) {
	if (!r2?.base) return '';
	return `/media/${r2.base}/orig.${r2.ext || 'jpg'}`;
}

/** A specific derivative width. @param {import('#lib/types.js').R2Image} r2 @param {number} w */
export function r2DerivativeUrl(r2, w) {
	if (!r2?.base) return '';
	return `/media/${r2.base}/w${w}.jpg`;
}

/** Largest available derivative — used as the lightbox display image. */
export function r2LargestUrl(/** @type {import('#lib/types.js').R2Image} */ r2) {
	if (!r2?.base || !r2.widths?.length) return '';
	return r2DerivativeUrl(r2, Math.max(...r2.widths));
}

/** srcset across all derivative widths. @param {import('#lib/types.js').R2Image} r2 */
export function r2Srcset(r2) {
	if (!r2?.base || !r2.widths?.length) return '';
	return r2.widths.map((w) => `${r2DerivativeUrl(r2, w)} ${w}w`).join(', ');
}
