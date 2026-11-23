/**
 * Finding stories: by the words in them and by tag.
 *
 * A site holds a person's stories, hundreds at most, and they are already in
 * memory when a list page is drawn. So search is a plain scan of their text:
 * no index to build or keep in step, and the same on SQLite and Postgres.
 */

/** Lower case, accents removed: "Café" and "cafe" find each other. @param {unknown} s */
export function fold(s) {
	return String(s ?? '')
		.normalize('NFD')
		.replace(/[̀-ͯ]/g, '')
		.toLowerCase();
}

/**
 * Every piece of a story a reader can read, as one string: title, summary,
 * place, tags, prose, captions, alt text, section titles and transcripts.
 * Pass the visitor's version of the story (publicStory), so nothing kept from
 * readers can be found by searching for it.
 * @param {Partial<import('./types.js').Story>} story
 */
export function storyText(story) {
	/** @type {unknown[]} */
	const parts = [story.title, story.summary, story.location, ...(story.tags || [])];
	for (const block of story.blocks || []) {
		const b = /** @type {any} */ (block);
		parts.push(b.body, b.label, b.caption, b.alt, b.transcript, b.place);
		for (const side of [b.left, b.right]) if (side) parts.push(side.caption, side.alt);
		for (const item of b.items || []) parts.push(item.caption, item.alt);
		for (const p of b.photos || []) parts.push(p.caption, p.alt, p.section, p.audio?.transcript);
	}
	return parts.filter((p) => typeof p === 'string' && p.trim()).join('\n');
}

/** The words of a search, folded. At most eight, so a pasted essay stays cheap. @param {unknown} q */
export function searchTerms(q) {
	return fold(q).split(/\s+/).filter(Boolean).slice(0, 8);
}

/**
 * Whether a story contains every word searched for, anywhere in it. No words
 * matches everything.
 * @param {Partial<import('./types.js').Story>} story
 * @param {string[]} terms from {@link searchTerms}
 */
export function matchesSearch(story, terms) {
	if (terms.length === 0) return true;
	const text = fold(storyText(story));
	return terms.every((t) => text.includes(t));
}

/** @param {Partial<import('./types.js').Story>} story @param {string} tag */
export function hasTag(story, tag) {
	const want = fold(tag).trim();
	return (story.tags || []).some((t) => fold(t).trim() === want);
}

/**
 * The tags in use, most used first. "Setup" and "setup" are one tag, shown
 * the way it was first written.
 * @param {Array<Partial<import('./types.js').Story>>} stories
 * @returns {Array<{ tag: string, count: number }>}
 */
export function tagCounts(stories) {
	/** @type {Map<string, { tag: string, count: number }>} */
	const seen = new Map();
	for (const s of stories) {
		/** @type {Set<string>} */
		const inThisStory = new Set();
		for (const raw of s.tags || []) {
			const tag = String(raw).trim();
			const key = fold(tag);
			if (!key || inThisStory.has(key)) continue;
			inThisStory.add(key);
			const entry = seen.get(key);
			if (entry) entry.count++;
			else seen.set(key, { tag, count: 1 });
		}
	}
	return [...seen.values()].sort((a, b) => b.count - a.count || fold(a.tag).localeCompare(fold(b.tag)));
}
