/**
 * Block helpers safe to import from the browser.
 * (Anything under #lib/server is server-only.)
 */

/**
 * The image ids of every photo-dump photo in a story: the photos a comment
 * may point at.
 * @param {{ blocks?: any[] } | null | undefined} story
 * @returns {Set<string>}
 */
export function dumpPhotoIds(story) {
	const ids = new Set();
	for (const b of story?.blocks || []) {
		if (b?.type !== 'dump') continue;
		for (const p of b.photos || []) if (p?.imageId) ids.add(String(p.imageId));
	}
	return ids;
}

/** Generate a stable block id. */
export function blockId() {
	return Math.random().toString(36).slice(2, 10);
}

/**
 * Where a photo in this story was taken, for the editors' "use the photo's
 * location": the first photo that knows. A photo block and a dump photo both
 * keep what their file said in a private `exif`; a dump photo may also have a
 * location the author already chose to show (`coords`).
 * @param {{ blocks?: any[] }} story
 * @returns {{ lat: number, lon: number } | null}
 */
export function photoLocation(story) {
	const ok = (/** @type {any} */ c) => (c && Number.isFinite(c.lat) && Number.isFinite(c.lon) ? { lat: c.lat, lon: c.lon } : null);
	for (const b of story.blocks || []) {
		if (b?.type === 'photo') {
			const at = ok(b.exif);
			if (at) return at;
		} else if (b?.type === 'dump') {
			for (const p of b.photos || []) {
				const at = ok(p?.coords) ?? ok(p?.exif);
				if (at) return at;
			}
		}
	}
	return null;
}
