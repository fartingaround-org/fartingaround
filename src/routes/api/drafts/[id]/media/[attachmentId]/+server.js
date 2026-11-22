/**
 * Per-attachment media endpoint.
 *
 *   GET  /api/drafts/:id/media/:attachmentId
 *     Stream the R2 object back with the stored content-type. Used for
 *     playback in the admin UI and (since the synced-transcribe fix) by the
 *     capture app to re-fetch audio for transcription after sync.
 *
 *   PUT  /api/drafts/:id/media/:attachmentId
 *     Streaming upload for audio/video. Takes the raw file body and pipes
 *     it straight to R2 without buffering — the multipart POST sibling at
 *     /api/drafts/:id/media buffers the whole body in memory via
 *     request.formData() and tips over on >100 MB phone videos. Metadata
 *     rides in headers because we can't read them from a multipart body
 *     when there is no multipart body:
 *       X-Kind         "audio" | "video"
 *       X-Caption      optional, URL-encoded
 *       X-Duration-Ms  optional, decimal integer
 *     Content-Type carries the MIME. Auth: admin cookie OR capture token.
 *
 * Photos still go through POST /api/drafts/:id/media because they don't
 * land in R2 at all — they're proxied through Cloudflare Images, which
 * needs the file as a Blob anyway.
 */

import { json } from '@sveltejs/kit';
import { getDraft, addAttachment } from '#lib/server/drafts.js';
import { putObject, draftMediaKey, extFromMime } from '#lib/server/objects.js';
import { serveObject } from '#lib/server/serve.js';
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
	if (!draft) return new Response('not found', { status: 404, headers: cors });

	const att = draft.attachments.find((a) => a.id === params.attachmentId);
	if (!att || (att.kind !== 'audio' && att.kind !== 'video')) {
		return new Response('not found', { status: 404, headers: cors });
	}

	// Stream with Range support so the admin's <audio>/<video> can seek.
	const res = await serveObject(request, /** @type {any} */ (att).r2Key, {
		mime: /** @type {any} */ (att).mimeType || undefined,
		cacheControl: 'private, max-age=3600'
	});
	for (const [k, v] of Object.entries(cors)) res.headers.set(k, v);
	return res;
}

/** @type {import('./$types').RequestHandler} */
export async function PUT({ request, params, locals }) {
	const cors = corsHeaders(request);
	if (!locals.isAdmin && !isCaptureRequest(request)) return unauthorized(request);


	const existing = await getDraft(params.id);
	if (!existing) {
		return new Response(JSON.stringify({ error: 'draft_not_found' }), {
			status: 404,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	const kind = (request.headers.get('x-kind') || '').toLowerCase();
	if (kind !== 'audio' && kind !== 'video') {
		return new Response(JSON.stringify({ error: 'bad_kind', kind }), {
			status: 400,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	if (!request.body) {
		return new Response(JSON.stringify({ error: 'empty_body' }), {
			status: 400,
			headers: { 'content-type': 'application/json', ...cors }
		});
	}

	const mime =
		request.headers.get('content-type') ||
		(kind === 'audio' ? 'audio/webm' : 'video/mp4');
	// Captions and durations come through headers since there's no form to
	// parse. URL-decode caption so it can contain spaces/punctuation without
	// breaking the header parser.
	const captionRaw = request.headers.get('x-caption');
	let caption;
	try {
		caption = captionRaw ? decodeURIComponent(captionRaw) : undefined;
	} catch {
		caption = captionRaw || undefined;
	}
	const durationHeader = request.headers.get('x-duration-ms');
	const durationMs = durationHeader && /^\d+$/.test(durationHeader) ? Number(durationHeader) : undefined;

	const key = draftMediaKey(params.id, params.attachmentId, extFromMime(mime));

	try {
		// Streaming put — the body goes to disk as it arrives, never buffered.
		await putObject(key, request.body);

		const attachment = {
			id: params.attachmentId,
			kind,
			r2Key: key,
			mimeType: mime,
			durationMs,
			caption,
			createdAt: new Date().toISOString()
		};
		const next = await addAttachment(params.id, /** @type {any} */ (attachment));
		return json({ draft: next }, { headers: cors });
	} catch (err) {
		const message = err instanceof Error ? err.message : String(err);
		const stack = err instanceof Error ? (err.stack || '') : '';
		console.error('Streaming media PUT failed:', message, stack);
		return new Response(
			JSON.stringify({
				error: 'upload_failed',
				kind,
				attachmentId: params.attachmentId,
				message,
				stack: stack.split('\n').slice(0, 5).join('\n')
			}),
			{
				status: 500,
				headers: { 'content-type': 'application/json', ...cors }
			}
		);
	}
}
