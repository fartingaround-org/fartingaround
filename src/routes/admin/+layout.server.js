import { redirect } from '@sveltejs/kit';

/** @type {import('./$types').LayoutServerLoad} */
export const load = ({ locals, url }) => {
	if (!locals.isAdmin) {
		throw redirect(303, `/login?next=${encodeURIComponent(url.pathname)}`);
	}
	return {};
};
