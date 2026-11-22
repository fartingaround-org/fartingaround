/**
 * Public playback for a story's video block.
 *
 *   GET /api/stories/:slug/video/:blockId
 *
 * Stories are public, so the video they embed is too. Looks up the story
 * by slug, finds the video block by id, and streams the R2 object behind
 * its r2Key, honouring HTTP Range so browsers can seek.
 */

import { getStory } from '#lib/server/stories.js';
import { serveObject } from '#lib/server/serve.js';
import { isCaptureRequest } from '#lib/server/auth-token.js';
import { canReadStory, isPrivate } from '#lib/story-access.js';
import { PRIVATE_CACHE, viewerFor } from '#lib/server/viewer.js';

/**
 * Parse an HTTP Range header into the shape R2's binding expects.
 * Supports `bytes=N-`, `bytes=N-M`, and `bytes=-N` (suffix).
 *
 * @param {string | null} header
 * @param {number} size  total object size
 * @returns {null | { offset: number, length: number, end: number }}
 */
function parseRange(header, size) {
	if (!header) return null;
	const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
	if (!match) return null;
	const startStr = match[1];
	const endStr = match[2];

	let offset;
	let end;
	if (startStr === '') {
		// suffix range: last N bytes
		const n = parseInt(endStr, 10);
		if (!Number.isFinite(n) || n <= 0) return null;
		offset = Math.max(0, size - n);
		end = size - 1;
	} else {
		offset = parseInt(startStr, 10);
		end = endStr === '' ? size - 1 : parseInt(endStr, 10);
		if (!Number.isFinite(offset) || !Number.isFinite(end)) return null;
		if (offset > end || offset >= size) return null;
		end = Math.min(end, size - 1);
	}
	return { offset, end, length: end - offset + 1 };
}

/** Diagnostic 404 — leaks the r2Key, but it's not secret on a personal site
 *  and it makes debugging migrated blocks tractable from the browser. */
function notFound(/** @type {string} */ msg, /** @type {object} */ detail) {
	return new Response(JSON.stringify({ error: msg, ...detail }, null, 2), {
		status: 404,
		headers: { 'content-type': 'application/json' }
	});
}

/** @type {import('./$types').RequestHandler} */
export async function GET({ params, request, locals, url }) {

	const story = await getStory(params.slug);
	if (!story) return notFound('story_not_found', { slug: params.slug });
	// Mirror the story page's behaviour: drafts visible to admin, hidden
	// from the public. This is what was causing migrated videos to 404 on
	// stories that hadn't been flipped from draft → published yet.
	// Drafts: the admin, or the phone with its bearer token (it plays and transcribes its own clips).
	if (story.draft && !locals.isAdmin && !isCaptureRequest(request)) {
		return notFound('story_is_draft', {
			slug: params.slug,
			hint:
				'Story is still flagged as draft. Either flip "Draft" off in the ' +
				'admin editor for this story, or sign in as admin to preview.'
		});
	}
	// Private stories: invited members only. Same answer as a missing story.
	if (!isCaptureRequest(request) && !canReadStory(story, await viewerFor({ locals, request, url }))) {
		return notFound('story_not_found', { slug: params.slug });
	}

	const block = (story.blocks || []).find(
		(b) => b.id === params.blockId && b.type === 'video'
	);
	if (!block || block.type !== 'video') {
		return notFound('block_not_found', {
			slug: params.slug,
			blockId: params.blockId,
			blockTypesInStory: (story.blocks || []).map((b) => `${b.type}#${b.id}`)
		});
	}

	// Streams from the object store with Range support (seeking in long clips).
	const res = await serveObject(request, block.r2Key, {
		mime: block.mimeType || 'video/mp4',
		// A private story's media must never sit in a shared cache.
		cacheControl: isPrivate(story) || story.draft ? PRIVATE_CACHE : 'public, max-age=86400'
	});
	if (res.status === 404) {
		return notFound('object_not_found', { slug: params.slug, blockId: params.blockId, r2Key: block.r2Key });
	}
	return res;
}
