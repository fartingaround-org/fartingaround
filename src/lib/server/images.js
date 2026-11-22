/**
 * Image store + on-demand transforms. Replaces Cloudflare Images.
 *
 * Originals: objects/images/<id>/orig.<ext>, with a row in `images` holding
 * dimensions. Variants: generated with sharp on first request for a given
 * parameter string and cached at objects/images/<id>/cache/<params>.<fmt>, so
 * the URL grammar the front-end already uses
 * (`/img/<id>/w=800,h=600,q=85,fit=cover,gravity=0.5x0.3`) keeps working.
 *
 * Widths are snapped to a fixed ladder and capped, so the set of variants an
 * image can ever have is bounded no matter what a URL asks for.
 */

import { randomBytes } from 'node:crypto';
import { getDb, nowIso } from './db.js';
import { deletePrefix, extFromMime, getObjectStream, headObject, objectPath, putObject } from './objects.js';

const WIDTHS = [96, 160, 240, 320, 400, 480, 640, 720, 800, 960, 1200, 1280, 1440, 1600, 1800, 2000, 2400];
const MAX_WIDTH = 2400;
const MAX_HEIGHT = 2400;

/** @returns {Promise<typeof import('sharp')>} */
async function sharp() {
	const mod = await import('sharp');
	return /** @type {any} */ (mod.default || mod);
}

export function newImageId() {
	// 20 url-safe chars; unguessable, so variant URLs can be cached forever.
	return randomBytes(15).toString('base64url');
}

/**
 * Store an uploaded image. Reads dimensions with sharp (orientation applied);
 * if sharp can't decode it (an odd HEIC, say) the file is still kept and the
 * dimensions left null.
 * @param {File | Blob} file
 * @param {{ filename?: string }} [opts]
 * @returns {Promise<{ id: string, filename: string, ext: string, width: number | null, height: number | null }>}
 */
export async function uploadImage(file, opts = {}) {
	const filename = opts.filename || (file instanceof File ? file.name : 'upload');
	const mime = file.type || mimeFromName(filename);
	const ext = extFromMime(mime) === 'bin' ? (filename.split('.').pop() || 'bin').toLowerCase() : extFromMime(mime);
	const id = newImageId();
	const { size } = await putObject(`images/${id}/orig.${ext}`, file);

	/** @type {number | null} */ let width = null;
	/** @type {number | null} */ let height = null;
	try {
		const s = await sharp();
		const meta = await s(objectPath(`images/${id}/orig.${ext}`)).rotate().metadata();
		// After rotate() for EXIF orientations 5–8, width/height swap.
		const swapped = (meta.orientation || 1) >= 5;
		width = (swapped ? meta.height : meta.width) ?? null;
		height = (swapped ? meta.width : meta.height) ?? null;
	} catch {
		/* keep nulls */
	}

	const db = await getDb();
	await db.run(
		'INSERT INTO images (id, ext, mime, width, height, bytes, filename, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
		[id, ext, mime, width, height, size, filename, nowIso()]
	);
	warmImageLater({ id, ext });
	return { id, filename, ext, width, height };
}

/** @param {string} id */
export async function getImage(id) {
	const db = await getDb();
	return /** @type {{ id: string, ext: string, mime: string, width: number | null, height: number | null } | undefined} */ (
		await db.get('SELECT id, ext, mime, width, height FROM images WHERE id = ?', [id])
	);
}

/** Remove an image, its original and every cached variant. */
export async function deleteImage(/** @type {string} */ id) {
	if (!/^[A-Za-z0-9_-]{8,}$/.test(id)) return;
	const db = await getDb();
	await db.run('DELETE FROM images WHERE id = ?', [id]);
	await deletePrefix(`images/${id}`);
}

// ── Variants ────────────────────────────────────────────────────────────────

/**
 * @typedef {{ w?: number, h?: number, q: number, fit: 'cover'|'contain'|'scale-down', gx: number, gy: number, format: 'jpeg'|'webp'|'avif'|'png', sharpen: boolean }} Variant
 */

/**
 * Parse `w=800,h=600,q=85,fit=cover,gravity=0.5x0.3,format=auto` (the
 * Cloudflare flexible-variant grammar our image.js emits) into a bounded,
 * normalized variant. `public` is the full image at the largest width.
 * @param {string} params
 * @param {string} accept  the request's Accept header, for format=auto
 */
