/**
 * How precisely a story's own location goes out to anyone but its author.
 *
 * A story's `coords` are usually filled in by the phone at the moment of
 * posting, so a post from home is a pin on home. This is the ONLY place the
 * rule for publishing them is written down: publicStory() (server/stories.js),
 * the /map page and the story lists all go through the functions here.
 *
 *  - 'area' (the default): rounded to a cell about a kilometre across. Every
 *    post from inside the cell gets the same point, so repeat posts from one
 *    spot cannot be averaged back into it.
 *  - 'exact': as stored. For owners who only post from places they are happy
 *    to pinpoint.
 *  - 'hidden': never sent. The place name still shows.
 *
 * A story's `location` is its place name, typed or looked up, and goes out as
 * written. The one exception is a "name" that is itself a precise position
 * (coordinates left there when no name could be found, or a pasted Plus
 * Code): publicLocation() gives that the same treatment as `coords`.
 *
 * A photo-dump photo's `coords` are a different thing: each is switched on by
 * hand in the editor, so they are published as chosen and are not touched here.
 *
 * Pure functions, no imports: tested directly in tests/location-privacy.test.js.
 */

/** @typedef {'area' | 'exact' | 'hidden'} LocationPrecision */

/** What a site gets until its owner chooses, and what any unrecognised value means. */
export const DEFAULT_LOCATION_PRECISION = 'area';

/** Decimal places kept for 'area': 0.01° is about 1.1 km north to south. */
const AREA_DECIMALS = 2;

/** @param {unknown} v @returns {LocationPrecision | undefined} */
export function locationPrecisionOf(v) {
	return v === 'area' || v === 'exact' || v === 'hidden' ? v : undefined;
}

/**
 * The coordinates a visitor may be sent, or undefined for none. Anything that
 * is not exactly 'exact' or 'hidden' is treated as 'area', so a missing or
 * bad setting can never publish a precise point.
 *
 * @param {unknown} coords      a story's stored `coords`
 * @param {unknown} [precision]
 * @returns {{ lat: number, lon: number, approximate?: true } | undefined}
 */
export function publicCoords(coords, precision) {
	if (!coords || typeof coords !== 'object') return undefined;
	const { lat, lon } = /** @type {any} */ (coords);
	if (!Number.isFinite(lat) || !Number.isFinite(lon)) return undefined;
	if (precision === 'hidden') return undefined;
	// `accuracy` (the phone's GPS error in metres) is the author's; it never goes out.
	if (precision === 'exact') return { lat, lon };
	const f = 10 ** AREA_DECIMALS;
	// "+ 0" turns -0 into 0, which JSON would print as "0" anyway.
	return { lat: Math.round(lat * f) / f + 0, lon: Math.round(lon * f) / f + 0, approximate: true };
}

/** "44.977753, -93.265011": a pair of decimal degrees and nothing else. */
const COORD_PAIR = /^\s*([+-]?\d{1,3}(?:\.\d+)?)\s*[,;\s]\s*([+-]?\d{1,3}(?:\.\d+)?)\s*$/;
/** A full Plus Code ("86P8XPHM+4X"), optionally followed by a locality. Good to about 14 m. */
const PLUS_CODE = /^\s*[23456789CFGHJMPQRVWX]{8}\+[23456789CFGHJMPQRVWX]{2,}(\s.*)?$/i;

/**
 * The place name a visitor may be sent. Ordinary names pass through. Text
 * that is really a precise position follows the setting: rounded for 'area'
 * (a Plus Code, which cannot be rounded as text, is dropped), as written for
 * 'exact', nothing for 'hidden'. A pair already at two decimals or fewer is
 * no more precise than 'area' and is left alone.
 *
 * @param {unknown} location     a story's stored `location`
 * @param {unknown} [precision]
 * @returns {string | undefined}
 */
export function publicLocation(location, precision) {
	if (typeof location !== 'string' || !location.trim()) return undefined;
	if (precision === 'exact') return location;
	if (PLUS_CODE.test(location)) return undefined;
	const m = location.match(COORD_PAIR);
	if (!m) return location;
	const decimals = Math.max(...[m[1], m[2]].map((n) => (n.split('.')[1] || '').length));
	if (decimals <= AREA_DECIMALS) return location;
	const c = publicCoords({ lat: Number(m[1]), lon: Number(m[2]) }, precision);
	return c ? `≈ ${c.lat.toFixed(AREA_DECIMALS)}, ${c.lon.toFixed(AREA_DECIMALS)}` : undefined;
}
