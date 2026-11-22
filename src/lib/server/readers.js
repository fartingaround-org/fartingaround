/**
 * Readers: the contact book, sign-in links and reader sessions.
 *
 * The rules here are the ones checked in docs/models/readers.als; each
 * function names the model event it implements, and tests/readers.test.js
 * has one test per model assertion. Change the model first, then this file.
 *
 * Every function takes the Db explicitly (routes pass `await getDb()`), so
 * the tests can run against a throwaway SQLite file.
 *
 * Access to stories is NOT decided here. It is computed per request from
 * the contact's current role and status, never stored in a session or token.
 */

import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { nowIso } from './db.js';

/** @typedef {import('./db.js').Db} Db */
/** @typedef {'member' | 'commenter'} Role */
/** @typedef {'invited' | 'active' | 'banned'} Status */
/**
 * @typedef {{
 *   id: string, displayName: string, note: string, email: string | null,
 *   role: Role, status: Status, createdAt: string, lastSeenAt: string | null,
 *   passkeys: number, sessions: number, linkExpiresAt: string | null
 * }} Contact
 * @typedef {{ id: string, displayName: string, role: Role }} Reader  A signed-in, active contact.
 */

export const ROLES = /** @type {const} */ (['member', 'commenter']);

const HOUR = 60 * 60 * 1000;
const INVITE_TTL_HOURS = 24 * 7;
const RECOVERY_TTL_HOURS = 24;
const MAX_TTL_HOURS = 24 * 30;
const DEVICE_LINK_TTL_MS = 10 * 60 * 1000;
export const SESSION_DAYS = 365;
/** Reader session cookie. Separate from the admin cookie on purpose. */
export const READER_COOKIE = 'fa_reader';
const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const MAX_PASSKEYS = 10;

/** Cookie options for a new reader session. */
export const readerCookieOptions = () =>
	/** @type {const} */ ({ path: '/', httpOnly: true, secure: true, sameSite: 'lax', maxAge: SESSION_DAYS * 24 * 60 * 60 });

/** Thrown for things the owner did wrong; `code` is safe to show. */
export class ReaderError extends Error {
	/** @param {'not_found' | 'banned' | 'not_banned' | 'not_active' | 'invalid'} code @param {string} [message] */
	constructor(code, message) {
		super(message || code);
		this.code = code;
	}
}

const hash = (/** @type {string} */ s) => createHash('sha256').update(s).digest('hex');
const secret = (bytes = 24) => randomBytes(bytes).toString('base64url');
const later = (/** @type {number} */ ms) => new Date(Date.now() + ms).toISOString();

// ── Contact book ────────────────────────────────────────────────────────────

/**
 * @param {{ displayName?: unknown, note?: unknown, email?: unknown, role?: unknown }} input
 */
function clean(input) {
	const displayName = String(input.displayName ?? '').trim();
	if (!displayName || displayName.length > 80) throw new ReaderError('invalid', 'A name of 1 to 80 characters is required.');
	const note = String(input.note ?? '').trim();
	if (note.length > 500) throw new ReaderError('invalid', 'Keep the note under 500 characters.');
	const emailRaw = String(input.email ?? '').trim();
	if (emailRaw && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailRaw)) throw new ReaderError('invalid', 'That email address does not look right.');
	const role = /** @type {Role} */ (String(input.role ?? 'commenter'));
	if (!ROLES.includes(role)) throw new ReaderError('invalid', 'Role must be member or commenter.');
	return { displayName, note, email: emailRaw || null, role };
}

/**
 * Add someone to the contact book. They are `invited` until they redeem a link.
 * @param {Db} db
 * @param {{ displayName?: unknown, note?: unknown, email?: unknown, role?: unknown }} input
 * @returns {Promise<string>} the new contact id
 */
export async function createContact(db, input) {
	const c = clean(input);
	const id = randomUUID();
	const now = nowIso();
	await db.run(
		'INSERT INTO contacts (id, display_name, note, email, role, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
		[id, c.displayName, c.note, c.email, c.role, 'invited', now, now]
	);
	return id;
}