export function parseVariant(params, accept = '') {
	/** @type {Record<string, string>} */
	const kv = {};
	if (params !== 'public') {
		for (const part of params.split(',')) {
			const [k, v] = part.split('=');
			if (k && v !== undefined) kv[k] = v;
		}
	}
	let w = kv.w ? snapWidth(Number(kv.w)) : undefined;
	let h = kv.h ? Math.min(MAX_HEIGHT, Math.max(1, Math.round(Number(kv.h) / 8) * 8)) : undefined;
	if (kv.w && w && Number(kv.w) > 0 && h) {
		// keep the requested aspect when we snapped w
		h = Math.min(MAX_HEIGHT, Math.max(8, Math.round(((Number(kv.h) / Number(kv.w)) * w) / 8) * 8));
	}
	if (!w && !h) w = 2000;
	const q = Math.min(95, Math.max(40, Number(kv.q) || 82));
	/** @type {Variant['fit']} */
	const fit = kv.fit === 'cover' || kv.fit === 'contain' ? kv.fit : kv.fit === 'crop' ? 'cover' : 'scale-down';
	let gx = 0.5, gy = 0.5;
	const g = kv.gravity?.match(/^([0-9.]+)x([0-9.]+)$/);
	if (g) { gx = clamp01(Number(g[1])); gy = clamp01(Number(g[2])); }
	/** @type {Variant['format']} */
	let format = 'jpeg';
	if (kv.format === 'webp' || kv.format === 'avif' || kv.format === 'png') format = kv.format;
	// format=auto is used for transparent overlays, so the answer must keep
	// alpha: avif when the client says it can, else webp (which every current
	// browser and Android decoder handles).
	else if (kv.format === 'auto') format = accept.includes('image/avif') ? 'avif' : 'webp';
	return /** @type {Variant} */ ({ w, h, q, fit, gx, gy, format, sharpen: !!kv.sharpen });
}

function snapWidth(/** @type {number} */ n) {
	if (!Number.isFinite(n) || n <= 0) return undefined;
	return WIDTHS.find((x) => x >= n) ?? MAX_WIDTH;
}
function clamp01(/** @type {number} */ n) {
	return Number.isFinite(n) ? Math.round(Math.max(0, Math.min(1, n)) * 100) / 100 : 0.5;
}

/** Deterministic cache key for a variant. */
export function variantKey(/** @type {string} */ id, /** @type {Variant} */ v) {
	const parts = [];
	if (v.w) parts.push(`w${v.w}`);
	if (v.h) parts.push(`h${v.h}`);
	parts.push(`q${v.q}`, v.fit);
	if (v.fit === 'cover' && v.h) parts.push(`g${v.gx}x${v.gy}`);
	if (v.sharpen) parts.push('s');
	return `images/${id}/cache/${parts.join('_')}.${v.format === 'jpeg' ? 'jpg' : v.format}`;
}

/** @type {Map<string, Promise<void>>} in-flight generations, so a burst of requests renders once */
const inflight = new Map();

/**
 * Renders run at most RENDER_CONCURRENCY at a time. A photo dump's first view
 * asks for hundreds of images at once; letting them all hit libvips together
 * thrashes a small box and stalls every other request. Queueing them keeps
 * the site responsive and finishes sooner overall.
 */
const RENDER_CONCURRENCY = 2;
let active = 0;
/** @type {Array<() => void>} */
const waiting = [];
async function withRenderSlot(/** @type {() => Promise<void>} */ fn) {
	if (active >= RENDER_CONCURRENCY) {
		await /** @type {Promise<void>} */ (new Promise((resolve) => {
			waiting.push(() => resolve());
		}));
	}
	active++;
	try {
		await fn();
	} finally {
		active--;
		waiting.shift()?.();
	}
}

/**
 * Ensure a variant exists on disk and return its object key + mime.
 * @param {{ id: string, ext: string }} image
 * @param {Variant} v
 */
