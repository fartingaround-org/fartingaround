import { fail, redirect } from '@sveltejs/kit';
import { ADMIN_COOKIE, adminCookieOptions, passwordMatches, startAdminSession } from '#lib/server/admin-session.js';
import { config } from '#lib/server/config.js';
import { getDb } from '#lib/server/db.js';

/** @type {import('./$types').Actions} */
export const actions = {
	default: async ({ request, cookies }) => {
		const form = await request.formData();
		const password = String(form.get('password') || '');

		if (!passwordMatches(password, config.adminPassword)) {
			return fail(401, { error: 'Wrong password.' });
		}

		// The cookie holds a random session token, not the password.
		cookies.set(ADMIN_COOKIE, await startAdminSession(await getDb()), adminCookieOptions());

		throw redirect(303, '/admin');
	}
};
