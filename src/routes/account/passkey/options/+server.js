/** Step 1 of adding a passkey: options for the signed-in reader's browser. */
import { error, json } from '@sveltejs/kit';
import { getDb } from '#lib/server/db.js';
import { registrationOptions } from '#lib/server/passkeys.js';

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ locals, url, cookies }) => {
	if (!locals.reader) throw error(401, 'Sign in first.');
	return json(await registrationOptions(await getDb(), url, cookies, locals.reader), { headers: { 'cache-control': 'no-store' } });
};
