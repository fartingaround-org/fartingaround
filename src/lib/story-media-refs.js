/**
 * Every stored file a story refers to. Pure, so both the server (deleting a
 * story's files, deciding which files are private) and the tests can use it.
 *
 * @param {import('#lib/types.js').Story} story
 * @returns {{ imageIds: string[], objectKeys: string[], objectPrefixes: string[] }}
 */
export function storyMediaRefs(story) {
	const ids = new Set();
	const keys = new Set();
	const prefixes = new Set();
	if (story.coverImageId) ids.add(story.coverImageId);
	for (const b of story.blocks || []) {
		switch (b.type) {
			case 'photo':
				if (b.imageId) ids.add(b.imageId);
				break;
			case 'diptych':
				if (b.left?.imageId) ids.add(b.left.imageId);
				if (b.right?.imageId) ids.add(b.right.imageId);
				break;
			case 'gallery':
				for (const it of b.items || []) {
					for (const id of [it.imageId, it.annotationId, it.overlayId, it.starlessId]) if (id) ids.add(id);
					if (it.r2?.base) prefixes.add(it.r2.base);
				}
				break;
			case 'dump':
				for (const p of b.photos || []) {
					if (p.imageId) ids.add(p.imageId);
					if (p.audio?.key) keys.add(p.audio.key);
				}
				break;
			case 'audio':
				if (b.r2Key) keys.add(b.r2Key);
				break;
			case 'video':
				if (b.r2Key) keys.add(b.r2Key);
				if (b.posterImageId) ids.add(b.posterImageId);
				break;
		}
	}
	return { imageIds: [...ids], objectKeys: [...keys], objectPrefixes: [...prefixes] };
}
