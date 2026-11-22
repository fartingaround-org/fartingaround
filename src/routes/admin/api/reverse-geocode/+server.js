/**
 * GET /admin/api/reverse-geocode?lat=…&lon=…  →  { name: string | null }
 *
 * A place name for a pair of coordinates, for the story editor's "use the
 * photo's location" button. Asks OpenStreetMap's Nominatim from the server,
 * so the author's browser is not what tells a third party where a photo was
 * taken, and shapes the answer the way the phone app does: neighbourhood or
 * town, region, country.
 */
import { json } from '@sveltejs/kit';

const PLACE_KEYS = ['neighbourhood', 'suburb', 'village', 'town', 'city', 'hamlet', 'county'];

/** @type {import('./$types').RequestHandler} */
export async function GET({ url, locals, request }) {
	if (!locals.isAdmin) return new Response('forbidden', { status: 403 });
	const lat = Number(url.searchParams.get('lat'));
	const lon = Number(url.searchParams.get('lon'));
	if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
		return json({ error: 'bad_coords' }, { status: 400 });
	}
	try {
		const res = await fetch(`https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}&zoom=14&addressdetails=1`, {
			headers: {
				'user-agent': `fartingaround (${url.origin})`,
				accept: 'application/json',
				'accept-language': request.headers.get('accept-language') || 'en'
			},
			signal: AbortSignal.timeout(8000)
		});
		if (!res.ok) return json({ name: null });
		const body = await res.json();
		const addr = body?.address || {};
		const locality = PLACE_KEYS.map((k) => addr[k]).find(Boolean);
		const parts = [...new Set([locality, addr.state || addr.region, addr.country].filter(Boolean))];
		return json({ name: parts.length ? parts.join(', ') : body?.display_name || null });
	} catch {
		// No name is not an error: the coordinates are still set.
		return json({ name: null });
	}
}
