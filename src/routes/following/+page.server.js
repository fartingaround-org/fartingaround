import { error } from '@sveltejs/kit';
import { getFollowing, parseOpml } from '#lib/server/following.js';
import { followingPublic } from '#lib/server/settings.js';

/** @type {import('./$types').PageServerLoad} */
export async function load({ locals }) {
	// The owner can keep who they follow to themselves.
	if (!locals.isAdmin && !(await followingPublic())) throw error(404, 'Not found');
	const f = await getFollowing();
	return { feeds: f ? parseOpml(f.opml) : [], updatedAt: f?.updatedAt ?? null };
}
