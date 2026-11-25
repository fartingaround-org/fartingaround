import { error, fail } from '@sveltejs/kit';
import { getDb } from '#lib/server/db.js';
import { CommentError, addComment, deleteOwnComment, editComment, listComments } from '#lib/server/comments.js';
import { authorName, commentsMode, locationPrecision } from '#lib/server/settings.js';
import { getStoryFor, listStoriesFor, publicStory } from '#lib/server/stories.js';
import { canSeeComments, isPrivate } from '#lib/story-access.js';
import { dumpPhotoIds } from '#lib/blocks.js';
import { storyLabel } from '#lib/story-label.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ params, locals, setHeaders }) => {
	// Drafts are the author's; private stories are for invited members. To
	// everyone else both look exactly like a story that does not exist.
	const story = await getStoryFor(params.slug, locals);
	if (!story) throw error(404, 'No such story');
	const priv = isPrivate(story);
	const mode = await commentsMode();

	// The stories either side of this one, by date, among those THIS viewer
	// may read. Drafts are never offered, even to the author.
	const shelf = (await listStoriesFor(locals)).filter((s) => !s.draft || s.slug === story.slug);
	const at = shelf.findIndex((s) => s.slug === story.slug);
	const newer = at > 0 ? shelf[at - 1] : null;
	const older = at >= 0 && at < shelf.length - 1 ? shelf[at + 1] : null;
	const link = (/** @type {import('#lib/types.js').Story | null} */ s) => (s ? { slug: s.slug, title: storyLabel(s) } : null);
	const privateNeighbour = [newer, older].some((s) => s && isPrivate(s));

	if (priv) setHeaders({ 'cache-control': 'private, no-store', 'x-robots-tag': 'noindex, noarchive' });
	// Readers-only comments on a public story, or a link to a private story
	// beside it: what a signed-in reader gets must never be stored where the
	// public could be served it.
	else if ((mode === 'readers' && (locals.isAdmin || locals.reader)) || privateNeighbour) setHeaders({ 'cache-control': 'private, no-store' });

	// The public sees no comment section at all when comments are for readers only.
	const enabled = !story.draft && canSeeComments(mode, locals);
	const owner = await authorName();
	const comments = enabled
		? await listComments(await getDb(), story.slug, { isAdmin: locals.isAdmin, readerId: locals.reader?.id, ownerName: owner, photoIds: dumpPhotoIds(story) })
		: [];
	// Visitors (admin included — the editor has its own load) get the
	// sanitized version, so unapplied photo EXIF never leaves the server and
	// the story's location is only as precise as the site's setting allows.
	return {
		story: publicStory(story, await locationPrecision()),
		private: priv,
		newer: link(newer),
		older: link(older),
		comments,
		commenting: {
			enabled,
			// Anyone who can read the story and is signed in may comment on it.
			as: !enabled ? null : locals.isAdmin ? owner : (locals.reader?.displayName ?? null)
		}
	};
};

/**
 * Who is commenting, after re-checking they may read this story right now.
 * @param {import('./$types').RequestEvent} event
 * @returns {Promise<import('#lib/server/comments.js').Author | null>}
 */
async function authorFor({ params, locals }) {
	const story = await getStoryFor(params.slug, locals);
	if (!story) throw error(404, 'No such story');
	if (story.draft || !canSeeComments(await commentsMode(), locals)) return null;
	if (locals.isAdmin) return { owner: true };
	return locals.reader ? { contactId: locals.reader.id } : null;
}

/**
 * @param {import('./$types').RequestEvent} event
 * @param {(author: import('#lib/server/comments.js').Author, f: FormData) => Promise<unknown>} fn
 */
async function act(event, fn) {
	const author = await authorFor(event);
	if (!author) return fail(403, { commentError: 'Sign in to comment.' });
	const f = await event.request.formData();
	try {
		await fn(author, f);
	} catch (e) {
		if (e instanceof CommentError) {
			return fail(400, { commentError: e.message, draft: String(f.get('body') || ''), parentId: String(f.get('parentId') || ''), id: String(f.get('id') || ''), photoId: String(f.get('photoId') || '') });
		}
		throw e;
	}
	return { commented: true };
}

/** @type {import('./$types').Actions} */
export const actions = {
	comment: (event) =>
		act(event, async (author, f) => {
			// A comment may be about one photo, but only a photo that is in this
			// story's photo dumps right now.
			const photoId = String(f.get('photoId') || '') || null;
			if (photoId) {
				const story = await getStoryFor(event.params.slug, event.locals);
				if (!story || !dumpPhotoIds(story).has(photoId)) throw new CommentError('bad_photo');
			}
			return addComment(await getDb(), { storySlug: event.params.slug, author, body: f.get('body'), parentId: String(f.get('parentId') || '') || null, photoId });
		}),
	editComment: (event) => act(event, async (author, f) => editComment(await getDb(), String(f.get('id') || ''), author, f.get('body'))),
	deleteComment: (event) => act(event, async (author, f) => deleteOwnComment(await getDb(), String(f.get('id') || ''), author))
};
