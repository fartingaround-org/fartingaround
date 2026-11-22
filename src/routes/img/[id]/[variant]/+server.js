/**
 * Public image delivery with on-demand transforms — the local stand-in for
 * imagedelivery.net.
 *
 *   GET /img/<id>/w=800,h=600,q=85,fit=cover,gravity=0.5x0.3[,format=auto]
 *   GET /img/<id>/public
 *
 * The parameter grammar is what src/lib/image.js emits (unchanged from the
 * Cloudflare days). Widths snap to a fixed ladder and variants are cached on
 * disk, so each distinct rendition is computed once. Image ids are random, so
 * responses are immutable and cached for a year; `format=auto` varies on
 * Accept and says so.
 *
 * This address is cacheable by anyone, so it only ever serves an image that
 * a published public story uses. Anything else (private stories, drafts, a
 * photo not attached to a story yet) is redirected to /private/img, which
 * checks who is asking. The redirect is the same for every viewer.
 */

import { serveImage } from '#lib/server/image-serve.js';
import { getMediaIndex } from '#lib/server/stories.js';
import { redirectToPrivate } from '#lib/server/viewer.js';
import { imageFlags } from '#lib/media-access.js';

/** @type {import('./$types').RequestHandler} */
export async function GET({ params, request, url }) {
	if (!imageFlags(await getMediaIndex(), params.id).public) {
		return redirectToPrivate(url, `/private/img/${params.id}/${params.variant}`);
	}
	return serveImage(request, params.id, params.variant);
}

export const HEAD = GET;
