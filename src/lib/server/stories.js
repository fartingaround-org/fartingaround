/**
 * Story repository. One row per story: the Story document as JSON plus the
 * columns we list and sort by. Works on SQLite or Postgres via db.js.
 */

import { getDb, nowIso, parseDoc } from './db.js';
import { defaultVisibility } from './settings.js';
import { canReadStory, resolveVisibility } from '../story-access.js';
import { publicCoords, publicLocation } from '../location-privacy.js';
import { buildMediaIndex } from '../media-access.js';
import { storyLabel } from '../story-label.js';

/**
 * Which files are public, derived from the stories (see lib/media-access.js).
 * Kept in memory and rebuilt after any story is saved or deleted through
 * this module. A script that writes the database directly needs a restart.
 */
let storiesVersion = 0;
/** @type {{ version: number, index: import('../media-access.js').MediaIndex } | null} */
let mediaIndexCache = null;

export async function getMediaIndex() {
	if (mediaIndexCache && mediaIndexCache.version === storiesVersion) return mediaIndexCache.index;
	const version = storiesVersion;
	const index = buildMediaIndex(await listStories());
	if (version === storiesVersion) mediaIndexCache = { version, index };
	return index;
}

/**
 * Every story, newest-first by its own date; within a day, the one published
 * last comes first (not the one edited last, which would reshuffle a day
 * whenever a typo is fixed). Drafts, and stories from before publish times
 * were kept, go by when they were created.
 *
 * AUTHOR'S VIEW: drafts and private stories included. Only the admin and the
 * authoring API may use this. Anything a visitor can reach uses
 * listStoriesFor() instead.
 * @returns {Promise<import('#lib/types.js').Story[]>}
 */
/** @param {any} s */
const when = (s) => s.publishedAt || s.createdAt || '';

export async function listStories() {
	const db = await getDb();
	const rows = await db.all('SELECT doc FROM stories ORDER BY date DESC, updated_at DESC');
	/** @type {import('#lib/types.js').Story[]} */
	const stories = rows.map((r) => JSON.parse(r.doc));
	return stories.sort((a, b) => (a.date === b.date ? when(b).localeCompare(when(a)) : a.date < b.date ? 1 : -1));
}

/**
 * AUTHOR'S VIEW, like listStories(). Visitors' routes use getStoryFor().
 * @param {string} slug
 * @returns {Promise<import('#lib/types.js').Story | null>}
 */
export async function getStory(slug) {
	const db = await getDb();
	return parseDoc(await db.get('SELECT doc FROM stories WHERE slug = ?', [slug]));
}

/**
 * The stories this viewer may read, newest first. Pass the request's
 * `locals`; pass `{}` for "anyone at all" (feeds, public APIs).
 * @param {import('../story-access.js').Viewer} viewer
 */
export async function listStoriesFor(viewer) {
	return (await listStories()).filter((s) => canReadStory(s, viewer));
}

/**
 * One story if this viewer may read it, else null. A story that exists but
 * is not theirs to read looks exactly like one that does not exist.
 * @param {string} slug
 * @param {import('../story-access.js').Viewer} viewer
 */
export async function getStoryFor(slug, viewer) {
	const story = await getStory(slug);
	return story && canReadStory(story, viewer) ? story : null;
}

/**
 * Thrown by putStory when the caller's copy is stale. `current` is what the
 * server has, so a client can show a keep-mine / take-theirs choice.
 */
export class StoryConflict extends Error {
	/** @param {import('#lib/types.js').Story} current */
	constructor(current) {
		super(`story ${current.slug} changed on the server (updatedAt ${current.updatedAt})`);
		this.name = 'StoryConflict';
		this.current = current;
	}
}

/**
 * Create or update a story.
 *
 * Pass `ifUpdatedAt` — the `updatedAt` the caller's copy was based on — to
 * make the write conditional: when the stored story has moved on, a
 * StoryConflict is thrown and nothing is written. Two editors (phone + web),
 * one author: last-write-wins with a warning is all the concurrency control
 * this needs.
 *
 * @param {import('#lib/types.js').Story} story
 * @param {{ ifUpdatedAt?: string }} [opts]
 */
