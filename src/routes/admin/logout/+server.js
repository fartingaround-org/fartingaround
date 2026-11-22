import { redirect } from '@sveltejs/kit';
import { ADMIN_COOKIE, endAdminSession } from '#lib/server/admin-session.js';
import { getDb } from '#lib/server/db.js';

export const POST = async ({ cookies }) => {
	await endAdminSession(await getDb(), cookies.get(ADMIN_COOKIE));
	cookies.delete(ADMIN_COOKIE, { path: '/' });
	throw redirect(303, '/');
};
