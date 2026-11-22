/**
 * Drafts collection endpoint.
 *
 *   GET  /api/drafts             list drafts (admin or capture-token)
 *   POST /api/drafts             upsert a draft (capture-token only)
 *   OPTIONS /api/drafts          CORS preflight
 *
 * The phone app upserts the full draft document. We do not auto-generate
 * ids on the server — the phone generates a UUID at draft-creation time so
 * offline capture works.
 */

import { json } from '@sveltejs/kit';
import { listDrafts, putDraft } from '#lib/server/drafts.js';
import { isCaptureRequest, corsHeaders, unauthorized } from '#lib/server/auth-token.js';

/** @type {import('./$types').RequestHandler} */
export async function OPTIONS({ request }) {
	return new Response(null, { status: 204, headers: corsHeaders(request) });
}

/** @type {import('./$types').RequestHandler} */
export async function GET({ request, locals }) {
	const cors = corsHeaders(request);
	if (!locals.isAdmin && !isCaptureRequest(request)) return unauthorized(request);


	const drafts = await listDrafts();
	return json({ drafts }, { headers: cors });
}

/** @type {import('./$types').RequestHandler} */
export async function POST({ request }) {
	const cors = corsHeaders(request);
	if (!isCaptureRequest(request)) return unauthorized(request);


	/** @type {any} */
	let body;
	try {
		body = await request.json();
	} catch {
		return new Response(JSON.stringify({ error: 'invalid_json' }), {
			status: 400,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	if (!body?.id || !body?.capturedAt) {
		return new Response(JSON.stringify({ error: 'missing_fields', need: ['id', 'capturedAt'] }), {
			status: 400,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	const saved = await putDraft({
		id: String(body.id),
		title: String(body.title || ''),
		body: String(body.body || ''),
		tags: Array.isArray(body.tags) ? body.tags.map(String) : [],
		location: body.location ? String(body.location) : undefined,
		coords: body.coords && typeof body.coords === 'object' ? body.coords : undefined,
		// Auto-context captured alongside the GPS tap. promote/ copies these onto
		// the story, so they have to survive the upsert.
		weather: body.weather && typeof body.weather === 'object' ? body.weather : undefined,
		astro: body.astro && typeof body.astro === 'object' ? body.astro : undefined,
		capturedAt: String(body.capturedAt),
		createdAt: body.createdAt ? String(body.createdAt) : new Date().toISOString(),
		updatedAt: new Date().toISOString(),
		attachments: Array.isArray(body.attachments) ? body.attachments : [],
		status: body.status === 'archived' || body.status === 'promoted' ? body.status : 'open'
	});

	return json({ draft: saved }, { headers: cors });
}
