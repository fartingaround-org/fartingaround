/**
 * Gallery files that are not public. Same keys as /media.
 *
 *   GET /private/media/gallery/<id>/w<N>.jpg[?token=<reader token>]
 *
 * Checked on every request, like /private/img. Never cache this path in a
 * shared cache.
 */

import { serveObject } from '#lib/server/serve.js';
import { getMediaIndex } from '#lib/server/stories.js';
import { PRIVATE_CACHE, privateNotFound, viewerFor } from '#lib/server/viewer.js';
import { mayFetchPrivate, objectFlags } from '#lib/media-access.js';

const ALLOWED_PREFIXES = ['gallery/'];

/** @type {import('./$types').RequestHandler} */
export async function GET(event) {
	const key = event.params.key || '';
	if (!ALLOWED_PREFIXES.some((p) => key.startsWith(p))) return privateNotFound();
	if (!mayFetchPrivate(objectFlags(await getMediaIndex(), key), await viewerFor(event))) return privateNotFound();
	try {
		return await serveObject(event.request, key, { cacheControl: PRIVATE_CACHE });
	} catch {
		return privateNotFound();
	}
}

export const HEAD = GET;
