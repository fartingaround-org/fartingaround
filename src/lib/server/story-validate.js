/**
 * Shape-check a Story coming over the API before it's stored. Deliberately
 * shallow: top-level fields are typed and clamped, blocks must each have a
 * `type` and an `id` (one is minted if missing) and the rest of a block is
 * the client's business — the renderers already skip what they don't know,
 * and the block set grows over time. Unknown top-level keys are dropped so
 * documents don't accumulate junk.
 */
import { randomBytes } from 'node:crypto';
import { visibilityOf } from '../story-access.js';

const BLOCK_TYPES = new Set(['text', 'photo', 'diptych', 'gallery', 'dump', 'pull', 'aside', 'divider', 'heading', 'code', 'audio', 'video']);
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * @param {Record<string, any>} input   must already carry the final `slug`
 * @param {import('#lib/types.js').Story} [existing]
 * @returns {{ story: import('#lib/types.js').Story } | { error: string, detail?: Record<string, unknown> }}
 */
export function normalizeStory(input, existing) {
	// A blank title is fine: the story is a note (specs/notes-titleless-posts.md).
	const title = (str(input.title) ?? existing?.title ?? '').trim();
	const date = str(input.date) ?? existing?.date ?? new Date().toISOString().slice(0, 10);
	if (!DATE_RE.test(date)) return { error: 'bad_date', detail: { date, want: 'YYYY-MM-DD' } };

	const blocksIn = input.blocks === undefined ? [] : input.blocks;
	if (!Array.isArray(blocksIn)) return { error: 'bad_blocks', detail: { want: 'array' } };
	/** @type {any[]} */
	const blocks = [];
	for (const [i, b] of blocksIn.entries()) {
		if (!b || typeof b !== 'object' || typeof b.type !== 'string') return { error: 'bad_block', detail: { index: i } };
		if (!BLOCK_TYPES.has(b.type)) return { error: 'unknown_block_type', detail: { index: i, type: b.type } };
		blocks.push({ ...b, id: typeof b.id === 'string' && b.id ? b.id : newBlockId(b.type) });
	}

	/** @type {import('#lib/types.js').Story} */
	const story = {
		slug: input.slug,
		title,
		date,
		location: str(input.location) ?? '',
		summary: str(input.summary) ?? '',
		coverImageId: str(input.coverImageId) ?? '',
		tags: Array.isArray(input.tags) ? input.tags.filter((t) => typeof t === 'string' && t.trim()).map((t) => t.trim()) : [],
		blocks,
		draft: input.draft === undefined ? Boolean(existing?.draft) : Boolean(input.draft),
		createdAt: existing?.createdAt ?? '', // putStory owns these
		updatedAt: ''
	};

	// Only set when the client says so. Left off, putStory keeps the stored
	// value (or applies the site default to a new story).
	const visibility = visibilityOf(input.visibility);
	if (visibility) story.visibility = visibility;
	const coords = coordsOf(input.coords);
	if (coords) story.coords = coords;
	const focus = focusOf(input.coverFocus);
	if (focus) story.coverFocus = focus;
	if (input.weather && typeof input.weather === 'object') story.weather = input.weather;
	if (input.astro && typeof input.astro === 'object') story.astro = input.astro;
	// Provenance marker left by scripts/migrate-drafts-to-stories.mjs; kept so
	// re-running the migration after an edit doesn't duplicate the story.
	const from = str(input.migratedFromDraft) ?? /** @type {any} */ (existing)?.migratedFromDraft;
	if (from) /** @type {any} */ (story).migratedFromDraft = from;

	return { story };
}

/** @param {unknown} v */
function str(v) {
	return typeof v === 'string' ? v : undefined;
}

/** @param {unknown} c */
function coordsOf(c) {
	if (!c || typeof c !== 'object') return undefined;
	const { lat, lon, accuracy } = /** @type {any} */ (c);
	if (!Number.isFinite(lat) || !Number.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) return undefined;
	return Number.isFinite(accuracy) ? { lat, lon, accuracy } : { lat, lon };
}

/** @param {unknown} f */
function focusOf(f) {
	if (!f || typeof f !== 'object') return undefined;
	const { x, y } = /** @type {any} */ (f);
	if (!Number.isFinite(x) || !Number.isFinite(y)) return undefined;
	return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
}

/** @param {string} type */
export function newBlockId(type) {
	return `${type.slice(0, 2)}-${randomBytes(5).toString('base64url')}`;
}