/**
 * Edit name, note, email and role. Model event: setRole. A role change takes
 * effect on the contact's next request because nothing caches it.
 * @param {Db} db @param {string} id
 * @param {{ displayName?: unknown, note?: unknown, email?: unknown, role?: unknown }} input
 */
export async function updateContact(db, id, input) {
	const c = clean(input);
	const r = await db.run(
		'UPDATE contacts SET display_name = ?, note = ?, email = ?, role = ?, updated_at = ? WHERE id = ?',
		[c.displayName, c.note, c.email, c.role, nowIso(), id]
	);
	if (r.changes !== 1) throw new ReaderError('not_found');
}

/** @param {Db} db @returns {Promise<Contact[]>} */
export async function listContacts(db) {
	const now = nowIso();
	const rows = await db.all(
		`SELECT c.id, c.display_name, c.note, c.email, c.role, c.status, c.created_at, c.last_seen_at,
			(SELECT COUNT(*) FROM reader_passkeys p WHERE p.contact_id = c.id) AS passkeys,
			(SELECT COUNT(*) FROM reader_sessions s WHERE s.contact_id = c.id AND s.expires_at > ?) AS sessions,
			(SELECT MAX(l.expires_at) FROM reader_links l
				WHERE l.contact_id = c.id AND l.purpose = 'signin'
				AND l.used_at IS NULL AND l.revoked_at IS NULL AND l.expires_at > ?) AS link_expires_at
		 FROM contacts c
		 ORDER BY LOWER(c.display_name), c.created_at`,
		[now, now]
	);
	return rows.map((r) => ({
		id: String(r.id),
		displayName: String(r.display_name),
		note: String(r.note ?? ''),
		email: r.email ? String(r.email) : null,
		role: /** @type {Role} */ (r.role),
		status: /** @type {Status} */ (r.status),
		createdAt: String(r.created_at),
		lastSeenAt: r.last_seen_at ? String(r.last_seen_at) : null,
		passkeys: Number(r.passkeys),
		sessions: Number(r.sessions),
		linkExpiresAt: r.link_expires_at ? String(r.link_expires_at) : null
	}));
}

// ── Links ───────────────────────────────────────────────────────────────────

/**
 * Model event: mint. The owner creates a sign-in link for a contact. Any
 * older unused link of theirs (add-device links included) is revoked, so at
 * most one owner link is ever outstanding per person.
 *
 * The code is returned once and never stored; only its hash is kept.
 *
 * @param {Db} db @param {string} contactId
 * @param {{ ttlHours?: number }} [opts]  default: 7 days for a first invite, 24 hours otherwise
 * @returns {Promise<{ code: string, expiresAt: string }>}
 */
export async function mintLink(db, contactId, opts = {}) {
	return db.tx(async (t) => {
		const c = await t.get('SELECT status FROM contacts WHERE id = ?', [contactId]);
		if (!c) throw new ReaderError('not_found');
		if (c.status === 'banned') throw new ReaderError('banned', 'Unban this person before sending them a link.');
		const fallback = c.status === 'invited' ? INVITE_TTL_HOURS : RECOVERY_TTL_HOURS;
		const ttl = Math.min(Math.max(Number(opts.ttlHours) || fallback, 1), MAX_TTL_HOURS);
		const now = nowIso();
		await t.run(
			'UPDATE reader_links SET revoked_at = ? WHERE contact_id = ? AND used_at IS NULL AND revoked_at IS NULL',
			[now, contactId]
		);
		const code = secret();
		const expiresAt = later(ttl * HOUR);
		await t.run(
			'INSERT INTO reader_links (id, contact_id, purpose, code_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)',
			[randomUUID(), contactId, 'signin', hash(code), now, expiresAt]
		);
		return { code, expiresAt };
	});
}

