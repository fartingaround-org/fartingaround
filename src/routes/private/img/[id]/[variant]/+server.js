/**
 * Images that are not public: those of private stories, drafts, and photos
 * not attached to a story yet. Same grammar as /img.
 *
 *   GET /private/img/<id>/<variant>[?token=<reader token>]
 *
 * Checked on every request: the author always; a member when a published
 * story they can read uses the image. Everyone else gets a plain 404.
 * Never put a shared cache in front of /private/ — responses are marked
 * private so only the viewer's own browser keeps them.
 */

import { serveImage } from '#lib/server/image-serve.js';
import { getMediaIndex } from '#lib/server/stories.js';
import { PRIVATE_CACHE, privateNotFound, viewerFor } from '#lib/server/viewer.js';
import { imageFlags, mayFetchPrivate } from '#lib/media-access.js';

/** @type {import('./$types').RequestHandler} */
export async function GET(event) {
	const { params, request } = event;
	const flags = imageFlags(await getMediaIndex(), params.id);
	if (!mayFetchPrivate(flags, await viewerFor(event))) return privateNotFound();
	return serveImage(request, params.id, params.variant, { cacheControl: PRIVATE_CACHE });
}

export const HEAD = GET;
