import { followingPublic, siteIdentity } from '#lib/server/settings.js';

/** @type {import('./$types').LayoutServerLoad} */
export const load = async ({ locals, url }) => {
	return {
		// Whose site this is: its name, its words, its links. Every page uses it.
		site: await siteIdentity(url.host),
		isAdmin: locals.isAdmin,
		reader: locals.reader ? { displayName: locals.reader.displayName } : null,
		// The owner always sees their own list; visitors only if it is public.
		showFollowing: locals.isAdmin || (await followingPublic())
	};
};
