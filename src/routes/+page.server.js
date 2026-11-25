import { listStoriesFor, publicStory } from '#lib/server/stories.js';
import { isNote, leadBlock, noteThumbId } from '#lib/story-label.js';
import { isPrivate } from '#lib/story-access.js';
import { locationPrecision } from '#lib/server/settings.js';
import { publicLocation } from '#lib/location-privacy.js';

/**
 * A story with no title is shown as its content, not as a card: send its
 * first block along (the visitor's version, without author-only photo data).
 * @param {import('#lib/types.js').Story} s
 */
function noteFields(s) {
	if (!isNote(s)) return { note: false };
	const pub = publicStory(s);
	return { note: true, lead: leadBlock(pub), more: (pub.blocks || []).length > 1, thumbId: noteThumbId(pub) };
}

/** @param {import('#lib/types.js').Story} s @param {unknown} precision  the site's locationPrecision() */
function card(s, precision) {
	return {
		slug: s.slug,
		title: s.title,
		...noteFields(s),
		date: s.date,
		location: publicLocation(s.location, precision),
		summary: s.summary,
		// No cover chosen: the story's first picture (or its video's poster) stands
		// in, as it does in the logbook. Only a story with no picture at all is set
		// as words. The focal point belongs to the chosen cover, not the stand-in.
		coverImageId: noteThumbId(s),
		coverFocus: s.coverImageId ? s.coverFocus : undefined,
		draft: s.draft,
		private: isPrivate(s)
	};
}

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ locals }) => {
	// Published stories only: the author's drafts live in /admin, not on the
	// front page (where the newest one would take the featured slot).
	const visible = (await listStoriesFor(locals)).filter((s) => !s.draft);
	// The six newest titled stories, with the notes that fall among them: a run
	// of notes must not push the stories off the page. Never more than 14 in all.
	/** @type {import('#lib/types.js').Story[]} */
	const picked = [];
	let titled = 0;
	for (const s of visible) {
		if (picked.length >= 14) break;
		if (!isNote(s)) {
			if (titled >= 6) break;
			titled++;
		}
		picked.push(s);
	}
	// The wide slot at the top goes to the newest titled story, never to a note.
	const lead = picked.find((s) => !isNote(s)) ?? null;
	const precision = await locationPrecision();
	return {
		featured: lead ? card(lead, precision) : null,
		stories: picked.filter((s) => s !== lead).map((s) => card(s, precision))
	};
};
