import { listStoriesFor, publicStory } from '#lib/server/stories.js';
import { hasTag, matchesSearch, searchTerms, tagCounts } from '#lib/story-search.js';
import { isPrivate } from '#lib/story-access.js';
import { locationPrecision } from '#lib/server/settings.js';
import { publicLocation } from '#lib/location-privacy.js';
import { getYear } from '#lib/format.js';
import { isNote, leadBlock, noteThumbId, storyLabel } from '#lib/story-label.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ locals, url }) => {
	// ?tag=… narrows the logbook to stories carrying that tag, ?q=… to stories
	// containing those words. Both together means both.
	const tag = url.searchParams.get('tag')?.trim() || null;
	const q = (url.searchParams.get('q') ?? '').trim().slice(0, 200);
	const terms = searchTerms(q);
	// Only what this visitor may read: the tag list and the search never reveal
	// a private story, a draft, or anything kept out of the reader's copy.
	const all = await listStoriesFor(locals);
	const precision = await locationPrecision();
	const visible = all.filter((s) => (!tag || hasTag(s, tag)) && matchesSearch(publicStory(s, precision), terms));

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
			location: publicLocation(s.location, precision),
			summary: s.summary,
			coverImageId: noteThumbId(s),
			draft: s.draft,
			private: isPrivate(s)
		});
	}

	return {
		tag,
		q,
		found: visible.length,
		tags: tagCounts(all),
		years: [...grouped.entries()].map(([year, stories]) => ({ year, stories }))
	};
};
