import { listStoriesFor } from '#lib/server/stories.js';
import { isPrivate } from '#lib/story-access.js';
import { getYear } from '#lib/format.js';
import { isNote, leadBlock, noteThumbId, storyLabel } from '#lib/story-label.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ locals, url }) => {
	// ?tag=… narrows the logbook to stories carrying that tag.
	const tag = url.searchParams.get('tag')?.trim() || null;
	const all = await listStoriesFor(locals);
	const visible = tag ? all.filter((s) => (s.tags || []).some((t) => t.toLowerCase() === tag.toLowerCase())) : all;

	/** @type {Map<number, any[]>} */
	const grouped = new Map();
	for (const s of visible) {
		const y = getYear(s.date);
		let bucket = grouped.get(y);
		if (!bucket) {
			bucket = [];
			grouped.set(y, bucket);
		}
		bucket.push({
			slug: s.slug,
			title: storyLabel(s),
			note: isNote(s),
			// For the thumbnail of a story with no picture.
			kind: leadBlock(s)?.type ?? '',
			date: s.date,
			location: s.location,
			summary: s.summary,
			coverImageId: noteThumbId(s),
			draft: s.draft,
			private: isPrivate(s)
		});
	}

	return {
		tag,
		years: [...grouped.entries()].map(([year, stories]) => ({ year, stories }))
	};
};
