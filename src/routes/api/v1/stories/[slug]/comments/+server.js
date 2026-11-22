/**
 * A story's comments, for apps.
 *
 *   GET  /api/v1/stories/:slug/comments
 *        → { enabled, canPost, as, comments }
 *   POST /api/v1/stories/:slug/comments   { body, parentId?, photoId? }
 *        → 201 { id }
 *
 * Who is asking is worked out the way private media does it (viewer.js):
 * the admin cookie or the capture token is the owner; a reader is known by
 * their cookie or a reader token (`?token=…` or `Authorization: Bearer …`);
 * anyone else is the public.
 *
 * Reading follows the site exactly: a story you cannot read is a 404, and
 * `enabled` is false (with no comments) wherever the story page would show
 * no comment section — a draft, comments switched off, or readers-only
 * comments and no reader.
 *
 * Posting here is for the OWNER only. A reader token is a feed address:
 * it is pasted into feed readers and can leak, so it reads but never
 * writes. Readers comment on the site, signed in.
 *
 * `comments` is the same shape the story page uses (PublicComment in
 * src/lib/server/comments.js): top-level comments oldest first, each with
 * `replies`; `photoId` names the photo-dump photo a comment is about, and
 * is only set while that photo is still in the story.
 */
import { getDb } from '#lib/server/db.js';
import { CommentError, addComment, listComments } from '#lib/server/comments.js';
import { authorName, commentsMode } from '#lib/server/settings.js';
import { getStory } from '#lib/server/stories.js';
import { viewerFor } from '#lib/server/viewer.js';
import { fail, preflight, readJson, reply } from '#lib/server/api-v1.js';
import { canReadStory, canSeeComments } from '#lib/story-access.js';
import { dumpPhotoIds } from '#lib/blocks.js';

/** Never stored anywhere shared: the answer depends on who asked. */
const PRIVATE = { 'cache-control': 'private, no-store' };

/**
 * The story and the asker, or null when they may not read it.
 * @param {import('./$types').RequestEvent} event
 */
async function context(event) {
	const who = await viewerFor(event);
	const owner = who.isAdmin || who.isAuthorApp;
	const viewer = { isAdmin: owner, reader: who.reader };
	const story = await getStory(event.params.slug);
	if (!story || !canReadStory(story, viewer)) return null;
	const enabled = !story.draft && canSeeComments(await commentsMode(), viewer);
	return { story, viewer, owner, enabled };
}

/** @type {import('./$types').RequestHandler} */
export const OPTIONS = async ({ request }) => preflight(request);

/** @type {import('./$types').RequestHandler} */
export const GET = async (event) => {
	const { request, params } = event;
	const ctx = await context(event);
	if (!ctx) return fail(request, 404, 'story_not_found', { slug: params.slug });
	if (!ctx.enabled) return reply(request, { enabled: false, canPost: false, as: null, comments: [] }, 200, PRIVATE);

	const name = await authorName();
	const comments = await listComments(await getDb(), ctx.story.slug, {
		isAdmin: ctx.owner,
		readerId: ctx.viewer.reader?.id,
		ownerName: name,
		photoIds: dumpPhotoIds(ctx.story)
	});
	return reply(request, { enabled: true, canPost: ctx.owner, as: ctx.owner ? name : null, comments }, 200, PRIVATE);
};

/** @type {import('./$types').RequestHandler} */
export const POST = async (event) => {
	const { request, params } = event;
	const ctx = await context(event);
	if (!ctx) return fail(request, 404, 'story_not_found', { slug: params.slug });
	if (!ctx.owner) return fail(request, 403, 'owner_only', { message: 'Only the site owner can comment through the app. Readers comment on the site.' });
	if (!ctx.enabled) return fail(request, 409, 'comments_closed', { message: 'Comments are not open on this story.' });

	const body = await readJson(request);
	const photoId = typeof body.photoId === 'string' && body.photoId ? body.photoId : null;
	if (photoId && !dumpPhotoIds(ctx.story).has(photoId)) {
		return fail(request, 400, 'bad_photo', { message: 'That photo is no longer in this story.' });
	}
	try {
		const id = await addComment(await getDb(), {
			storySlug: ctx.story.slug,
			author: { owner: true },
			body: body.body,
			parentId: typeof body.parentId === 'string' && body.parentId ? body.parentId : null,
			photoId
		});
		return reply(request, { id }, 201, PRIVATE);
	} catch (e) {
		if (e instanceof CommentError) return fail(request, 400, e.code, { message: e.message });
		throw e;
	}
};
