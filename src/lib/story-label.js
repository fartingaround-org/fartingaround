/**
 * Posts without a title ("notes"): specs/notes-titleless-posts.md.
 *
 * Nothing is stored for this. A story whose title is blank is a note, and
 * what to call it in the places that need words (lists, map pins, the tab)
 * is worked out here from what is in it.
 */
import { formatDateShort } from './format.js';

/** @typedef {{ title?: string, summary?: string, date?: string, coverImageId?: string, blocks?: any[] }} StoryLike */

const KIND = /** @type {Record<string, string>} */ ({
	photo: 'Photo',
	dump: 'Photos',
	gallery: 'Photos',
	diptych: 'Photos',
	audio: 'Recording',
	video: 'Video'
});

/**
 * No title. "Untitled" is what the server used to fill in, so it counts as none.
 * @param {StoryLike | null | undefined} story
 */
export function isNote(story) {
	const t = (story?.title || '').trim();
	return !t || t.toLowerCase() === 'untitled';
}

/** @param {unknown} text @param {number} [max] */
function clip(text, max = 80) {
	if (typeof text !== 'string') return '';
	const line = text.trim().split(/\n/)[0].replace(/\s+/g, ' ').trim();
	if (line.length <= max) return line;
	const cut = line.slice(0, max - 1);
	const at = cut.lastIndexOf(' ');
	return (at > max * 0.6 ? cut.slice(0, at) : cut).trimEnd() + '…';
}

/**
 * The first block that carries content: what a note shows inline.
 * @param {StoryLike} story
 */
export function leadBlock(story) {
	return (story.blocks || []).find((b) => b && b.type !== 'divider') ?? null;
}

/**
 * What to call a story where a title is needed. The title when there is
 * one; otherwise the summary, a caption, the first line of text, the start of
 * a transcript, or the kind and date.
 * @param {StoryLike} story
 */
export function storyLabel(story) {
	if (!isNote(story)) return (story.title || '').trim();
	const blocks = (story.blocks || []).filter(Boolean);
	const found =
		clip(story.summary) ||
		first(blocks, (b) => clip(b.caption) || (b.type === 'dump' ? first(b.photos || [], (p) => clip(p?.caption)) : '')) ||
		first(blocks, (b) => (b.type === 'text' || b.type === 'pull' || b.type === 'aside' ? clip(b.body) : '')) ||
		first(blocks, (b) => (b.type === 'audio' ? clip(b.transcript) : ''));
	if (found) return found;
	const kind = KIND[leadBlock(story)?.type] ?? 'Note';
	const day = story.date ? formatDateShort(story.date) : '';
	return day ? `${kind} · ${day}` : kind;
}

/**
 * A picture to stand for the story in a list: its cover, else its first photo.
 * @param {StoryLike} story
 * @returns {string}
 */
export function noteThumbId(story) {
	if (story.coverImageId) return story.coverImageId;
	for (const b of story.blocks || []) {
		if (!b) continue;
		if (b.type === 'photo' && b.imageId) return b.imageId;
		if (b.type === 'dump') { const p = (b.photos || []).find((/** @type {any} */ x) => x?.imageId); if (p) return p.imageId; }
		if (b.type === 'gallery') { const p = (b.items || []).find((/** @type {any} */ x) => x?.imageId); if (p) return p.imageId; }
		if (b.type === 'diptych' && b.left?.imageId) return b.left.imageId;
		if (b.type === 'video' && b.posterImageId) return b.posterImageId;
	}
	return '';
}

/**
 * @template T
 * @param {T[]} list @param {(item: T) => string} f
 */
function first(list, f) {
	for (const item of list) {
		const v = f(item);
		if (v) return v;
	}
	return '';
}

/**
 * When a story was published, for feeds: the moment it first stopped being a
 * draft (`publishedAt`, kept by the server), so several posts from one day
 * carry their real times and keep their order in a feed reader. Stories from
 * before that was kept go by when they were created.
 *
 * A backdated story (a trip written up weeks later) is the exception: its
 * place in a reader's timeline is its own date, so it says noon UTC that day.
 * @param {{ date: string, publishedAt?: string, createdAt?: string }} story
 * @returns {string} ISO 8601
 */
export function storyPublishedAt(story) {
	const noon = `${story.date}T12:00:00Z`;
	const at = Date.parse(story.publishedAt || story.createdAt || '');
	if (Number.isNaN(at)) return noon;
	return Math.abs(at - Date.parse(noon)) <= 36 * 3600 * 1000 ? new Date(at).toISOString() : noon;
}
