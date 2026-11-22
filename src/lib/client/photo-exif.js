/**
 * Read the two bits of EXIF we care about from a photo *before* upload:
 * where it was taken and when. Cloudflare Images strips GPS on delivery and
 * our own canvas downscale strips everything, so this is the only moment the
 * data is reachable.
 *
 * Returns null when the file has no EXIF, no GPS and no timestamp, or can't be
 * parsed (PNG screenshots, HEIC in some browsers) — callers treat null as
 * "nothing to offer".
 */
import exifr from 'exifr';

/**
 * @param {File | Blob} file
 * @returns {Promise<import('#lib/types.js').DumpPhotoExif | null>}
 */
export async function readPhotoExif(file) {
	try {
		const [gps, tags] = await Promise.all([
			exifr.gps(file).catch(() => null),
			exifr
				.parse(file, { pick: ['DateTimeOriginal', 'OffsetTimeOriginal', 'CreateDate'] })
				.catch(() => null)
		]);
		/** @type {import('#lib/types.js').DumpPhotoExif} */
		const out = {};
		if (gps && Number.isFinite(gps.latitude) && Number.isFinite(gps.longitude)) {
			out.lat = round6(gps.latitude);
			out.lon = round6(gps.longitude);
		}
		const taken = toIso(tags?.DateTimeOriginal ?? tags?.CreateDate, tags?.OffsetTimeOriginal);
		if (taken) out.takenAt = taken;
		return out.lat !== undefined || out.takenAt ? out : null;
	} catch {
		return null;
	}
}

/** @param {number} n */
function round6(n) {
	return Math.round(n * 1e6) / 1e6;
}

/**
 * exifr revives DateTimeOriginal into a Date using the *browser's* zone, since
 * EXIF timestamps carry none. If the camera also wrote OffsetTimeOriginal
 * ("+02:00"), rebuild the instant with the real offset; otherwise accept the
 * local-zone guess — ordering within a trip is what matters.
 * @param {unknown} d
 * @param {unknown} offset
 */
function toIso(d, offset) {
	if (!(d instanceof Date) || Number.isNaN(d.getTime())) return undefined;
	if (typeof offset === 'string' && /^[+-]\d{2}:\d{2}$/.test(offset)) {
		const pad = (/** @type {number} */ n) => String(n).padStart(2, '0');
		const local =
			`${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
			`T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}${offset}`;
		const t = new Date(local);
		if (!Number.isNaN(t.getTime())) return t.toISOString();
	}
	return d.toISOString();
}
