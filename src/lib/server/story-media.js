/**
 * Every stored file a story refers to, and how to remove them all. Used by
 * the admin's Delete action and DELETE /api/v1/stories/:slug. Best-effort:
 * a missing object is not an error.
 */
import { deleteImage } from './images.js';
import { deleteObject, deletePrefix } from './objects.js';
import { storyMediaRefs } from '../story-media-refs.js';

export { storyMediaRefs };

/** @param {import('#lib/types.js').Story} story */
export async function deleteStoryMedia(story) {
	const refs = storyMediaRefs(story);
	await Promise.allSettled([
		...refs.imageIds.map((id) => deleteImage(id)),
		...refs.objectKeys.map((k) => deleteObject(k)),
		...refs.objectPrefixes.map((p) => deletePrefix(p))
	]);
}
