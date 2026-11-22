/**
 * Step 1 of the large-upload flow for audio/video:
 *
 *   POST /api/drafts/:id/media/:attachmentId/sign  { kind, mimeType }
 *   → { url, key, expiresAt }
 *
 * Returns a short-lived signed PUT URL on this server (see
 * src/lib/server/upload-urls.js). The client PUTs the bytes there with no auth
 * header, then calls /confirm. This used to presign an R2 URL; the contract is
 * unchanged so the phone app didn't have to be.
 */

import { json } from '@sveltejs/kit';
import { getDraft } from '#lib/server/drafts.js';
import { draftMediaKey, extFromMime } from '#lib/server/objects.js';
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
	const mimeType = String(payload?.mimeType || '');
	const kind = String(payload?.kind || '');
	if (kind !== 'audio' && kind !== 'video') {
		return new Response(JSON.stringify({ error: 'bad_kind', kind }), {
			status: 400,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	const key = draftMediaKey(params.id, params.attachmentId, extFromMime(mimeType));
	const signed = signedUploadUrl(url.origin, key);
	const expiresAt = new Date(Number(new URL(signed).searchParams.get('exp')) * 1000).toISOString();
	return json({ url: signed, key, expiresAt }, { headers: cors });
}
