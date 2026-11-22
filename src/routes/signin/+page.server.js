import { redirect } from '@sveltejs/kit';

/** @type {import('./$types').PageServerLoad} */
export const load = ({ locals, setHeaders }) => {
	setHeaders({ 'cache-control': 'no-store' });
	if (locals.reader) throw redirect(303, '/account');
	return {};
};
