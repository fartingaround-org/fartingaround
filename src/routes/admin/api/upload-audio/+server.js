/**
 * Admin upload of a short audio clip — a spoken caption for a photo-dump
 * photo. Stored under `audio/<id>.<ext>`; the id doubles as the public
 * playback id at /api/stories/:slug/audio/:id.
 *
 *   POST /admin/api/upload-audio   multipart: file, durationMs?
 *   → { id, key, mimeType, durationMs? }
 */

import { json, error } from '@sveltejs/kit';
import { randomBytes } from 'node:crypto';
import { extFromMime, putObject } from '#lib/server/objects.js';

const MAX_BYTES = 50 * 1024 * 1024;

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, locals }) => {
	if (!locals.isAdmin) throw error(401, 'Unauthorized');

	const form = await request.formData();
	const file = form.get('file');
	if (!(file instanceof File)) throw error(400, 'No file uploaded.');
	if (file.size > MAX_BYTES) {
		throw error(413, `File is ${(file.size / 1024 / 1024).toFixed(1)} MB — the limit here is ${MAX_BYTES / 1024 / 1024} MB.`);
	}

	let mimeType = (file.type || '').split(';')[0].trim().toLowerCase();
	if (!mimeType.startsWith('audio/')) {
		// Browsers sometimes hand over .m4a as video/mp4 or with no type at all.
		const name = (file.name || '').toLowerCase();
		if (/\.(m4a|mp4|aac)$/.test(name)) mimeType = 'audio/mp4';
		else if (/\.mp3$/.test(name)) mimeType = 'audio/mpeg';
		else if (/\.(ogg|oga|opus)$/.test(name)) mimeType = 'audio/ogg';
		else if (/\.wav$/.test(name)) mimeType = 'audio/wav';
		else if (/\.webm$/.test(name)) mimeType = 'audio/webm';
		else throw error(415, `Not an audio file (${file.type || 'unknown type'}).`);
	}

	const durationRaw = Number(form.get('durationMs'));
	const durationMs = Number.isFinite(durationRaw) && durationRaw > 0 ? Math.round(durationRaw) : undefined;

	const id = randomBytes(9).toString('base64url');
	const key = `audio/${id}.${extFromMime(mimeType)}`;

	try {
		await putObject(key, file);
	} catch (err) {
		console.error('[upload-audio] failed:', err);
		throw error(500, err instanceof Error ? err.message : 'Upload failed');
	}
	return json({ id, key, mimeType, ...(durationMs ? { durationMs } : {}) });
};
