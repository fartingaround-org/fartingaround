/**
 * Server-side transcription of an uploaded clip (ElevenLabs Scribe, word
 * timestamps). The phone can transcribe with its own key instead; this is
 * for clients that don't carry one.
 *
 *   GET  /api/v1/media/transcribe          → { enabled, model }
 *   POST /api/v1/media/transcribe { key }  → { transcript, words, model, languageCode? }
 *        503 when ELEVENLABS_API_KEY isn't set on the server.
 */
import { config } from '#lib/server/config.js';
import { transcribeObject, transcriptionEnabled } from '#lib/server/transcribe.js';
import { fail, isAuthor, preflight, readJson, reply } from '#lib/server/api-v1.js';

const KEY_RE = /^audio\/[A-Za-z0-9_-]{6,64}\.[a-z0-9]{1,5}$/;

/** @type {import('./$types').RequestHandler} */
export const OPTIONS = async ({ request }) => preflight(request);

/** @type {import('./$types').RequestHandler} */
export const GET = async ({ request, locals }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	return reply(request, { enabled: transcriptionEnabled(), model: config.elevenLabsModel });
};

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, locals }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	if (!transcriptionEnabled()) return fail(request, 503, 'transcription_disabled', { hint: 'Set ELEVENLABS_API_KEY on the server.' });
	const body = await readJson(request);
	const key = typeof body.key === 'string' ? body.key : '';
	if (!KEY_RE.test(key)) return fail(request, 400, 'bad_key', { key });
	try {
		return reply(request, await transcribeObject(key));
	} catch (err) {
		console.error('[v1/media/transcribe] failed:', err);
		return fail(request, 502, 'transcription_failed', { message: err instanceof Error ? err.message : String(err) });
	}
};
