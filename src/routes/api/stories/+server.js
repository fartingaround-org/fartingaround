/**
 * Public JSON list of live stories.
 *
 *   GET /api/stories         → { stories: [ { slug, title, date, ... } ] }
 *   OPTIONS                  → CORS preflight
 *
 * No auth — these are the same stories anyone can see on the site.
 * Excludes drafts. Resolves Cloudflare Image cover URLs server-side so
 * the phone app gets absolute URLs it can load directly.
 *
 * Used by the Live tab in the Android capture app.
 */

import { json } from '@sveltejs/kit';
import { listStoriesFor } from '#lib/server/stories.js';
import { imageUrl } from '#lib/image.js';
import { locationPrecision } from '#lib/server/settings.js';
import { publicLocation } from '#lib/location-privacy.js';

const ALLOWED_ORIGINS = [
	'https://localhost',
	'capacitor://localhost',
	'http://localhost',
	'http://localhost:5173',
	'http://localhost:5174'
];

/** @param {Request} request */
function cors(request) {
	const origin = request.headers.get('origin') || '';
	const allow = ALLOWED_ORIGINS.includes(origin) ? origin : '*';
	return {
		'Access-Control-Allow-Origin': allow,
		'Access-Control-Allow-Methods': 'GET, OPTIONS',
		'Access-Control-Allow-Headers': 'Content-Type',
		'Access-Control-Max-Age': '86400',
		Vary: 'Origin'
	};
}

/**
 * Absolute cover-image URL for a client that lives off-site (the phone app).
 * @param {string} origin
 * @param {string | undefined} imageId
 * @param {number} [w]
 */
function coverUrl(origin, imageId, w = 800) {
	if (!imageId) return null;
	return `${origin}${imageUrl(imageId, { w, q: 85, fit: 'cover' })}`;
}

/** @type {import('./$types').RequestHandler} */
export async function OPTIONS({ request }) {
	return new Response(null, { status: 204, headers: cors(request) });
}

/** @type {import('./$types').RequestHandler} */
export async function GET({ request, url }) {
	const headers = cors(request);

	// No auth here, so: published, public stories only.
	const precision = await locationPrecision();
	const live = (await listStoriesFor({}))
		.map((s) => ({
			slug: s.slug,
			title: s.title,
			date: s.date,
			location: publicLocation(s.location, precision) || null,
			summary: s.summary || null,
			tags: s.tags || [],
			coverUrl: coverUrl(url.origin, s.coverImageId, 800),
			coverThumbUrl: coverUrl(url.origin, s.coverImageId, 240),
			href: `${url.origin}/stories/${s.slug}`
		}));

	return json({ stories: live }, { headers });
}
