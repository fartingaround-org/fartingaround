/** Step 2 of passkey sign-in: verify, then start a reader session. */
import { json } from '@sveltejs/kit';
import { getDb } from '#lib/server/db.js';
import { verifyAuthentication } from '#lib/server/passkeys.js';
import { READER_COOKIE, readerCookieOptions } from '#lib/server/readers.js';

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ url, cookies, request }) => {
	const body = await request.json().catch(() => ({}));
	const session = await verifyAuthentication(await getDb(), url, cookies, body, request.headers.get('user-agent'));
	// One message for every failure: don't reveal whether a passkey is known here.
	if (!session) return json({ ok: false, error: "That passkey didn't work here. If you were sent a link, use that instead." }, { status: 401 });
	cookies.set(READER_COOKIE, session.token, readerCookieOptions());
	return json({ ok: true });
};
