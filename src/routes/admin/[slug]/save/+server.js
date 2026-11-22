import { error, json } from '@sveltejs/kit';
import { getStory, putStory, StoryConflict } from '#lib/server/stories.js';
import { visibilityOf } from '#lib/story-access.js';

/**
 * Save from the web editor. `payload.baseUpdatedAt` is the `updatedAt` the
 * editor loaded; if the story has since been saved elsewhere (the phone),
 * this answers 409 with the server's copy instead of overwriting it. Omit
 * `baseUpdatedAt` (or send `force: true`) to overwrite regardless.
 */
/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, params, locals }) => {
	if (!locals.isAdmin) throw error(401, 'Unauthorized');

	const payload = await request.json();
	const existing = await getStory(params.slug);
	if (!existing) throw error(404, 'No such story');

	/** @type {{ ifUpdatedAt?: string }} */
	const cond = {};
	if (typeof payload.baseUpdatedAt === 'string' && !payload.force) cond.ifUpdatedAt = payload.baseUpdatedAt;

	let saved;
	try {
		saved = await putStory({
		...existing,
		title: payload.title ?? existing.title,
		date: payload.date ?? existing.date,
		location: payload.location ?? '',
		// `coords` semantics in the payload:
		//   undefined → don't touch (preserve what was on the existing story)
		//   null      → explicit clear, removes coords from the story
		//   { lat, lon } → set
		coords: normalizeCoords(payload.coords, existing.coords),
		summary: payload.summary ?? '',
		coverImageId: payload.coverImageId ?? '',
		coverFocus: normalizeFocus(payload.coverFocus),
		tags: Array.isArray(payload.tags) ? payload.tags : [],
		draft: !!payload.draft,
		// Unset in the payload → `existing.visibility` from the spread above stands.
		...(visibilityOf(payload.visibility) ? { visibility: visibilityOf(payload.visibility) } : {}),
		blocks: Array.isArray(payload.blocks) ? payload.blocks : [],
		slug: params.slug
	}, cond);
	} catch (e) {
		if (e instanceof StoryConflict) {
			return json({ error: 'conflict', current: e.current }, { status: 409 });
		}
		throw e;
	}

	return json({ ok: true, updatedAt: saved.updatedAt });
};

/**
 * Reconcile the incoming `coords` payload with what's on the existing
 * story, validating the lat/lon shape and clamping into legal ranges.
 *
 * @param {any} incoming
 * @param {any} existing
 */
function normalizeCoords(incoming, existing) {
	if (incoming === undefined) return existing; // not touched in this save
	if (incoming === null) return undefined; // explicit clear
	if (typeof incoming !== 'object') return existing;
	const lat = Number(incoming.lat);
	const lon = Number(incoming.lon);
	if (!Number.isFinite(lat) || !Number.isFinite(lon)) return existing;
	if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return existing;
	return { lat, lon };
}

/**
 * @param {any} f
 * @returns {{x: number, y: number} | undefined}
 */
function normalizeFocus(f) {
	if (!f || typeof f !== 'object') return undefined;
	const x = Number(f.x);
	const y = Number(f.y);
	if (!Number.isFinite(x) || !Number.isFinite(y)) return undefined;
	return {
		x: Math.max(0, Math.min(1, x)),
		y: Math.max(0, Math.min(1, y))
	};
}