/**
 * Model event: mintDevice. A signed-in, active reader makes a short-lived
 * link for their own second device. Revokes nothing.
 * @param {Db} db @param {string} contactId
 * @returns {Promise<{ code: string, expiresAt: string }>}
 */
export async function mintDeviceLink(db, contactId) {
	return db.tx(async (t) => {
		const now = nowIso();
		const c = await t.get('SELECT status FROM contacts WHERE id = ?', [contactId]);
		if (!c) throw new ReaderError('not_found');
		const live = await t.get('SELECT id FROM reader_sessions WHERE contact_id = ? AND expires_at > ? LIMIT 1', [contactId, now]);
		if (c.status !== 'active' || !live) throw new ReaderError('not_active');
		const code = secret();
		const expiresAt = later(DEVICE_LINK_TTL_MS);
		await t.run(
			'INSERT INTO reader_links (id, contact_id, purpose, code_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)',
			[randomUUID(), contactId, 'add_device', hash(code), now, expiresAt]
		);
		return { code, expiresAt };
	});
}

/**
 * Who a link is for, without using it. For the "Continue as …" page.
 * @param {Db} db @param {string} code
 * @returns {Promise<{ displayName: string, purpose: 'signin' | 'add_device' } | null>}
 */
export async function peekLink(db, code) {
	const row = await db.get(
		`SELECT c.display_name, l.purpose FROM reader_links l JOIN contacts c ON c.id = l.contact_id
		 WHERE l.code_hash = ? AND l.used_at IS NULL AND l.revoked_at IS NULL AND l.expires_at > ?
		 AND c.status <> 'banned'`,
		[hash(code), nowIso()]
	);
	return row ? { displayName: String(row.display_name), purpose: row.purpose } : null;
}

/** Internal: abandon a redemption and roll the claim back. */
class Abandon extends Error {}

/**
 * Model events: redeemWipe (sign-in links) and redeemKeep (add-device links).
 *
 * One transaction. The link is claimed with a single conditional UPDATE, so
 * two people opening the same link at once cannot both get in.
 *
 * A sign-in link ALWAYS wipes: every existing session, passkey, reader token
 * and pending link of that contact is revoked before the new session is
 * created. There is no "keep existing" option (decided 2026-11-11).
 *
 * @param {Db} db @param {string} code
 * @param {{ userAgent?: string | null }} [opts]
 * @returns {Promise<{ token: string, expiresAt: string, reader: Reader } | null>} null if the link is not usable
 */
export async function redeemLink(db, code, opts = {}) {
	try {
		return await db.tx(async (t) => {
			const now = nowIso();
			const codeHash = hash(code);
			const claimed = await t.run(
				'UPDATE reader_links SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?',
				[now, codeHash, now]
			);
			if (claimed.changes !== 1) throw new Abandon();
			const link = await t.get('SELECT id, contact_id, purpose FROM reader_links WHERE code_hash = ?', [codeHash]);
			const c = link && (await t.get('SELECT id, display_name, role, status FROM contacts WHERE id = ?', [link.contact_id]));
			if (!link || !c || c.status === 'banned') throw new Abandon();

			if (link.purpose === 'add_device') {
				if (c.status !== 'active') throw new Abandon();
			} else {
				await t.run('DELETE FROM reader_sessions WHERE contact_id = ?', [c.id]);
				await t.run('DELETE FROM reader_passkeys WHERE contact_id = ?', [c.id]);
				await t.run('DELETE FROM reader_tokens WHERE contact_id = ?', [c.id]);
				await t.run(
					'UPDATE reader_links SET revoked_at = ? WHERE contact_id = ? AND id <> ? AND used_at IS NULL AND revoked_at IS NULL',
					[now, c.id, link.id]
				);
				await t.run('UPDATE contacts SET status = ?, updated_at = ? WHERE id = ?', ['active', now, c.id]);
			}

			const session = await startSession(t, String(c.id), opts.userAgent, now);
			return { ...session, reader: { id: String(c.id), displayName: String(c.display_name), role: /** @type {Role} */ (c.role) } };
		});
	} catch (e) {
		if (e instanceof Abandon) return null;
		throw e;
	}
}

