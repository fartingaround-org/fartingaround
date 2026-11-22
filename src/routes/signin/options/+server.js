/** Step 1 of passkey sign-in. */
import { json } from '@sveltejs/kit';
import { getDb } from '#lib/server/db.js';
import { authenticationOptions } from '#lib/server/passkeys.js';

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ url, cookies }) => {
	return json(await authenticationOptions(await getDb(), url, cookies), { headers: { 'cache-control': 'no-store' } });
};
