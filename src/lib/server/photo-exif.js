/**
 * Server-side twin of src/lib/client/photo-exif.js: the two bits of EXIF a
 * photo-dump photo keeps — where and when it was taken — read from the stored
 * original, so a client (the phone app) doesn't need its own parser.
 *
 * GPS stays PRIVATE on the story (`DumpPhoto.exif`) until the author applies
 * it; publicStory() strips it. See types.js.
 */
import exifr from 'exifr';
import { objectPath } from './objects.js';

/**
 * @param {string} key   object key of the original, e.g. images/<id>/orig.jpg
 * @returns {Promise<import('#lib/types.js').DumpPhotoExif | null>}
 */
export async function readStoredPhotoExif(key) {
	try {
		const file = objectPath(key);
		const [gps, tags] = await Promise.all([
			exifr.gps(file).catch(() => null),
			exifr.parse(file, { pick: ['DateTimeOriginal', 'OffsetTimeOriginal', 'CreateDate'] }).catch(() => null)
		]);
		/** @type {import('#lib/types.js').DumpPhotoExif} */
		const out = {};
		if (gps && Number.isFinite(gps.latitude) && Number.isFinite(gps.longitude)) {
			out.lat = Math.round(gps.latitude * 1e6) / 1e6;
			out.lon = Math.round(gps.longitude * 1e6) / 1e6;
		}
		const taken = toIso(tags?.DateTimeOriginal ?? tags?.CreateDate, tags?.OffsetTimeOriginal);
		if (taken) out.takenAt = taken;
		return out.lat !== undefined || out.takenAt ? out : null;
	} catch {
		return null;
	}
}

/**
 * exifr revives EXIF timestamps (which carry no zone) as a Date in the
 * *process's* zone. If the camera wrote OffsetTimeOriginal, rebuild the
 * instant with the real offset; otherwise accept the guess — ordering within
 * a trip is what matters.
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
