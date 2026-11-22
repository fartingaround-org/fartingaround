/**
 * Turn a stored object into an HTTP response: Range requests (video seeking,
 * audio scrubbing), ETag/If-None-Match, HEAD, and long cache lifetimes for
 * content whose key never changes meaning.
 */

import { Readable } from 'node:stream';
import { getObjectStream, headObject, mimeFromKey } from './objects.js';

/**
 * @param {Request} request
 * @param {string} key
 * @param {{ mime?: string, cacheControl?: string, filename?: string }} [opts]
 * @returns {Promise<Response>}
 */
export async function serveObject(request, key, opts = {}) {
	const head = await headObject(key);
	if (!head) return new Response('not found', { status: 404 });

	const etag = `"${head.size.toString(16)}-${Math.floor(head.mtime.getTime() / 1000).toString(16)}"`;
	const headers = new Headers({
		'content-type': opts.mime || mimeFromKey(key),
		'cache-control': opts.cacheControl || 'public, max-age=31536000, immutable',
		'accept-ranges': 'bytes',
		etag
	});
	if (opts.filename) headers.set('content-disposition', `inline; filename="${opts.filename.replace(/"/g, '')}"`);

	if (request.headers.get('if-none-match') === etag) {
		return new Response(null, { status: 304, headers });
	}

	// Range: bytes=start-end | bytes=start- | bytes=-suffix
	let start = 0;
	let end = head.size - 1;
	let status = 200;
	const range = request.headers.get('range');
	if (range) {
		const m = range.match(/^bytes=(\d*)-(\d*)$/);
		if (!m || (m[1] === '' && m[2] === '')) {
			return new Response(null, { status: 416, headers: { 'content-range': `bytes */${head.size}` } });
		}
		if (m[1] === '') {
			start = Math.max(0, head.size - Number(m[2]));
		} else {
			start = Number(m[1]);
			if (m[2] !== '') end = Math.min(end, Number(m[2]));
		}
		if (start > end || start >= head.size) {
			return new Response(null, { status: 416, headers: { 'content-range': `bytes */${head.size}` } });
		}
		status = 206;
		headers.set('content-range', `bytes ${start}-${end}/${head.size}`);
	}
	headers.set('content-length', String(end - start + 1));

	if (request.method === 'HEAD') return new Response(null, { status, headers });

	const obj = await getObjectStream(key, { start, end });
	if (!obj) return new Response('not found', { status: 404 });
	return new Response(/** @type {any} */ (Readable.toWeb(obj.stream)), { status, headers });
}
