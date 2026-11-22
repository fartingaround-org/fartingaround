/**
 * Single-draft endpoint.
 *
 *   GET    /api/drafts/:id
 *   PATCH  /api/drafts/:id       merge fields, useful for adding a transcript
 *                                or marking archived
 *   DELETE /api/drafts/:id
 *   OPTIONS                       CORS preflight
 */

import { json } from '@sveltejs/kit';
import { getDraft, putDraft, deleteDraft } from '#lib/server/drafts.js';
import { deleteObject } from '#lib/server/objects.js';
import { isCaptureRequest, corsHeaders, unauthorized } from '#lib/server/auth-token.js';

/** @type {import('./$types').RequestHandler} */
export async function OPTIONS({ request }) {
	return new Response(null, { status: 204, headers: corsHeaders(request) });
}

/** @type {import('./$types').RequestHandler} */
export async function GET({ request, params, locals }) {
	const cors = corsHeaders(request);
	if (!locals.isAdmin && !isCaptureRequest(request)) return unauthorized(request);


	const draft = await getDraft(params.id);
	if (!draft) return new Response(JSON.stringify({ error: 'not_found' }), {
		status: 404,
		headers: { 'content-type': 'application/json', ...cors }
	});
	return json({ draft }, { headers: cors });
}

/** @type {import('./$types').RequestHandler} */
export async function PATCH({ request, params, locals }) {
	const cors = corsHeaders(request);
	if (!locals.isAdmin && !isCaptureRequest(request)) return unauthorized(request);


	const draft = await getDraft(params.id);
	if (!draft) return new Response(JSON.stringify({ error: 'not_found' }), {
		status: 404,
		headers: { 'content-type': 'application/json', ...cors }
	});

	/** @type {any} */
	let patch;
	try {
		patch = await request.json();
	} catch {
		return new Response(JSON.stringify({ error: 'invalid_json' }), {
			status: 400,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	const next = { ...draft, ...patch, id: draft.id, updatedAt: new Date().toISOString() };
	const saved = await putDraft(next);
	return json({ draft: saved }, { headers: cors });
}

/** @type {import('./$types').RequestHandler} */
export async function DELETE({ request, params, locals }) {
	const cors = corsHeaders(request);
	if (!locals.isAdmin && !isCaptureRequest(request)) return unauthorized(request);


	const draft = await getDraft(params.id);
	if (draft) {
		// best-effort cleanup of R2 audio/video; we leave Images alone (they're
		// inexpensive and may be reused by a promoted story)
		await Promise.all(
			draft.attachments
				.filter((a) => a.kind === 'audio' || a.kind === 'video')
				.map((a) => deleteObject(/** @type {any} */ (a).r2Key).catch(() => null))
		);
	}
	await deleteDraft(params.id);
	return new Response(null, { status: 204, headers: cors });
}