// ── Sessions ────────────────────────────────────────────────────────────────

/**
 * @param {Db} t @param {string} contactId @param {string | null | undefined} userAgent @param {string} now
 * @returns {Promise<{ token: string, expiresAt: string }>}
 */
async function startSession(t, contactId, userAgent, now) {
	const token = secret(32);
	const expiresAt = later(SESSION_DAYS * 24 * HOUR);
	await t.run(
		'INSERT INTO reader_sessions (id, contact_id, token_hash, created_at, expires_at, user_agent) VALUES (?, ?, ?, ?, ?, ?)',
		[randomUUID(), contactId, hash(token), now, expiresAt, (userAgent || '').slice(0, 300) || null]
	);
	return { token, expiresAt };
}

/** Renew a session once this much of its year has been used up. */
const RENEW_AFTER_MS = 30 * 24 * HOUR;

/**
 * The signed-in reader for a session cookie, or null. Only an `active`
 * contact is ever returned, and role is read fresh on every call.
 * @param {Db} db @param {string | undefined | null} token
 * @returns {Promise<Reader | null>}
 */
export async function readerForSession(db, token) {
	return (await readerSession(db, token))?.reader ?? null;
}

/**
 * Push a live session's expiry out to a full year from now. Returns false
 * if the session is gone (signed out, banned, removed) in the meantime.
 * @param {Db} db @param {string} token
 */
export async function extendSession(db, token) {
	const now = nowIso();
	const r = await db.run('UPDATE reader_sessions SET expires_at = ? WHERE token_hash = ? AND expires_at > ?', [
		later(SESSION_DAYS * 24 * HOUR), hash(token), now
	]);
	return r.changes === 1;
}

/**
 * Like readerForSession, plus `renew`: true when the session is more than
 * a month into its year and should be extended (extendSession).
 * @param {Db} db @param {string | undefined | null} token
 * @returns {Promise<{ reader: Reader, renew: boolean } | null>}
 */
export async function readerSession(db, token) {
	if (!token) return null;
	const now = nowIso();
	const row = await db.get(
		`SELECT c.id, c.display_name, c.role, c.last_seen_at, s.expires_at FROM reader_sessions s JOIN contacts c ON c.id = s.contact_id
		 WHERE s.token_hash = ? AND s.expires_at > ? AND c.status = 'active'`,
		[hash(token), now]
	);
	if (!row) return null;
	// Last-seen is for the owner's contact book; an hour's resolution is plenty.
	if (!row.last_seen_at || Date.parse(now) - Date.parse(String(row.last_seen_at)) > HOUR) {
		await db.run('UPDATE contacts SET last_seen_at = ? WHERE id = ?', [now, row.id]);
	}
	const left = Date.parse(String(row.expires_at)) - Date.parse(now);
	return {
		reader: { id: String(row.id), displayName: String(row.display_name), role: /** @type {Role} */ (row.role) },
		renew: left < SESSION_DAYS * 24 * HOUR - RENEW_AFTER_MS
	};
}

/** Model event: signOut. @param {Db} db @param {string | undefined | null} token */
export async function endSession(db, token) {
	if (token) await db.run('DELETE FROM reader_sessions WHERE token_hash = ?', [hash(token)]);
}

// ── Ban, unban, remove ──────────────────────────────────────────────────────

/** Everything a contact holds, gone in one step. @param {Db} t @param {string} id @param {string} now */
async function revokeAll(t, id, now) {
	await t.run('DELETE FROM reader_sessions WHERE contact_id = ?', [id]);
	await t.run('DELETE FROM reader_passkeys WHERE contact_id = ?', [id]);
	await t.run('DELETE FROM reader_tokens WHERE contact_id = ?', [id]);
	await t.run('DELETE FROM reader_challenges WHERE contact_id = ?', [id]);
	await t.run('UPDATE reader_links SET revoked_at = ? WHERE contact_id = ? AND used_at IS NULL AND revoked_at IS NULL', [now, id]);
}

