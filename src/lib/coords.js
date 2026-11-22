/**
 * Parsers for human-typed coordinate strings.
 *
 * Supports two input shapes:
 *   - Decimal lat/lon:  "39.618852, -104.9039125" (with or without spaces)
 *   - Plus Code (OLC):  "85FQXP3X+XR" — the Open Location Code Google Maps
 *                       shows when you right-click a spot.
 *
 * Short Plus Codes that rely on a reference locality (e.g. "XP3X+XR Denver")
 * are not supported, because resolving them needs a geocoder call to find
 * the reference. Use the full long-form code that starts with the area
 * prefix instead — that's what Google Maps shows by default.
 */

/**
 * Parse whatever the user typed into a {lat, lon} or null. Whitespace is
 * tolerated; case in Plus Codes is normalized.
 *
 * @param {string} input
 * @returns {{ lat: number, lon: number, source: 'latlon' | 'pluscode' } | null}
 */
export function parseCoordsInput(input) {
	if (!input || typeof input !== 'string') return null;
	const trimmed = input.trim();
	if (!trimmed) return null;

	const ll = parseLatLon(trimmed);
	if (ll) return { ...ll, source: 'latlon' };

	const pc = decodePlusCode(trimmed);
	if (pc) return { ...pc, source: 'pluscode' };

	return null;
}

/**
 * Parse "lat, lon" / "lat,lon" / "lat lon". Allows negatives, decimal
 * fractions, and an optional explicit + sign.
 *
 * @param {string} s
 * @returns {{ lat: number, lon: number } | null}
 */
function parseLatLon(s) {
	const m = s.match(/^([+-]?\d+(?:\.\d+)?)[\s,]+([+-]?\d+(?:\.\d+)?)$/);
	if (!m) return null;
	const lat = Number(m[1]);
	const lon = Number(m[2]);
	if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
	if (lat < -90 || lat > 90) return null;
	if (lon < -180 || lon > 180) return null;
	return { lat, lon };
}

const PLUS_CODE_ALPHA = '23456789CFGHJMPQRVWX';

/**
 * Decode a full Open Location Code into the centre of its cell. Returns
 * null for short codes (no area prefix), invalid characters, or codes
 * outside the standard length range.
 *
 * Algorithm:
 *   - Strip the '+' separator and any trailing locality (space-delimited).
 *   - First 10 characters: pair encoding — each pair (i, i+1) contributes
 *     lat += alpha_index(i) * resolution, lon += alpha_index(i+1) * resolution.
 *     Resolution starts at 20° and divides by 20 each pair: 20°, 1°, 0.05°,
 *     0.0025°, 0.000125° (≈14m).
 *   - Characters 11–15 (grid refinement) are accepted but ignored — 10-char
 *     precision is plenty for "where was this story" purposes.
 *
 * @param {string} input
 * @returns {{ lat: number, lon: number } | null}
 */
export function decodePlusCode(input) {
	let code = input.trim().toUpperCase();
	const spaceIdx = code.indexOf(' ');
	if (spaceIdx > 0) code = code.slice(0, spaceIdx);
	if (code.indexOf('+') === -1) return null;

	// Strip '+' and padding '0's. Padding only appears in short codes which
	// we don't support, but stripping is harmless.
	const clean = code.replace(/[+0]/g, '');
	if (clean.length < 4 || clean.length > 15) return null;
	for (const c of clean) {
		if (!PLUS_CODE_ALPHA.includes(c)) return null;
	}

	let lat = -90;
	let lon = -180;
	let resolution = 20;
	const pairChars = Math.min(clean.length, 10);
	for (let i = 0; i < pairChars; i += 2) {
		lat += PLUS_CODE_ALPHA.indexOf(clean[i]) * resolution;
		if (i + 1 < pairChars) {
			lon += PLUS_CODE_ALPHA.indexOf(clean[i + 1]) * resolution;
		}
		resolution /= 20;
	}
	// `resolution` divided one extra time past the last pair; bring it back
	// to the actual cell size and centre lat/lon inside that cell.
	const cellSize = resolution * 20;
	lat += cellSize / 2;
	lon += cellSize / 2;

	if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
	return { lat, lon };
}
