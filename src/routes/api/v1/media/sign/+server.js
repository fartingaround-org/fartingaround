/**
 * Step 1 of a large upload: get signed PUT URL(s) on this server.
 *
 *   POST /api/v1/media/sign
 *     { kind: 'audio' | 'video', mimeType, id? }
 *       → { id, key, url, expiresAt }
 *
 * PUT the bytes to each `url` with no auth header, then POST /media/confirm
 * with the key(s). Put the returned `key` into the block (`r2Key` on an
 * audio/video block, `audio.key` on a dump photo) and `id` is its public
 * playback id.
 *
 * (`kind: 'photo-full'`, the full-resolution upload for the astro gallery,
 * was withdrawn 2026-11 when galleries became display-only.)
 *
 * `id` is optional; pass your own (≥ 6 url-safe chars) when you want retries
 * to overwrite the same object instead of leaving strays.
 */
import { randomBytes } from 'node:crypto';
import { extFromMime, storyMediaKey } from '#lib/server/objects.js';
import { signedUploadUrl } from '#lib/server/upload-urls.js';
import { fail, isAuthor, preflight, readJson, reply } from '#lib/server/api-v1.js';

const TTL_HINT_MS = 15 * 60 * 1000; // matches upload-urls.js

/** @type {import('./$types').RequestHandler} */
export const OPTIONS = async ({ request }) => preflight(request);

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, locals, url }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	const body = await readJson(request);
	const kind = String(body.kind || '');
	const id = typeof body.id === 'string' && /^[A-Za-z0-9_-]{6,64}$/.test(body.id) ? body.id : randomBytes(9).toString('base64url');
	const expiresAt = new Date(Date.now() + TTL_HINT_MS).toISOString();

	if (kind === 'audio' || kind === 'video') {
		const mimeType = String(body.mimeType || '');
		const ext = extFromMime(mimeType);
		if (ext === 'bin' || !mimeType.startsWith(`${kind}/`)) return fail(request, 400, 'bad_mime', { kind, mimeType });
		const key = storyMediaKey(kind, id, ext);
		return reply(request, { id, key, url: signedUploadUrl(url.origin, key), expiresAt });
	}

	return fail(request, 400, 'bad_kind', { kind, want: ['audio', 'video'] });
};