export async function ensureVariant(image, v) {
	const key = variantKey(image.id, v);
	if (!(await headObject(key))) {
		let job = inflight.get(key);
		if (!job) {
			job = withRenderSlot(() => render(image, v, key)).finally(() => inflight.delete(key));
			inflight.set(key, job);
		}
		await job;
	}
	return { key, mime: `image/${v.format}` };
}

/**
 * @param {{ id: string, ext: string }} image
 * @param {Variant} v
 * @param {string} key
 */
async function render(image, v, key) {
	const s = await sharp();
	let img = s(objectPath(`images/${image.id}/orig.${image.ext}`), { failOn: 'none' }).rotate();
	const meta = await img.metadata();
	const swapped = (meta.orientation || 1) >= 5;
	const W = (swapped ? meta.height : meta.width) || 1;
	const H = (swapped ? meta.width : meta.height) || 1;

	if (v.fit === 'cover' && v.w && v.h) {
		// Cover-crop around a focal point (Cloudflare's gravity=XxY). sharp's
		// `position` only knows compass points, so do the arithmetic ourselves:
		// scale to cover, then extract a w×h window centred on the focus.
		const scale = Math.max(v.w / W, v.h / H);
		const rw = Math.max(v.w, Math.round(W * scale));
		const rh = Math.max(v.h, Math.round(H * scale));
		const left = Math.min(rw - v.w, Math.max(0, Math.round(v.gx * rw - v.w / 2)));
		const top = Math.min(rh - v.h, Math.max(0, Math.round(v.gy * rh - v.h / 2)));
		img = img.resize(rw, rh).extract({ left, top, width: v.w, height: v.h });
	} else {
		img = img.resize({
			width: v.w,
			height: v.h,
			fit: v.fit === 'contain' ? 'contain' : 'inside',
			withoutEnlargement: true,
			background: { r: 0, g: 0, b: 0, alpha: 0 }
		});
	}
	if (v.sharpen) img = img.sharpen();
	switch (v.format) {
		case 'avif': img = img.avif({ quality: v.q }); break;
		case 'webp': img = img.webp({ quality: v.q }); break;
		case 'png': img = img.png(); break;
		default: img = img.jpeg({ quality: v.q, mozjpeg: true });
	}
	await putObject(key, await img.toBuffer());
}

/**
 * The sizes the site actually asks for in bulk: photo-dump tiles and the
 * editor/gallery thumbnails (see thumbWidths in BlockDump/BlockGallery). Having
 * these ready means a dump page never renders on first view. Lightbox sizes
 * (2000px) are left to render on demand — far fewer of them get looked at.
 */
const WARM_WIDTHS = [320, 480, 720, 960, 1280];

/**
 * Pre-render the common sizes of one image. Runs through the same queue as
 * on-demand renders, so it can't starve a real visitor.
 * @param {{ id: string, ext: string }} image
 */
export async function warmImage(image) {
	for (const w of WARM_WIDTHS) {
		await ensureVariant(image, parseVariant(`w=${w},q=82`, ''));
	}
}

/** Kick off warming in the background (after an upload); errors are logged, not thrown. */
export function warmImageLater(/** @type {{ id: string, ext: string }} */ image) {
	warmImage(image).catch((e) => console.warn('[img] warm failed', image.id, e?.message || e));
}

/**
 * Walk every image and warm it. Used by the admin warm endpoint after a
 * migration. Reports progress through the callback.
 * @param {(done: number, total: number, id: string) => void} [onProgress]
 */
export async function warmAllImages(onProgress) {
	const db = await getDb();
	const rows = /** @type {{ id: string, ext: string }[]} */ (await db.all('SELECT id, ext FROM images ORDER BY created_at DESC'));
	let done = 0;
	for (const img of rows) {
		try {
			await warmImage(img);
		} catch (e) {
			console.warn('[img] warm failed', img.id, e instanceof Error ? e.message : e);
		}
		done++;
		onProgress?.(done, rows.length, img.id);
	}
	return rows.length;
}

/** Stream a variant (after ensureVariant). */
export async function openVariant(/** @type {string} */ key) {
	return getObjectStream(key);
}

function mimeFromName(/** @type {string} */ name) {
	const ext = name.split('.').pop()?.toLowerCase();
	return ext === 'png' ? 'image/png' : ext === 'webp' ? 'image/webp' : ext === 'heic' ? 'image/heic' : 'image/jpeg';
}
