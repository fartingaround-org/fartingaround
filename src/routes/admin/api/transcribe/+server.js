/**
 *   GET  /admin/api/transcribe            → { enabled, model }
 *   POST /admin/api/transcribe { key }    → { transcript, words, model }
 *
 * Only keys under `audio/` are accepted — this is for admin-uploaded audio
 * captions, not arbitrary objects.
 */

import { json, error } from '@sveltejs/kit';
import { config } from '#lib/server/config.js';
import { transcribeObject, transcriptionEnabled } from '#lib/server/transcribe.js';

/** @type {import('./$types').RequestHandler} */
export const GET = async ({ locals }) => {
	if (!locals.isAdmin) throw error(401, 'Unauthorized');
	return json({ enabled: transcriptionEnabled(), model: config.elevenLabsModel });
};

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, locals }) => {
	if (!locals.isAdmin) throw error(401, 'Unauthorized');
	if (!transcriptionEnabled()) throw error(503, 'Transcription is off: set ELEVENLABS_API_KEY on the server.');

	const body = await request.json().catch(() => ({}));
	const key = typeof body?.key === 'string' ? body.key : '';
	if (!/^audio\/[A-Za-z0-9_-]+\.[a-z0-9]+$/.test(key)) throw error(400, 'Bad key.');

	try {
		return json(await transcribeObject(key));
	} catch (err) {
		console.error('[transcribe] failed:', err);
		throw error(502, err instanceof Error ? err.message : 'Transcription failed');
	}
};
