/**
 * One-shot migration: backfill `coords` on stories that have a
 * `location` text field but no GPS lat/lon.
 *
 *   POST /admin/api/geocode-stories?dry=1
 *
 * Background: the /map page filters by `coords`, not `location`, because
 * a place-name string ("Westminster, CO") isn't enough to drop a pin.
 * Stories captured before we wired GPS through the promote endpoint, or
 * created directly in admin without coords, will have a location string
 * and nothing else — so they're invisible on the map. This pass runs each
 * such story's `location` through Nominatim and writes the result back.
 *
 * Strategy:
 *   1. List all stories.
 *   2. Filter to ones where `location` is set and `coords` is missing.
 *   3. For each, GET https://nominatim.openstreetmap.org/search?q=… paced
 *      at 1.1 seconds per request (their usage policy permits 1/sec).
 *   4. If the result has lat/lon, save back as coords {lat, lon}.
 *
 * Idempotent: stories that already have coords are skipped. Re-running
 * after a partial run is safe.
 *
 * Auth: admin-only.
 *
 * Note on Cloudflare Workers wall-clock: paced at 1.1 sec per story, 30
 * stories takes 33 seconds — under the Pages Functions limit (which is
 * generous, around 30-60s for typical configs). If you ever have hundreds
 * of un-geocoded stories, this would need batching; for personal scale
 * (tens) it's fine.
 */

import { json } from '@sveltejs/kit';
import { listStories, putStory } from '#lib/server/stories.js';

const NOMINATIM = 'https://nominatim.openstreetmap.org/search';
const PACE_MS = 1100;

function sleep(/** @type {number} */ ms) {
	return new Promise((r) => setTimeout(r, ms));
}

/**
 * Forward-geocode a free-form place string.
 *
 * Returns a `{ result, reason }` pair instead of just null-on-failure so
 * the migration page can show the real reason the request failed (HTTP
 * status, network error, empty result, etc.) — earlier "no result" reports
 * turned out to be 403s caused by a missing User-Agent. Nominatim's usage
 * policy requires an identifiable UA or Referer.
 *
 * @param {string} query
 * @param {string} origin  this site's address: who is asking, as the map service's policy requires
 * @returns {Promise<{ result: { lat: number, lon: number, display_name?: string } | null, reason?: string }>}
 */
async function geocode(query, origin) {
	const url = `${NOMINATIM}?format=jsonv2&limit=1&q=${encodeURIComponent(query)}`;
	let res;
	try {
		res = await fetch(url, {
			headers: {
				// Cloudflare Workers DOES forward the User-Agent we set here on
				// outbound fetches. Without it Nominatim's free instance often
				// returns empty/403 silently.
				'User-Agent': `fartingaround (${origin})`,
				Accept: 'application/json',
				'Accept-Language': 'en'
			}
		});
	} catch (e) {
		return { result: null, reason: `fetch threw: ${e instanceof Error ? e.message : String(e)}` };
	}
	if (!res.ok) {
		const body = await res.text().catch(() => '');
		return {
			result: null,
			reason: `Nominatim ${res.status} ${res.statusText}: ${body.slice(0, 200)}`
		};
	}
	let data;
	try {
		data = await res.json();
	} catch (e) {
		return { result: null, reason: `Nominatim returned non-JSON: ${e instanceof Error ? e.message : String(e)}` };
	}
	if (!Array.isArray(data) || !data.length) {
		return { result: null, reason: `Nominatim returned no match for "${query}"` };
	}
	const lat = Number(data[0].lat);
	const lon = Number(data[0].lon);
	if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
		return { result: null, reason: `Nominatim returned invalid lat/lon: ${JSON.stringify(data[0])}` };
	}
	return { result: { lat, lon, display_name: data[0].display_name } };
}

/** @type {import('./$types').RequestHandler} */
export async function POST({ url, locals }) {
	if (!locals.isAdmin) return new Response('forbidden', { status: 403 });

	const dry = url.searchParams.get('dry') === '1';

	const stories = await listStories();
	/** @type {Array<{slug:string,title:string,action:string,location?:string,coords?:any,displayName?:string,reason?:string}>} */
	const report = [];

	const candidates = stories.filter((s) => {
		const hasLocation = typeof s.location === 'string' && s.location.trim();
		const hasCoords =
			s.coords && Number.isFinite(s.coords.lat) && Number.isFinite(s.coords.lon);
		return hasLocation && !hasCoords;
	});

	let geocoded = 0;
	let failed = 0;
	const totalNoChange = stories.length - candidates.length;

	for (let i = 0; i < candidates.length; i++) {
		const story = candidates[i];
		const { result, reason } = await geocode(story.location || '', url.origin);
		if (!result) {
			report.push({
				slug: story.slug,
				title: story.title,
				location: story.location,
				action: 'no-match',
				reason: reason || 'Nominatim returned no result'
			});
			failed++;
		} else {
			report.push({
				slug: story.slug,
				title: story.title,
				location: story.location,
				action: dry ? 'would-geocode' : 'geocoded',
				coords: { lat: result.lat, lon: result.lon },
				displayName: result.display_name
			});
			if (!dry) {
				await putStory({
					.../** @type {any} */ (story),
					coords: { lat: result.lat, lon: result.lon }
				});
			}
			geocoded++;
		}
		// Pace ourselves except after the last request — no point waiting if
		// there's nothing more to do.
		if (i < candidates.length - 1) await sleep(PACE_MS);
	}

	return json({
		dry,
		summary: {
			totalStories: stories.length,
			candidates: candidates.length,
			geocoded,
			failed,
			noChange: totalNoChange
		},
		stories: report
	});
}
