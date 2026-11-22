/**
 * Confirm that a direct-to-R2 upload completed and register the resulting
 * attachment in KV.
 *
 *   POST /api/drafts/:id/media/:attachmentId/confirm
 *     body: {
 *       kind: 'audio' | 'video',
 *       key: string,          // the R2 key the /sign endpoint returned
 *       mimeType: string,
 *       durationMs?: number,
 *       caption?: string
 *     }
 *     returns: { draft }
 *
 * The Worker quickly HEADs the R2 object to make sure the upload actually
 * landed (so a bad/expired presigned URL doesn't get silently registered as
 * a "synced" attachment), then patches the draft via the existing KV
 * helpers.
 *
 * Auth: admin cookie OR capture token.
 */

import { json } from '@sveltejs/kit';
import { getDraft, addAttachment } from '#lib/server/drafts.js';
import { headObject } from '#lib/server/objects.js';
import { isCaptureRequest, corsHeaders, unauthorized } from '#lib/server/auth-token.js';

/** @type {import('./$types').RequestHandler} */
export async function OPTIONS({ request }) {
	return new Response(null, { status: 204, headers: corsHeaders(request) });
}

/** @type {import('./$types').RequestHandler} */
export async function POST({ request, params, locals }) {
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
	const kind = String(payload?.kind || '');
	const key = String(payload?.key || '');
	const mimeType = String(payload?.mimeType || '');
	const durationMs = typeof payload?.durationMs === 'number' ? payload.durationMs : undefined;
	const caption = typeof payload?.caption === 'string' ? payload.caption : undefined;

	if ((kind !== 'audio' && kind !== 'video') || !key || !mimeType) {
		return new Response(
			JSON.stringify({ error: 'missing_fields', need: ['kind', 'key', 'mimeType'] }),
			{ status: 400, headers: { 'content-type': 'application/json', ...cors } }
		);
	}

	// Sanity-check the upload landed, and that the key really belongs to this
	// draft + attachment (a confirm can't adopt someone else's object).
	const head = key.startsWith(`drafts/${params.id}/${params.attachmentId}.`) ? await headObject(key) : null;
	if (!head) {
		return new Response(
			JSON.stringify({ error: 'object_not_found', key }),
			{ status: 409, headers: { 'content-type': 'application/json', ...cors } }
		);
	}

	const attachment = {
		id: params.attachmentId,
		kind,
		r2Key: key,
		mimeType,
		durationMs,
		caption,
		createdAt: new Date().toISOString()
	};
	const next = await addAttachment(params.id, /** @type {any} */ (attachment));
	return json({ draft: next }, { headers: cors });
}