/**
 * Model event: ban. Their comments stay as they are unless `hideComments`
 * is set; hiding is reversible from the comment list, and an unban never
 * shows them again by itself.
 * @param {Db} db @param {string} id @param {{ hideComments?: boolean }} [opts]
 */
export async function banContact(db, id, opts = {}) {
	await db.tx(async (t) => {
		const now = nowIso();
		const r = await t.run('UPDATE contacts SET status = ?, updated_at = ? WHERE id = ?', ['banned', now, id]);
		if (r.changes !== 1) throw new ReaderError('not_found');
		await revokeAll(t, id, now);
		if (opts.hideComments) await t.run(`UPDATE comments SET status = 'hidden' WHERE contact_id = ? AND status = 'visible'`, [id]);
	});
}

/**
 * Model event: unban. Only lifts the block: nothing the ban destroyed comes
 * back, and the contact cannot get in until the owner mints a new link.
 * @param {Db} db @param {string} id
 */
export async function unbanContact(db, id) {
	const r = await db.run('UPDATE contacts SET status = ?, updated_at = ? WHERE id = ? AND status = ?', ['invited', nowIso(), id, 'banned']);
	if (r.changes !== 1) throw new ReaderError('not_banned');
}

/**
 * Model event: remove. Final: the row and everything hanging off it is deleted.
 *
 * Their comments: 'placeholder' (default) erases the words and leaves
 * "[deleted]" markers; 'delete' also removes the rows, except where other
 * people replied, which keep a marker so those replies survive.
 * @param {Db} db @param {string} id @param {{ comments?: 'placeholder' | 'delete' }} [opts]
 */
export async function removeContact(db, id, opts = {}) {
	await db.tx(async (t) => {
		const now = nowIso();
		if (opts.comments === 'delete') {
			await t.run(
				'DELETE FROM comments WHERE contact_id = ? AND id NOT IN (SELECT parent_id FROM comments WHERE parent_id IS NOT NULL)',
				[id]
			);
		}
		await t.run(`UPDATE comments SET body = '', status = 'deleted', edited_at = ? WHERE contact_id = ?`, [now, id]);
		await revokeAll(t, id, now);
		await t.run('DELETE FROM reader_links WHERE contact_id = ?', [id]);
		const r = await t.run('DELETE FROM contacts WHERE id = ?', [id]);
		if (r.changes !== 1) throw new ReaderError('not_found');
	});
}

// ── Passkeys ────────────────────────────────────────────────────────────────
// The WebAuthn ceremony itself is in passkeys.js; this is the storage and the
// model's rules about who may add one and who may sign in with one.

/**
 * @typedef {{ id: string, label: string, createdAt: string, lastUsedAt: string | null }} PasskeyInfo
 * @typedef {{ id: string, publicKey: Uint8Array<ArrayBuffer>, counter: number, transports?: string[] }} StoredCredential
 */

/** @param {Db} db @param {string} contactId @returns {Promise<PasskeyInfo[]>} */
export async function listPasskeys(db, contactId) {
	const rows = await db.all('SELECT id, label, created_at, last_used_at FROM reader_passkeys WHERE contact_id = ? ORDER BY created_at', [contactId]);
	return rows.map((r) => ({ id: String(r.id), label: String(r.label || ''), createdAt: String(r.created_at), lastUsedAt: r.last_used_at ? String(r.last_used_at) : null }));
}

/** Credential ids a contact already has, so the browser will not register one twice. @param {Db} db @param {string} contactId */
export async function passkeyDescriptors(db, contactId) {
	const rows = await db.all('SELECT credential_id, transports FROM reader_passkeys WHERE contact_id = ?', [contactId]);
	return rows.map((r) => ({ id: String(r.credential_id), transports: r.transports ? /** @type {string[]} */ (JSON.parse(r.transports)) : undefined }));
}

