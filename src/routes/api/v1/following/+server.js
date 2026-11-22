/**
 * The author's subscription list as OPML — the phone app's backup.
 *
 *   GET  /api/v1/following   the stored OPML (bearer token or admin)
 *   PUT  /api/v1/following   replace it (bearer token; body is the OPML)
 *
 * The public, read-only copy lives at /following.opml.
 */

import { json } from '@sveltejs/kit';
import { getFollowing, putFollowing } from '#lib/server/following.js';
import { isCaptureRequest, corsHeaders, unauthorized } from '#lib/server/auth-token.js';

/** @type {import('./$types').RequestHandler} */
export async function OPTIONS({ request }) {
	return new Response(null, { status: 204, headers: corsHeaders(request) });
}

/** @type {import('./$types').RequestHandler} */
export async function GET({ request, locals }) {
	if (!locals.isAdmin && !isCaptureRequest(request)) return unauthorized(request);
	const f = await getFollowing();
	if (!f) return new Response(null, { status: 404, headers: corsHeaders(request) });
	return new Response(f.opml, {
		headers: { 'content-type': 'text/x-opml; charset=utf-8', 'last-modified': new Date(f.updatedAt).toUTCString(), ...corsHeaders(request) }
	});
}

/** @type {import('./$types').RequestHandler} */
export async function PUT({ request }) {
	if (!isCaptureRequest(request)) return unauthorized(request);
	const opml = await request.text();
	try {
		const r = await putFollowing(opml);
		return json({ ok: true, updatedAt: r.updatedAt }, { headers: corsHeaders(request) });
	} catch (e) {
		return json({ error: e instanceof Error ? e.message : 'bad request' }, { status: 400, headers: corsHeaders(request) });
	}
}
