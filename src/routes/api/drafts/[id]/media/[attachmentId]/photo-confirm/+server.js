/**
 * Confirm a full-resolution photo's R2 upload and register it on the draft.
 *
 *   POST /api/drafts/:id/media/:attachmentId/photo-confirm
 *     body: { base, ext, widths: number[], w, h, caption? }
 *     returns: { draft }
 *
 * HEADs the original object to be sure the upload landed, then writes a photo
 * attachment carrying the r2 metadata (no Cloudflare Images id). The gallery
 * renders these from /media/<key> and promote turns them into R2 gallery items.
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
	const base = String(payload?.base || '');
	const ext = String(payload?.ext || 'jpg');
	const widths = Array.isArray(payload?.widths) ? payload.widths.map(Number).filter(Number.isFinite) : [];
	const w = Number(payload?.w);
	const h = Number(payload?.h);
	const caption = typeof payload?.caption === 'string' ? payload.caption : undefined;

	if (!base.startsWith('gallery/') || !widths.length || !Number.isFinite(w) || !Number.isFinite(h)) {
		return new Response(
			JSON.stringify({ error: 'missing_fields', need: ['base', 'widths', 'w', 'h'] }),
			{ status: 400, headers: { 'content-type': 'application/json', ...cors } }
		);
	}

	// Make sure the original actually landed before we register it.
	const head = base === `gallery/${params.attachmentId}` ? await headObject(`${base}/orig.${ext}`) : null;
	if (!head) {
		return new Response(JSON.stringify({ error: 'original_not_found_in_r2', base }), {
			status: 409,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	const attachment = {
		id: params.attachmentId,
		kind: 'photo',
		caption,
		r2: { base, widths, ext, w, h },
		createdAt: new Date().toISOString()
	};
	const next = await addAttachment(params.id, /** @type {any} */ (attachment));
	return json({ draft: next }, { headers: cors });
}
