/**
 * One story, for authoring clients.
 *
 *   GET    /api/v1/stories/:slug   → { story }   the author's copy (private
 *                                  EXIF and object keys included)
 *   PUT    /api/v1/stories/:slug   → { story }   create or replace
 *   DELETE /api/v1/stories/:slug   → 204         story + every file it referenced
 *
 * PUT is conditional. Send the `updatedAt` your copy was based on, either as
 * `If-Match: "<updatedAt>"` or `baseUpdatedAt` in the body. If the server's
 * copy has a different `updatedAt` you get 409 `{ error: "conflict",
 * current: <server story> }` and nothing is written — show keep-mine /
 * take-theirs, then either PUT again with the new base or GET and merge.
 * Replacing an existing story with no base at all is also a 409
 * (`no_base`), unless `?force=1`. Creating (slug not yet taken) needs none.
 *
 * `createdAt`/`updatedAt` in the body are ignored; the server owns them.
 */
import { getStory, putStory, deleteStory, StoryConflict } from '#lib/server/stories.js';
import { deleteStoryMedia } from '#lib/server/story-media.js';
import { normalizeStory } from '#lib/server/story-validate.js';
import { corsHeaders, fail, isAuthor, isSlug, preflight, readJson, reply } from '#lib/server/api-v1.js';

/** @type {import('./$types').RequestHandler} */
export const OPTIONS = async ({ request }) => preflight(request);

/** @type {import('./$types').RequestHandler} */
export const GET = async ({ request, locals, params }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	const story = await getStory(params.slug);
	if (!story) return fail(request, 404, 'story_not_found', { slug: params.slug });
	return reply(request, { story }, 200, { etag: `"${story.updatedAt}"` });
};

/** @type {import('./$types').RequestHandler} */
export const PUT = async ({ request, locals, params, url }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	if (!isSlug(params.slug)) return fail(request, 400, 'bad_slug', { slug: params.slug });
	const body = await readJson(request);

	const ifMatch = request.headers.get('if-match');
	/** @type {string | undefined} */
	let base = typeof body.baseUpdatedAt === 'string' ? body.baseUpdatedAt : undefined;
	if (ifMatch) base = ifMatch.replace(/^W\//, '').replace(/^"|"$/g, '');
	const force = url.searchParams.get('force') === '1';

	const existing = await getStory(params.slug);
	if (existing && base === undefined && !force) {
		return fail(request, 409, 'no_base', {
			hint: 'Send If-Match or baseUpdatedAt with the updatedAt your copy came from, or ?force=1 to overwrite.',
			current: existing
		});
	}

	const normalized = normalizeStory({ ...body, slug: params.slug }, existing ?? undefined);
	if ('error' in normalized) return fail(request, 400, normalized.error, normalized.detail);

	try {
		const story = await putStory(normalized.story, force ? {} : { ifUpdatedAt: base });
		return reply(request, { story }, existing ? 200 : 201, { etag: `"${story.updatedAt}"` });
	} catch (e) {
		if (e instanceof StoryConflict) return fail(request, 409, 'conflict', { current: e.current });
		throw e;
	}
};

/** @type {import('./$types').RequestHandler} */
export const DELETE = async ({ request, locals, params }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	const story = await getStory(params.slug);
	if (!story) return fail(request, 404, 'story_not_found', { slug: params.slug });
	await deleteStoryMedia(story);
	await deleteStory(params.slug);
	return new Response(null, { status: 204, headers: corsHeaders(request) });
};
