/**
 * Public playback for a story's audio.
 *
 *   GET /api/stories/:slug/audio/:id
 *
 * `:id` is either an `audio` block's id or the `audio.id` of a photo inside a
 * `dump` block (a spoken caption). Stories are public, so the audio they
 * embed is too. Streams the stored object with the recorded MIME type (Range
 * supported, for scrubbing).
 *
 * (We don't proxy via the existing /api/drafts/.../media/... endpoint
 * because that one is admin/capture-token gated by design — drafts can
 * contain unpublished material. Story audio is already publishable, so
 * a separate, no-auth path is the right fit.)
 */

import { getStory } from '#lib/server/stories.js';
import { serveObject } from '#lib/server/serve.js';
import { isCaptureRequest } from '#lib/server/auth-token.js';
import { canReadStory, isPrivate } from '#lib/story-access.js';
import { PRIVATE_CACHE, viewerFor } from '#lib/server/viewer.js';

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
	// Drafts: the admin, or the phone with its bearer token (it plays and transcribes its own clips).
	if (story.draft && !locals.isAdmin && !isCaptureRequest(request)) {
		return notFound('story_is_draft', {
			slug: params.slug,
			hint: 'Story is still flagged as draft. Toggle Draft off in the admin editor, or sign in as admin.'
		});
	}
	// Private stories: invited members only. Same answer as a missing story.
	if (!isCaptureRequest(request) && !canReadStory(story, await viewerFor({ locals, request, url }))) {
		return notFound('story_not_found', { slug: params.slug });
	}

	/** @type {{ key: string, mimeType?: string } | null} */
	let found = null;
	for (const b of story.blocks || []) {
		if (b.type === 'audio' && b.id === params.blockId) {
			found = { key: b.r2Key, mimeType: b.mimeType };
			break;
		}
		if (b.type === 'dump') {
			const p = (b.photos || []).find((p) => p.audio?.id === params.blockId);
			if (p?.audio) {
				found = { key: p.audio.key, mimeType: p.audio.mimeType };
				break;
			}
		}
	}
	if (!found) {
		return notFound('block_not_found', {
			slug: params.slug,
			blockId: params.blockId,
			blockTypesInStory: (story.blocks || []).map((b) => `${b.type}#${b.id}`)
		});
	}

	const res = await serveObject(request, found.key, {
		mime: found.mimeType || 'application/octet-stream',
		// A private story's media must never sit in a shared cache.
		cacheControl: isPrivate(story) || story.draft ? PRIVATE_CACHE : 'public, max-age=86400'
	});
	if (res.status === 404) {
		return notFound('object_not_found', { slug: params.slug, blockId: params.blockId, key: found.key });
	}
	return res;
}
