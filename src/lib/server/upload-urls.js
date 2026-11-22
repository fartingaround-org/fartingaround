/**
 * Signed upload URLs — the local replacement for presigned R2 PUTs.
 *
 * The phone app's upload flow is: ask `/sign` for a URL, PUT the bytes to it
 * with no auth header, then `/confirm`. We keep that contract exactly, but the
 * URL now points back at this server:
 *
 *   PUT /api/upload/<key>?exp=<unix seconds>&sig=<hmac>
 *
 * The signature covers the key and the expiry, so a URL is good for exactly
 * one object for a short window, and nothing else. UPLOAD_SECRET (or a value
 * derived from ADMIN_PASSWORD) is the HMAC key. With UPLOAD_ORIGIN set, the
 * URL's host is that instead of the site's — see config.js.
 */

import { createHmac, timingSafeEqual } from 'node:crypto';
import { config } from './config.js';

const TTL_SECONDS = 15 * 60;

/** @param {string} key @param {number} exp */
function sign(key, exp) {
	return createHmac('sha256', config.uploadSecret).update(`${key}\n${exp}`).digest('base64url');
}

/**
 * @param {string} origin   the request's origin, used unless UPLOAD_ORIGIN is set
 * @param {string} key      object key the client may write
 */
export function signedUploadUrl(origin, key) {
	const exp = Math.floor(Date.now() / 1000) + TTL_SECONDS;
	// UPLOAD_ORIGIN lets the big PUTs go to a hostname that isn't behind a
	// proxy with a request-body cap (Cloudflare: 100 MB on the free plan).
	const base = config.uploadOrigin || origin;
	return `${base}/api/upload/${key}?exp=${exp}&sig=${sign(key, exp)}`;
}

/**
 * Check a PUT's query string against its key.
 * @param {string} key
 * @param {URLSearchParams} query
 */
export function verifyUploadUrl(key, query) {
	const exp = Number(query.get('exp'));
	const sig = query.get('sig') || '';
	if (!Number.isFinite(exp) || exp < Date.now() / 1000) return false;
	const expected = sign(key, exp);
	const a = Buffer.from(sig);
	const b = Buffer.from(expected);
	return a.length === b.length && timingSafeEqual(a, b);
}