/**
 * Model event: addPasskey. Only for an active contact who is signed in
 * right now (the route passes the signed-in reader's own id).
 * @param {Db} db @param {string} contactId
 * @param {{ credentialId: string, publicKey: Uint8Array, counter: number, transports?: string[], label?: string }} cred
 * @returns {Promise<string>} the passkey row id
 */
export async function addPasskey(db, contactId, cred) {
	return db.tx(async (t) => {
		const now = nowIso();
		const c = await t.get('SELECT status FROM contacts WHERE id = ?', [contactId]);
		const live = await t.get('SELECT id FROM reader_sessions WHERE contact_id = ? AND expires_at > ? LIMIT 1', [contactId, now]);
		if (!c || c.status !== 'active' || !live) throw new ReaderError('not_active');
		const n = await t.get('SELECT COUNT(*) AS n FROM reader_passkeys WHERE contact_id = ?', [contactId]);
		if (Number(n?.n) >= MAX_PASSKEYS) throw new ReaderError('invalid', `You can have up to ${MAX_PASSKEYS} passkeys. Remove one first.`);
		const dup = await t.get('SELECT id FROM reader_passkeys WHERE credential_id = ?', [cred.credentialId]);
		if (dup) throw new ReaderError('invalid', 'That passkey is already set up here.');
		const id = randomUUID();
		await t.run(
			'INSERT INTO reader_passkeys (id, contact_id, credential_id, public_key, sign_count, transports, label, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
			[
				id, contactId, cred.credentialId, Buffer.from(cred.publicKey).toString('base64url'), cred.counter || 0,
				cred.transports?.length ? JSON.stringify(cred.transports) : null, String(cred.label || '').trim().slice(0, 60), now
			]
		);
		return id;
	});
}

/** A reader removes one of their own passkeys. @param {Db} db @param {string} contactId @param {string} passkeyId */
export async function removePasskey(db, contactId, passkeyId) {
	await db.run('DELETE FROM reader_passkeys WHERE id = ? AND contact_id = ?', [passkeyId, contactId]);
}

/**
 * The stored credential for a sign-in attempt, if it belongs to an active contact.
 * @param {Db} db @param {string} credentialId
 * @returns {Promise<{ passkeyId: string, credential: StoredCredential } | null>}
 */
export async function passkeyForSignIn(db, credentialId) {
	const r = await db.get(
		`SELECT p.id, p.credential_id, p.public_key, p.sign_count, p.transports FROM reader_passkeys p
		 JOIN contacts c ON c.id = p.contact_id WHERE p.credential_id = ? AND c.status = 'active'`,
		[credentialId]
	);
	if (!r) return null;
	return {
		passkeyId: String(r.id),
		credential: {
			id: String(r.credential_id),
			publicKey: new Uint8Array(Buffer.from(String(r.public_key), 'base64url')),
			counter: Number(r.sign_count),
			transports: r.transports ? JSON.parse(r.transports) : undefined
		}
	};
}

/**
 * Model event: passkeySignIn. Call only after the WebAuthn response has
 * been verified. Re-checks, inside the transaction, that the passkey still
 * exists and its contact is still active.
 * @param {Db} db @param {string} passkeyId @param {number} newCounter
 * @param {{ userAgent?: string | null }} [opts]
 * @returns {Promise<{ token: string, expiresAt: string, reader: Reader } | null>}
 */
export async function signInWithPasskey(db, passkeyId, newCounter, opts = {}) {
	return db.tx(async (t) => {
		const now = nowIso();
		const c = await t.get(
			`SELECT c.id, c.display_name, c.role FROM reader_passkeys p JOIN contacts c ON c.id = p.contact_id
			 WHERE p.id = ? AND c.status = 'active'`,
			[passkeyId]
		);
		if (!c) return null;
		await t.run('UPDATE reader_passkeys SET sign_count = ?, last_used_at = ? WHERE id = ?', [newCounter || 0, now, passkeyId]);
		const session = await startSession(t, String(c.id), opts.userAgent, now);
		return { ...session, reader: { id: String(c.id), displayName: String(c.display_name), role: /** @type {Role} */ (c.role) } };
	});
}

