/**
 * The owner's sign-in. The password is checked once at /login; after that
 * the browser holds a random session token, never the password.
 * Functions take the Db so tests can run on SQLite.
 */
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { nowIso } from './db.js';

/** @typedef {import('./db.js').Db} Db */

export const ADMIN_COOKIE = 'admin';
export const ADMIN_SESSION_DAYS = 30;
export const adminCookieOptions = () =>
	/** @type {const} */ ({ path: '/', httpOnly: true, secure: true, sameSite: 'lax', maxAge: ADMIN_SESSION_DAYS * 24 * 60 * 60 });

const hash = (/** @type {string} */ s) => createHash('sha256').update(s).digest();

/** Compare without leaking, through timing, how much of the password matched. @param {string} given @param {string} expected */
export function passwordMatches(given, expected) {
	return Boolean(expected) && timingSafeEqual(hash(given), hash(expected));
}

/** @param {Db} db @returns {Promise<string>} the token for the cookie */
export async function startAdminSession(db) {
	const now = nowIso();
	await db.run('DELETE FROM admin_sessions WHERE expires_at < ?', [now]);
	const token = randomBytes(32).toString('base64url');
	await db.run('INSERT INTO admin_sessions (token_hash, created_at, expires_at) VALUES (?, ?, ?)', [
		hash(token).toString('hex'),
		now,
		new Date(Date.now() + ADMIN_SESSION_DAYS * 24 * 60 * 60 * 1000).toISOString()
	]);
	return token;
}

/** @param {Db} db @param {string | undefined | null} token */
export async function isAdminSession(db, token) {
	if (!token || token.length > 200) return false;
	return Boolean(await db.get('SELECT 1 AS ok FROM admin_sessions WHERE token_hash = ? AND expires_at > ?', [hash(token).toString('hex'), nowIso()]));
}

/** @param {Db} db @param {string | undefined | null} token */
export async function endAdminSession(db, token) {
	if (token) await db.run('DELETE FROM admin_sessions WHERE token_hash = ?', [hash(token).toString('hex')]);
}
