/**
 * Which stored files are public. This is `restricted` from
 * docs/models/media.als, and the only place that rule is written in code.
 *
 *   A file is PUBLIC only while a published, public story uses it.
 *
 * Everything else is restricted: files of private stories, files of drafts,
 * and files not attached to any story yet (a photo just uploaded into the
 * editor). Restricted files are never served from the cacheable public
 * addresses (/img, /media); they are served from /private/... to the author,
 * and to members when a published story they can read uses the file.
 *
 * Pure functions: tested in tests/media-access.test.js.
 */
import { storyMediaRefs } from './story-media-refs.js';
import { isPublished } from './story-access.js';

/**
 * @typedef {{ public: boolean, member: boolean }} MediaFlags
 *   public: a published public story uses it. member: a published story (public or private) uses it.
 * @typedef {{ images: Map<string, MediaFlags>, objects: Map<string, MediaFlags>, prefixes: { prefix: string, flags: MediaFlags }[] }} MediaIndex
 */

/** @type {MediaFlags} */
const NONE = Object.freeze({ public: false, member: false });

/**
 * @param {import('#lib/types.js').Story[]} stories  every story, drafts and private included
 * @returns {MediaIndex}
 */
export function buildMediaIndex(stories) {
	/** @type {MediaIndex} */
	const index = { images: new Map(), objects: new Map(), prefixes: [] };
	/** @type {Map<string, MediaFlags>} */
	const prefixes = new Map();
	for (const story of stories) {
		const pub = isPublished(story);
		const member = !story.draft;
		const refs = storyMediaRefs(story);
		/** @param {Map<string, MediaFlags>} map @param {string} key */
		const mark = (map, key) => {
			const f = map.get(key) ?? { public: false, member: false };
			f.public ||= pub;
			f.member ||= member;
			map.set(key, f);
		};
		for (const id of refs.imageIds) mark(index.images, id);
		for (const key of refs.objectKeys) mark(index.objects, key);
		for (const prefix of refs.objectPrefixes) mark(prefixes, prefix);
	}
	index.prefixes = [...prefixes].map(([prefix, flags]) => ({ prefix, flags }));
	return index;
}

/** @param {MediaIndex} index @param {string} imageId @returns {MediaFlags} */
export function imageFlags(index, imageId) {
	return index.images.get(imageId) ?? NONE;
}

/**
 * Flags for an object-store key such as `gallery/<id>/w1600.jpg`.
 * @param {MediaIndex} index @param {string} key @returns {MediaFlags}
 */
export function objectFlags(index, key) {
	const exact = index.objects.get(key);
	const out = { public: !!exact?.public, member: !!exact?.member };
	for (const { prefix, flags } of index.prefixes) {
		if (key === prefix || key.startsWith(prefix.endsWith('/') ? prefix : prefix + '/')) {
			out.public ||= flags.public;
			out.member ||= flags.member;
		}
	}
	return out;
}

/**
 * May this viewer fetch the file from a /private/... address?
 * @param {MediaFlags} flags
 * @param {{ isAdmin?: boolean, isAuthorApp?: boolean, reader?: { role: 'member' | 'commenter' } | null }} viewer
 */
export function mayFetchPrivate(flags, viewer) {
	if (viewer.isAdmin || viewer.isAuthorApp) return true;
	if (flags.public) return true; // already public; harmless either way
	return flags.member && viewer.reader?.role === 'member';
}
