import { fail, redirect } from '@sveltejs/kit';
import { getStory, listStories, putStory, toSlug } from '#lib/server/stories.js';
import { isPrivate } from '#lib/story-access.js';
import { listDrafts } from '#lib/server/drafts.js';
import { storyLabel } from '#lib/story-label.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({}) => {
	const stories = await listStories();
	// Old phone captures that were never turned into stories. The list has no
	// tab of its own any more, so it is only linked while something is waiting.
	const oldDrafts = (await listDrafts().catch(() => [])).filter((d) => d.status === 'open').length;
	return {
		oldDrafts,
		stories: stories.map((s) => ({
			slug: s.slug,
			title: storyLabel(s),
			date: s.date,
			location: s.location,
			coverImageId: s.coverImageId,
			draft: s.draft,
			private: isPrivate(s),
			blockCount: s.blocks?.length || 0,
			updatedAt: s.updatedAt
		}))
	};
};

/** @type {import('./$types').Actions} */
export const actions = {
	create: async ({ request }) => {
		const form = await request.formData();
		const title = String(form.get('title') || '').trim();
		const date = String(form.get('date') || '').trim();
		if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return fail(400, { error: 'A date is required.' });

		// No title: a note, addressed by its date (specs/notes-titleless-posts.md).
		let slug = toSlug(String(form.get('slug') || title)) || date;

		// avoid collisions
		const existing = await getStory(slug);
		if (existing) {
			let i = 2;
			while (await getStory(`${slug}-${i}`)) i++;
			slug = `${slug}-${i}`;
		}

		await putStory({
			slug,
			title,
			date,
			location: '',
			summary: '',
			coverImageId: '',
			tags: [],
			blocks: [],
			createdAt: new Date().toISOString(),
			updatedAt: new Date().toISOString(),
			draft: true
		});

		throw redirect(303, `/admin/${slug}`);
	}
};
