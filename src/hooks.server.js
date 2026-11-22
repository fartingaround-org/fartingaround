/**
 * Server hooks.
 *
 *  - init: open the database and apply migrations before the first request,
 *    so a schema problem fails the start instead of the first visitor.
 *  - handle: mark the request as admin if a valid `admin` cookie is present,
 *    and attach the signed-in reader (an invited contact) if there is one.
 */
import { config } from '#lib/server/config.js';
import { getDb } from '#lib/server/db.js';
import { ADMIN_COOKIE, isAdminSession } from '#lib/server/admin-session.js';
import { READER_COOKIE, extendSession, readerCookieOptions, readerSession } from '#lib/server/readers.js';
import { mkdir } from 'node:fs/promises';

/** @type {import('@sveltejs/kit/hooks').ServerInit} */
export const init = async () => {
	if (!config.adminPassword || config.adminPassword === 'change-me') {
		throw new Error('ADMIN_PASSWORD is not set (or is still "change-me"). Set it in .env before starting.');
	}
	await mkdir(config.objectsDir, { recursive: true });
	await getDb();
	console.log(`[startup] data in ${config.dataDir}; database: ${config.databaseUrl ? 'postgres' : 'sqlite'}`);
};

/** @type {import('@sveltejs/kit/hooks').Handle} */
export const handle = async ({ event, resolve }) => {
	// The admin cookie is a session token (see admin-session.js), not the password.
	const adminCookie = event.cookies.get(ADMIN_COOKIE);
	event.locals.isAdmin = false;
	if (adminCookie) {
		try {
			event.locals.isAdmin = await isAdminSession(await getDb(), adminCookie);
		} catch (e) {
			console.error('[admin] session lookup failed', e);
		}
	}

	// Looked up on every request, so a ban, removal or role change applies at
	// once. Only requests that carry the cookie pay for the query.
	const readerCookie = event.cookies.get(READER_COOKIE);
	event.locals.reader = null;
	if (readerCookie) {
		try {
			const db = await getDb();
			const found = await readerSession(db, readerCookie);
			event.locals.reader = found?.reader ?? null;
			// Rolling: someone who keeps visiting stays signed in.
			if (found?.renew && (await extendSession(db, readerCookie))) {
				event.cookies.set(READER_COOKIE, readerCookie, readerCookieOptions());
			}
		} catch (e) {
			console.error('[readers] session lookup failed', e);
		}
	}
	return resolve(event);
};
