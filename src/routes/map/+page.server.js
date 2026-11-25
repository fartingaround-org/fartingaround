/**
 * Loads every visible story that has GPS coords for the /map atlas page.
 *
 * Stories are grouped into "locations": clusters of stories captured within a
 * short distance of each other (e.g. the many posts from home). Each location
 * becomes a single map marker whose popup lets you page through the stories
 * captured there. We strip down to just the fields the map needs so we don't
 * ship blocks/transcripts to the client just to render a pin.
 */

import { listStoriesFor } from '#lib/server/stories.js';
import { storyLabel } from '#lib/story-label.js';
import { locationPrecision } from '#lib/server/settings.js';
import { publicCoords, publicLocation } from '#lib/location-privacy.js';

// Stories whose coords fall within this many metres of an existing cluster are
// folded into it. ~80 m comfortably absorbs GPS jitter around a single spot
// (a house, a trailhead) without merging genuinely different places in a town.
const CLUSTER_RADIUS_M = 80;

/**
 * Haversine distance in metres between two lat/lon points.
 * @param {number} aLat
 * @param {number} aLon
 * @param {number} bLat
 * @param {number} bLon
 */
function distanceM(aLat, aLon, bLat, bLon) {
	const R = 6371000;
	const toRad = (/** @type {number} */ d) => (d * Math.PI) / 180;
	const dLat = toRad(bLat - aLat);
	const dLon = toRad(bLon - aLon);
	const lat1 = toRad(aLat);
	const lat2 = toRad(bLat);
	const h =
		Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
	return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ locals }) => {
	const visible = await listStoriesFor(locals);

	// The map shows a story where the story page says it is: at the site's
	// location precision, so a pin is never more exact than the page (and with
	// locations hidden there are no pins at all).
	const precision = await locationPrecision();

	const withCoords = visible
		.map((s) => ({ ...s, coords: publicCoords(s.coords, precision) }))
		.filter(
			/** @returns {s is typeof s & { coords: { lat: number, lon: number } }} */
			(s) => !!s.coords
		)
		.map((s) => ({
			slug: s.slug,
			title: storyLabel(s),
			date: s.date,
			location: publicLocation(s.location, precision) || '',
			summary: s.summary || '',
			coverImageId: s.coverImageId || '',
			coords: { lat: s.coords.lat, lon: s.coords.lon },
			draft: !!s.draft
		}));

	// Greedy distance clustering. listStoriesFor already returns newest-first, so
	// each cluster's `stories` stays in that order as we append.
	/** @type {Array<{ lat: number, lon: number, stories: typeof withCoords }>} */
	const clusters = [];
	for (const story of withCoords) {
		const hit = clusters.find(
			(c) => distanceM(c.lat, c.lon, story.coords.lat, story.coords.lon) <= CLUSTER_RADIUS_M
		);
		if (hit) {
			hit.stories.push(story);
			// Re-centre on the running mean so the marker sits among its stories.
			const n = hit.stories.length;
			hit.lat = (hit.lat * (n - 1) + story.coords.lat) / n;
			hit.lon = (hit.lon * (n - 1) + story.coords.lon) / n;
		} else {
			clusters.push({ lat: story.coords.lat, lon: story.coords.lon, stories: [story] });
		}
	}

	const locations = clusters.map((c) => {
		const stories = [...c.stories].sort((a, b) => (a.date < b.date ? 1 : -1));
		return {
			lat: c.lat,
			lon: c.lon,
			count: stories.length,
			// Label the cluster with the most recent story's place text.
			label: stories.find((s) => s.location)?.location || '',
			stories
		};
	});

	return { locations };
};
