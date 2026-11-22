/** Step 2 of adding a passkey: verify what the browser made and store it. */
import { error, json } from '@sveltejs/kit';
import { getDb } from '#lib/server/db.js';
import { verifyRegistration } from '#lib/server/passkeys.js';
import { ReaderError } from '#lib/server/readers.js';

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ locals, url, cookies, request }) => {
	if (!locals.reader) throw error(401, 'Sign in first.');
	const body = await request.json().catch(() => ({}));
	try {
		await verifyRegistration(await getDb(), url, cookies, locals.reader, body);
	} catch (e) {
		if (e instanceof ReaderError) {
			return json({ ok: false, error: e.code === 'not_active' ? 'You are no longer signed in.' : e.message }, { status: 400 });
		}
		throw e;
	}
	return json({ ok: true });
};
