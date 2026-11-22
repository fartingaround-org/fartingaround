/**
 * Presign R2 PUT URLs for a full-resolution photo attachment (astro gallery).
 *
 *   POST /api/drafts/:id/media/:attachmentId/photo-sign
 *     body: { ext: string, widths: number[] }
 *     returns: { base, ext, widths, urls: { orig, "<width>": url, ... } }
 *
 * The capture app downscales the photo locally, then uploads the original +
 * derivatives straight to R2 (ASSETS_BUCKET) bypassing the Cloudflare Images
 * 10 MB cap. Afterwards it calls .../photo-confirm to register the r2 metadata.
 *
 * Auth: admin cookie OR capture token — same gate as the other media endpoints.
 */

import { json } from '@sveltejs/kit';
import { getDraft } from '#lib/server/drafts.js';
import { signedUploadUrl } from '#lib/server/upload-urls.js';
import { isCaptureRequest, corsHeaders, unauthorized } from '#lib/server/auth-token.js';

/** @type {import('./$types').RequestHandler} */
export async function OPTIONS({ request }) {
	return new Response(null, { status: 204, headers: corsHeaders(request) });
}

/** @type {import('./$types').RequestHandler} */
export async function POST({ request, params, locals, url }) {
	const cors = corsHeaders(request);
	if (!locals.isAdmin && !isCaptureRequest(request)) return unauthorized(request);

	const draft = await getDraft(params.id);
	if (!draft) {
		return new Response(JSON.stringify({ error: 'draft_not_found' }), {
			status: 404,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	let payload;
	try {
		payload = await request.json();
	} catch {
		payload = {};
	}
	const ext = String(payload?.ext || 'jpg').replace(/[^a-z0-9]/gi, '').toLowerCase() || 'jpg';
	const widths = Array.isArray(payload?.widths)
		? payload.widths.map(Number).filter((/** @type {number} */ w) => Number.isFinite(w) && w > 0 && w <= 8192)
		: [];
	if (!widths.length) {
		return new Response(JSON.stringify({ error: 'no_widths' }), {
			status: 400,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	// Keyed by the attachment's uuid so re-sync overwrites the same objects.
	const base = `gallery/${params.attachmentId}`;
	/** @type {Record<string, string>} */
	const urls = {};
	urls.orig = signedUploadUrl(url.origin, `${base}/orig.${ext}`);
	for (const w of widths) {
		urls[String(w)] = signedUploadUrl(url.origin, `${base}/w${w}.jpg`);
	}

	return json({ base, ext, widths, urls }, { headers: cors });
}
