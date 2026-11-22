/**
 * A sign-in link: /in/<code>.
 *
 * GET only shows who the link is for. Using it takes a POST (the "Continue"
 * button), because mail scanners and link previews fetch GET URLs and would
 * otherwise burn a single-use link before the person ever sees it.
 */
import { fail, redirect } from '@sveltejs/kit';
import { getDb } from '#lib/server/db.js';
import { READER_COOKIE, peekLink, readerCookieOptions, redeemLink } from '#lib/server/readers.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ params, setHeaders }) => {
	// The code in the URL is a credential: keep it out of caches and out of the
	// Referer header sent to other sites. `same-origin`, not `no-referrer`:
	// under no-referrer browsers send `Origin: null` on the form POST, which
	// SvelteKit's CSRF check rejects as a cross-site submission.
	setHeaders({ 'cache-control': 'no-store', 'referrer-policy': 'same-origin', 'x-robots-tag': 'noindex' });
	return { link: await peekLink(await getDb(), params.code) };
};

/** @type {import('./$types').Actions} */
export const actions = {
	default: async ({ params, cookies, request }) => {
		const session = await redeemLink(await getDb(), params.code, { userAgent: request.headers.get('user-agent') });
		if (!session) return fail(410, { gone: true });
		cookies.set(READER_COOKIE, session.token, readerCookieOptions());
		throw redirect(303, '/account?welcome=1');
	}
};