// ── WebAuthn challenges ─────────────────────────────────────────────────────

/**
 * Remember a challenge for a few minutes. Returns the id to hand the browser.
 * @param {Db} db @param {{ purpose: 'register' | 'signin', contactId?: string | null, challenge: string }} c
 */
export async function saveChallenge(db, c) {
	const now = nowIso();
	await db.run('DELETE FROM reader_challenges WHERE expires_at < ?', [now]);
	const id = secret(18);
	await db.run('INSERT INTO reader_challenges (id, purpose, contact_id, challenge, expires_at) VALUES (?, ?, ?, ?, ?)', [
		id, c.purpose, c.contactId ?? null, c.challenge, later(CHALLENGE_TTL_MS)
	]);
	return id;
}

/**
 * Use a challenge up. It is deleted whether or not it matches, so each one
 * gets exactly one attempt.
 * @param {Db} db @param {string | null | undefined} id
 * @param {'register' | 'signin'} purpose @param {string | null} [contactId]
 * @returns {Promise<string | null>}
 */
export async function takeChallenge(db, id, purpose, contactId = null) {
	if (!id) return null;
	return db.tx(async (t) => {
		const row = await t.get('SELECT purpose, contact_id, challenge, expires_at FROM reader_challenges WHERE id = ?', [id]);
		const del = await t.run('DELETE FROM reader_challenges WHERE id = ?', [id]);
		if (!row || del.changes !== 1) return null;
		if (row.purpose !== purpose || (row.contact_id ?? null) !== contactId || String(row.expires_at) <= nowIso()) return null;
		return String(row.challenge);
	});
}

// ── Reader tokens ───────────────────────────────────────────────────────────
// One per contact, for the private feed link and for media inside that feed.
// It carries no rights of its own: every request looks the contact up again,
// so a ban, removal or role change applies to the token at once.

/**
 * Model event: issueToken. For a signed-in, active contact. Replaces any
 * token they had, so the old feed link stops working. Shown once: only the
 * hash is stored.
 * @param {Db} db @param {string} contactId @returns {Promise<string>}
 */
export async function issueReaderToken(db, contactId) {
	return db.tx(async (t) => {
		const now = nowIso();
		const c = await t.get('SELECT status FROM contacts WHERE id = ?', [contactId]);
		const live = await t.get('SELECT id FROM reader_sessions WHERE contact_id = ? AND expires_at > ? LIMIT 1', [contactId, now]);
		if (!c || c.status !== 'active' || !live) throw new ReaderError('not_active');
		await t.run('DELETE FROM reader_tokens WHERE contact_id = ?', [contactId]);
		const token = secret(32);
		await t.run('INSERT INTO reader_tokens (id, contact_id, token_hash, created_at) VALUES (?, ?, ?, ?)', [randomUUID(), contactId, hash(token), now]);
		return token;
	});
}

/** When the contact's current token was made, or null. @param {Db} db @param {string} contactId */
export async function readerTokenCreatedAt(db, contactId) {
	const r = await db.get('SELECT created_at FROM reader_tokens WHERE contact_id = ?', [contactId]);
	return r ? String(r.created_at) : null;
}

/**
 * The active contact a token belongs to, or null. Role is read fresh.
 * @param {Db} db @param {string | null | undefined} token @returns {Promise<Reader | null>}
 */
export async function readerForToken(db, token) {
	if (!token || token.length > 200) return null;
	const row = await db.get(
		`SELECT c.id, c.display_name, c.role FROM reader_tokens t JOIN contacts c ON c.id = t.contact_id
		 WHERE t.token_hash = ? AND c.status = 'active'`,
		[hash(token)]
	);
	return row ? { id: String(row.id), displayName: String(row.display_name), role: /** @type {Role} */ (row.role) } : null;
}
