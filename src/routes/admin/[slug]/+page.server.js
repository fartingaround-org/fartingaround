import { error, redirect } from '@sveltejs/kit';
import { getStory, deleteStory } from '#lib/server/stories.js';
import { deleteStoryMedia } from '#lib/server/story-media.js';
import { defaultVisibility } from '#lib/server/settings.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ params }) => {
	const story = await getStory(params.slug);
	if (!story) throw error(404, 'No such story');
	return { story, defaultVisibility: await defaultVisibility() };
};

/** @type {import('./$types').Actions} */
export const actions = {
	delete: async ({ params }) => {
		const story = await getStory(params.slug);
		if (!story) throw redirect(303, '/admin');

		// Best-effort: every image, clip and full-res original the story referenced.
		await deleteStoryMedia(story);

		await deleteStory(params.slug);
		throw redirect(303, '/admin');
	}
};
