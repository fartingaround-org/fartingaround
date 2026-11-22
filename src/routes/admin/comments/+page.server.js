/** The owner's comment list: hide, show or delete any comment; turn comments on or off. */
import { fail } from '@sveltejs/kit';
import { getDb } from '#lib/server/db.js';
import { CommentError, moderateComment, recentComments } from '#lib/server/comments.js';
import { listStories } from '#lib/server/stories.js';
import { authorName } from '#lib/server/settings.js';
import { storyLabel } from '#lib/story-label.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async () => {
	const [comments, stories] = await Promise.all([recentComments(await getDb(), 200, await authorName()), listStories()]);
	const titles = new Map(stories.map((s) => [s.slug, storyLabel(s)]));
	return { comments: comments.map((c) => ({ ...c, storyTitle: titles.get(c.storySlug) || c.storySlug })) };
};

/** @param {Request} request @param {'hide' | 'show' | 'delete'} action */
async function moderate(request, action) {
	const id = String((await request.formData()).get('id') || '');
	try {
		await moderateComment(await getDb(), id, action);
	} catch (e) {
		if (e instanceof CommentError) return fail(400, { error: 'That comment no longer exists.' });
		throw e;
	}
	return { done: true };
}

/** @type {import('./$types').Actions} */
export const actions = {
	hide: ({ request }) => moderate(request, 'hide'),
	show: ({ request }) => moderate(request, 'show'),
	delete: ({ request }) => moderate(request, 'delete')
};
