/**
 * ElevenLabs Scribe speech-to-text, server side, for audio captions uploaded
 * through the admin. (The capture app calls ElevenLabs directly with its own
 * on-device key; this is the equivalent for clips that arrive via /admin.)
 *
 * Docs: https://elevenlabs.io/docs/api-reference/speech-to-text/convert
 */

import { config } from './config.js';
import { getObjectStream, mimeFromKey } from './objects.js';

export const transcriptionEnabled = () => !!config.elevenLabsApiKey;

/**
 * @param {string} key   object-store key of the audio clip
 * @returns {Promise<{ transcript: string, words: import('#lib/types.js').TranscriptWord[], model: string, languageCode?: string }>}
 */
export async function transcribeObject(key) {
	if (!config.elevenLabsApiKey) throw new Error('ELEVENLABS_API_KEY is not set');

	const obj = await getObjectStream(key);
	if (!obj) throw new Error(`object not found: ${key}`);
	// Clips are small (a caption, not a lecture); buffer rather than stream so
	// the multipart body has a known length.
	const chunks = [];
	for await (const c of obj.stream) chunks.push(c);
	const blob = new Blob(chunks, { type: mimeFromKey(key) });

	const form = new FormData();
	form.append('file', blob, key.split('/').pop() || 'clip');
	form.append('model_id', config.elevenLabsModel);
	form.append('timestamps_granularity', 'word');
	form.append('tag_audio_events', 'false');

	const res = await fetch('https://api.elevenlabs.io/v1/speech-to-text', {
		method: 'POST',
		headers: { 'xi-api-key': config.elevenLabsApiKey },
		body: form
	});
	const ct = res.headers.get('content-type') || '';
	const data = ct.includes('application/json') ? await res.json().catch(() => null) : await res.text();
	if (!res.ok) {
		const reason = typeof data === 'string' ? data.slice(0, 300) : JSON.stringify(data ?? {}).slice(0, 300);
		throw new Error(`ElevenLabs ${res.status}: ${reason}`);
	}
	if (!data || typeof data !== 'object') throw new Error('Unexpected response from ElevenLabs');

	/** @type {any} */
	const d = data;
	/** @type {import('#lib/types.js').TranscriptWord[]} */
	const words = Array.isArray(d.words)
		? d.words
				.filter((/** @type {any} */ w) => w && (w.type === 'word' || w.type === undefined) && typeof w.text === 'string' && w.text.trim())
				.map((/** @type {any} */ w) => ({
					t: String(w.text).trim(),
					s: round(Number(w.start) || 0),
					e: round(Number(w.end) || 0)
				}))
		: [];

	return {
		transcript: String(d.text || '').trim(),
		words,
		model: config.elevenLabsModel,
		languageCode: typeof d.language_code === 'string' ? d.language_code : undefined
	};
}

const round = (/** @type {number} */ n) => Math.round(n * 100) / 100;