export async function putStory(story, opts = {}) {
	const db = await getDb();
	const now = nowIso();
	const existing = await getStory(story.slug);
	if (existing && opts.ifUpdatedAt !== undefined && existing.updatedAt !== opts.ifUpdatedAt) {
		throw new StoryConflict(existing);
	}
	const toSave = {
		...story,
		createdAt: existing?.createdAt || now,
		updatedAt: now
	};
	// The moment it first stopped being a draft: what a reader calls "when it was
	// posted". Set once; later edits, or unpublishing and publishing again, keep it.
	const publishedAt = /** @type {any} */ (existing)?.publishedAt || (toSave.draft ? '' : now);
	if (publishedAt) /** @type {any} */ (toSave).publishedAt = publishedAt;
	else delete (/** @type {any} */ (toSave).publishedAt);
	// Visibility never changes by omission: an update that doesn't mention it
	// keeps what was stored, and a new story takes the owner's site default.
	const visibility = resolveVisibility(story.visibility, existing, existing ? 'public' : await defaultVisibility());
	if (visibility) toSave.visibility = visibility;
	else delete toSave.visibility;
	await db.run(
		`INSERT INTO stories (slug, date, draft, updated_at, doc) VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT (slug) DO UPDATE SET date = excluded.date, draft = excluded.draft,
		   updated_at = excluded.updated_at, doc = excluded.doc`,
		[toSave.slug, toSave.date || '', toSave.draft ? 1 : 0, now, JSON.stringify(toSave)]
	);
	storiesVersion++;
	return toSave;
}

/** @param {string} slug */
export async function deleteStory(slug) {
	const db = await getDb();
	await db.run('DELETE FROM stories WHERE slug = ?', [slug]);
	storiesVersion++;
}

/**
 * Pick a slug that isn't taken: `base`, else `base-2`, `base-3`, …
 * @param {string} base
 */
export async function uniqueSlug(base) {
	let slug = base || 'story';
	if (!(await getStory(slug))) return slug;
	let i = 2;
	while (await getStory(`${slug}-${i}`)) i++;
	return `${slug}-${i}`;
}

/**
 * The list-view shape of a story: what an index, the admin list, or a phone
 * drafts screen needs, without the blocks.
 * @param {import('#lib/types.js').Story} s
 * @returns {import('#lib/types.js').StorySummary & { label: string, updatedAt: string, createdAt: string, blockCount: number }}
 */
export function summarizeStory(s) {
	return {
		slug: s.slug,
		title: s.title,
		// What to call it when the title is blank.
		label: storyLabel(s),
		date: s.date,
		location: s.location,
		summary: s.summary,
		coverImageId: s.coverImageId,
		tags: s.tags,
		draft: s.draft,
		visibility: s.visibility,
		createdAt: s.createdAt,
		updatedAt: s.updatedAt,
		blockCount: (s.blocks || []).length
	};
}

/**
 * @param {string} input
 */
export function toSlug(input) {
	return input
		.toLowerCase()
		.normalize('NFD')
		.replace(/[̀-ͯ]/g, '')
		.replace(/[^a-z0-9-]+/g, '-')
		.replace(/^-+|-+$/g, '')
		.slice(0, 80);
}


/**
 * The version of a story that visitors receive. Strips author-only data —
 * today that's the raw EXIF (GPS + time) kept on photo-dump photos, which
 * exists so a location can be applied from the editor later; a photo's
 * location is public only once `coords` has been set on it deliberately.
 *
 * It also decides how precisely the story's own `coords` go out: see
 * src/lib/location-privacy.js. Without a `precision` they are rounded to the
 * approximate area, so a caller that forgets to pass the site's setting (or
 * `.map(publicStory)`, which passes an index) can never publish the exact spot.
 *
 * Call this in every public load that ships a story's blocks to the client.
 *
 * @param {import('#lib/types.js').Story} story
 * @param {unknown} [precision]  the site's locationPrecision() setting
 * @returns {import('#lib/types.js').Story}
 */
export function publicStory(story, precision) {
	return {
		...story,
		coords: publicCoords(story.coords, precision),
		// A place name passes through unless it is itself a precise position.
		location: publicLocation(story.location, precision),
		blocks: (story.blocks || []).map((b) => {
			// A single photo keeps what its file said too (for the editor's "use the
			// photo's location"); that is the author's, never a visitor's.
			if (b.type === 'photo' && 'exif' in b) {
				const { exif: _exif, ...rest } = /** @type {any} */ (b);
				return rest;
			}
			if (b.type !== 'dump') return b;
			return {
				...b,
				photos: (b.photos || []).map((p) => {
					const { exif: _exif, ...rest } = p;
					if (rest.audio) {
						// The object-store key is server-side detail; readers use audio.id.
						const { key: _key, ...audio } = rest.audio;
						rest.audio = /** @type {any} */ (audio);
					}
					return rest;
				})
			};
		})
	};
}
