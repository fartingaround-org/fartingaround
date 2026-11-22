/**
 * PUT target for signed upload URLs (see src/lib/server/upload-urls.js).
 * Streams the request body straight to the object store — no buffering, so a
 * multi-hundred-MB video is fine as long as BODY_SIZE_LIMIT allows it.
 */

import { putObject } from '#lib/server/objects.js';
import { verifyUploadUrl } from '#lib/server/upload-urls.js';

/** @type {import('./$types').RequestHandler} */
export async function PUT({ params, request, url }) {
	const key = params.key || '';
	if (!verifyUploadUrl(key, url.searchParams)) {
		return new Response(JSON.stringify({ error: 'bad_or_expired_signature' }), {
			status: 403,
			headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*' }
		});
	}
	if (!request.body) return new Response('empty body', { status: 400 });
	try {
		const { size } = await putObject(key, request.body);
		// The admin's browser PUTs here cross-origin when UPLOAD_ORIGIN is a
		// different host, so the success response needs CORS headers too.
		return new Response(JSON.stringify({ ok: true, key, size }), {
			headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*' }
		});
	} catch (e) {
		console.error('[upload] failed', key, e);
		return new Response(JSON.stringify({ error: 'write_failed' }), { status: 500, headers: { 'content-type': 'application/json' } });
	}
}

// Browsers preflight cross-origin PUTs; the Capacitor-era web app did this.
export async function OPTIONS() {
	return new Response(null, {
		status: 204,
		headers: {
			'access-control-allow-origin': '*',
			'access-control-allow-methods': 'PUT, OPTIONS',
			'access-control-allow-headers': 'content-type',
			'access-control-max-age': '86400'
		}
	});
}
