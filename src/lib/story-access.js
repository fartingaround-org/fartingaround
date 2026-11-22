/**
 * Who may read a story. This is `canRead` from docs/models/readers.als, and
 * the ONLY place that rule is written down in code. Every page, list, feed
 * and API that shows stories to anyone but the author goes through it (via
 * listStoriesFor / getStoryFor in server/stories.js).
 *
 * Pure functions, no imports: tested directly in tests/story-access.test.js.
 */

/** @typedef {'public' | 'private'} Visibility */
/** @typedef {{ isAdmin?: boolean, reader?: { role: 'member' | 'commenter' } | null }} Viewer */

/** @param {unknown} v @returns {Visibility | undefined} */
export function visibilityOf(v) {
	return v === 'private' || v === 'public' ? v : undefined;
}

/**
 * A story with no `visibility` predates the feature and is public. Anything
 * else that is not exactly 'public' counts as private, so a bad value can
 * never expose a story.
 * @param {{ visibility?: unknown }} story
 */
export function isPrivate(story) {
	return story.visibility !== undefined && story.visibility !== 'public';
}

/** Readable by anyone at all: what feeds, public APIs and search engines get. @param {{ draft?: boolean, visibility?: unknown }} story */
export function isPublished(story) {
	return !story.draft && !isPrivate(story);
}

/**
 * @param {{ draft?: boolean, visibility?: unknown }} story
 * @param {Viewer} viewer  `locals` from a request; `reader` is only ever an active contact
 */
export function canReadStory(story, viewer) {
	if (viewer.isAdmin) return true;
	if (story.draft) return false;
	if (!isPrivate(story)) return true;
	return viewer.reader?.role === 'member';
}

/**
 * What a story's visibility should be after a save. A save that does not
 * say (an older phone app, an API client that drops unknown fields) must
 * never flip a private story public, so silence keeps what was there.
 *
 * @param {unknown} incoming       what the caller sent, if anything
 * @param {{ visibility?: unknown } | null | undefined} existing  the stored story, if this is an update
 * @param {Visibility} siteDefault  the owner's choice for new stories
 * @returns {Visibility | undefined}  undefined = leave the field off (legacy public)
 */
export function resolveVisibility(incoming, existing, siteDefault) {
	const said = visibilityOf(incoming);
	if (said) return said;
	if (existing) return existing.visibility === undefined ? undefined : isPrivate(existing) ? 'private' : 'public';
	return siteDefault;
}

/** @typedef {'off' | 'readers' | 'everyone'} CommentsMode */

/** @param {unknown} v @returns {CommentsMode | undefined} */
export function commentsModeOf(v) {
	return v === 'off' || v === 'readers' || v === 'everyone' ? v : undefined;
}

/**
 * May this viewer see a story's comment section at all? (Whether they can
 * read the story is a separate, earlier check.)
 *  - 'everyone': anyone who can read the story sees its comments.
 *  - 'readers': only the owner and signed-in invited readers do; the public
 *    sees the story with no sign that comments exist.
 *  - 'off': nobody.
 * Posting always needs a signed-in reader or the owner, in every mode.
 * @param {CommentsMode} mode @param {Viewer} viewer
 */
export function canSeeComments(mode, viewer) {
	if (mode === 'everyone') return true;
	if (mode === 'readers') return !!viewer.isAdmin || !!viewer.reader;
	return false;
}
