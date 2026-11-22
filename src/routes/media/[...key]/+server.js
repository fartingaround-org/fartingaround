/**
 * Public media: full-resolution gallery photos and their derivatives.
 *
 *   GET /media/gallery/<id>/orig.<ext>
 *   GET /media/gallery/<id>/w<N>.jpg
 *
 * Only the `gallery/` prefix is served here — the object store also holds
 * draft media, which goes through the authenticated /api/drafts route. Keys
 * are uuid-based and immutable, so this caches hard. Supports Range requests.
 *
 * Like /img, this address is cacheable by anyone, so it only serves a file
 * that a published public story uses; anything else is redirected to
 * /private/media, which checks who is asking.
 */

import { serveObject } from '#lib/server/serve.js';
import { getMediaIndex } from '#lib/server/stories.js';
import { redirectToPrivate } from '#lib/server/viewer.js';
import { objectFlags } from '#lib/media-access.js';

const ALLOWED_PREFIXES = ['gallery/'];

/** @type {import('./$types').RequestHandler} */
export async function GET({ params, request, url }) {
	const key = params.key || '';
	if (!ALLOWED_PREFIXES.some((p) => key.startsWith(p))) return new Response('not found', { status: 404 });
	if (!objectFlags(await getMediaIndex(), key).public) return redirectToPrivate(url, `/private/media/${key}`);
	try {
		return await serveObject(request, key);
	} catch {
		return new Response('not found', { status: 404 });
	}
}

export const HEAD = GET;
