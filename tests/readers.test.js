/**
 * Tests for src/lib/server/readers.js, one per assertion in
 * docs/models/readers.als. Run from the repo root: `pnpm test`.
 * Uses a throwaway SQLite file and the real migrations.
 */
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { beforeEach, test } from 'node:test';
import { migrate, openSqlite } from '../src/lib/server/db.js';
import * as readers from '../src/lib/server/readers.js';

/** @type {import('../src/lib/server/db.js').Db} */
let db;

beforeEach(async () => {
	db = await openSqlite(path.join(mkdtempSync(path.join(tmpdir(), 'fa-readers-')), 'test.db'));
	await migrate(db);
});

const count = async (/** @type {string} */ table, /** @type {string} */ id, extra = '') =>
	Number((await db.get(`SELECT COUNT(*) AS n FROM ${table} WHERE contact_id = ? ${extra}`, [id]))?.n);
const unused = (/** @type {string} */ id) => count('reader_links', id, 'AND used_at IS NULL AND revoked_at IS NULL');
const status = async (/** @type {string} */ id) => (await db.get('SELECT status FROM contacts WHERE id = ?', [id]))?.status;

/** A contact who has redeemed an invite, with a passkey and a feed token. */
async function activeContact(role = 'member') {
	const id = await readers.createContact(db, { displayName: 'Sam', role });
	const { code } = await readers.mintLink(db, id);
	const session = await readers.redeemLink(db, code);
	assert.ok(session);
	const now = new Date().toISOString();
	await readers.addPasskey(db, id, { credentialId: `cred-${id}`, publicKey: new Uint8Array([1, 2, 3]), counter: 0, label: 'Phone' });
	await db.run('INSERT INTO reader_tokens (id, contact_id, token_hash, created_at) VALUES (?, ?, ?, ?)', [`tok-${id}`, id, `hash-${id}`, now]);
	return { id, token: session.token };
}

async function holdsNothing(/** @type {string} */ id) {
	assert.equal(await count('reader_sessions', id), 0, 'sessions');
	assert.equal(await count('reader_passkeys', id), 0, 'passkeys');
	assert.equal(await count('reader_tokens', id), 0, 'tokens');
	assert.equal(await unused(id), 0, 'unused links');
}

test('invite: redeeming activates the contact and signs them in', async () => {
	const id = await readers.createContact(db, { displayName: 'Sam', role: 'member' });
	assert.equal(await status(id), 'invited');
	const { code } = await readers.mintLink(db, id);
	assert.deepEqual(await readers.peekLink(db, code), { displayName: 'Sam', purpose: 'signin' });
	const s = await readers.redeemLink(db, code);
	assert.equal(await status(id), 'active');
	assert.deepEqual(await readers.readerForSession(db, s?.token), { id, displayName: 'Sam', role: 'member' });
});

test('LinkUsedOnce: a link works once, also when two people open it at the same moment', async () => {
	const id = await readers.createContact(db, { displayName: 'Sam' });
	const { code } = await readers.mintLink(db, id);
	const [a, b] = await Promise.all([readers.redeemLink(db, code), readers.redeemLink(db, code)]);
	assert.equal([a, b].filter(Boolean).length, 1);
	assert.equal(await readers.redeemLink(db, code), null);
	assert.equal(await readers.peekLink(db, code), null);
	assert.equal(await count('reader_sessions', id), 1);
});

test('links: unknown and expired codes are refused', async () => {
	const id = await readers.createContact(db, { displayName: 'Sam' });
	const { code } = await readers.mintLink(db, id);
	assert.equal(await readers.redeemLink(db, 'not-a-code'), null);
	await db.run('UPDATE reader_links SET expires_at = ? WHERE contact_id = ?', ['2000-01-01T00:00:00.000Z', id]);
	assert.equal(await readers.peekLink(db, code), null);
	assert.equal(await readers.redeemLink(db, code), null);
	assert.equal(await status(id), 'invited');
});

test('OneOutstandingLink: a new link revokes the old one', async () => {
	const id = await readers.createContact(db, { displayName: 'Sam' });
	const first = await readers.mintLink(db, id);
	const second = await readers.mintLink(db, id);
	assert.equal(await unused(id), 1);
	assert.equal(await readers.redeemLink(db, first.code), null);
	assert.ok(await readers.redeemLink(db, second.code));
});

test('OwnerLinksAlwaysWipe / WipeRecoveryIsClean: recovery leaves only the new session', async () => {
	const { id, token: oldToken } = await activeContact();
	const device = await readers.mintDeviceLink(db, id); // e.g. made on a stolen phone
	const { code } = await readers.mintLink(db, id);
	const s = await readers.redeemLink(db, code);
	assert.ok(s);
	assert.equal(await readers.readerForSession(db, oldToken), null);
	assert.ok(await readers.readerForSession(db, s.token));
	assert.equal(await count('reader_sessions', id), 1);
	assert.equal(await count('reader_passkeys', id), 0);
	assert.equal(await count('reader_tokens', id), 0);
	assert.equal(await unused(id), 0);
	assert.equal(await readers.redeemLink(db, device.code), null);
});

test('WipeRecoveryIsClean: a device link made after the recovery link was issued dies on redemption', async () => {
	const { id } = await activeContact();
	const { code } = await readers.mintLink(db, id);
	const device = await readers.mintDeviceLink(db, id);
	assert.ok(await readers.redeemLink(db, code));
	assert.equal(await readers.redeemLink(db, device.code), null);
	assert.equal(await count('reader_sessions', id), 1);
});

test('NewLinkKillsDeviceLinks: minting an owner link revokes pending add-device links', async () => {
	const { id } = await activeContact();
	const device = await readers.mintDeviceLink(db, id);
	await readers.mintLink(db, id);
	assert.equal(await unused(id), 1);
	assert.equal(await readers.redeemLink(db, device.code), null);
});

test('add-device link: adds a session and wipes nothing', async () => {
	const { id, token } = await activeContact();
	const device = await readers.mintDeviceLink(db, id);
	const s = await readers.redeemLink(db, device.code);
	assert.ok(s);
	assert.ok(await readers.readerForSession(db, token));
	assert.equal(await count('reader_sessions', id), 2);
	assert.equal(await count('reader_passkeys', id), 1);
	assert.equal(await count('reader_tokens', id), 1);
});

test('DeviceLinkOnlyForActive: only a signed-in, active contact can make one', async () => {
	const invited = await readers.createContact(db, { displayName: 'New' });
	await assert.rejects(readers.mintDeviceLink(db, invited), { code: 'not_active' });
	const { id, token } = await activeContact();
	await readers.endSession(db, token);
	await assert.rejects(readers.mintDeviceLink(db, id), { code: 'not_active' });
});

test('BannedHoldsNothing: a ban revokes everything at once', async () => {
	const { id, token } = await activeContact();
	const device = await readers.mintDeviceLink(db, id);
	await readers.banContact(db, id);
	await holdsNothing(id);
	assert.equal(await readers.readerForSession(db, token), null);
	assert.equal(await readers.redeemLink(db, device.code), null);
	await assert.rejects(readers.mintLink(db, id), { code: 'banned' });
});

test('UnbanComesBackEmpty: unban restores nothing and needs a new link', async () => {
	const { id, token } = await activeContact();
	await readers.banContact(db, id);
	await readers.unbanContact(db, id);
	assert.equal(await status(id), 'invited');
	await holdsNothing(id);
	assert.equal(await readers.readerForSession(db, token), null);
	const { code } = await readers.mintLink(db, id);
	assert.ok(await readers.redeemLink(db, code));
	assert.equal(await status(id), 'active');
	await assert.rejects(readers.unbanContact(db, id), { code: 'not_banned' });
});

test('RemovedIsFinal: removal deletes the contact and everything they held', async () => {
	const { id, token } = await activeContact();
	const { code } = await readers.mintLink(db, id);
	await readers.removeContact(db, id);
	assert.equal(await status(id), undefined);
	await holdsNothing(id);
	assert.equal(await count('reader_links', id), 0);
	assert.equal(await readers.readerForSession(db, token), null);
	assert.equal(await readers.redeemLink(db, code), null);
	await assert.rejects(readers.mintLink(db, id), { code: 'not_found' });
});

test('role: a change is visible on the very next request', async () => {
	const { id, token } = await activeContact('member');
	await readers.updateContact(db, id, { displayName: 'Sam', role: 'commenter' });
	assert.equal((await readers.readerForSession(db, token))?.role, 'commenter');
});

test('contact book: lists what the owner needs to see, and rejects bad input', async () => {
	const { id } = await activeContact();
	await readers.mintLink(db, id);
	const [c] = await readers.listContacts(db);
	assert.equal(c.status, 'active');
	assert.equal(c.passkeys, 1);
	assert.equal(c.sessions, 1);
	assert.ok(c.linkExpiresAt);
	await assert.rejects(readers.createContact(db, { displayName: '  ' }), { code: 'invalid' });
	await assert.rejects(readers.createContact(db, { displayName: 'X', role: 'admin' }), { code: 'invalid' });
});

test('addPasskey: only for a signed-in, active contact, and never the same credential twice', async () => {
	const { id, token } = await activeContact();
	await assert.rejects(readers.addPasskey(db, id, { credentialId: `cred-${id}`, publicKey: new Uint8Array([9]), counter: 0 }), { code: 'invalid' });
	await readers.endSession(db, token);
	await assert.rejects(readers.addPasskey(db, id, { credentialId: 'other', publicKey: new Uint8Array([9]), counter: 0 }), { code: 'not_active' });
	const invited = await readers.createContact(db, { displayName: 'New' });
	await assert.rejects(readers.addPasskey(db, invited, { credentialId: 'x', publicKey: new Uint8Array([9]), counter: 0 }), { code: 'not_active' });
});

test('passkey sign-in: starts a session for an active contact and round-trips the key', async () => {
	const { id, token } = await activeContact();
	await readers.endSession(db, token);
	const found = await readers.passkeyForSignIn(db, `cred-${id}`);
	assert.ok(found);
	assert.deepEqual([...found.credential.publicKey], [1, 2, 3]);
	const s = await readers.signInWithPasskey(db, found.passkeyId, 5);
	assert.ok(s);
	assert.equal((await readers.readerForSession(db, s.token))?.id, id);
	assert.equal((await readers.passkeyForSignIn(db, `cred-${id}`))?.credential.counter, 5);
	assert.equal(await readers.passkeyForSignIn(db, 'unknown'), null);
});

test('passkey sign-in: refused once banned, and the passkey is gone', async () => {
	const { id } = await activeContact();
	const found = await readers.passkeyForSignIn(db, `cred-${id}`);
	assert.ok(found);
	await readers.banContact(db, id);
	assert.equal(await readers.passkeyForSignIn(db, `cred-${id}`), null);
	assert.equal(await readers.signInWithPasskey(db, found.passkeyId, 1), null);
	assert.equal(await count('reader_sessions', id), 0);
});

test('removePasskey: a reader can only remove their own', async () => {
	const a = await activeContact();
	const other = await readers.createContact(db, { displayName: 'Other' });
	const [k] = await readers.listPasskeys(db, a.id);
	await readers.removePasskey(db, other, k.id);
	assert.equal((await readers.listPasskeys(db, a.id)).length, 1);
	await readers.removePasskey(db, a.id, k.id);
	assert.equal((await readers.listPasskeys(db, a.id)).length, 0);
});

test('challenges: single use, bound to purpose and contact, and expire', async () => {
	const { id } = await activeContact();
	const one = await readers.saveChallenge(db, { purpose: 'register', contactId: id, challenge: 'abc' });
	assert.equal(await readers.takeChallenge(db, one, 'register', id), 'abc');
	assert.equal(await readers.takeChallenge(db, one, 'register', id), null);
	const wrongPurpose = await readers.saveChallenge(db, { purpose: 'register', contactId: id, challenge: 'abc' });
	assert.equal(await readers.takeChallenge(db, wrongPurpose, 'signin'), null);
	assert.equal(await readers.takeChallenge(db, wrongPurpose, 'register', id), null, 'a failed attempt uses it up');
	const wrongContact = await readers.saveChallenge(db, { purpose: 'register', contactId: id, challenge: 'abc' });
	assert.equal(await readers.takeChallenge(db, wrongContact, 'register', 'someone-else'), null);
	const stale = await readers.saveChallenge(db, { purpose: 'signin', challenge: 'abc' });
	await db.run('UPDATE reader_challenges SET expires_at = ? WHERE id = ?', ['2000-01-01T00:00:00.000Z', stale]);
	assert.equal(await readers.takeChallenge(db, stale, 'signin'), null);
	assert.equal(await readers.takeChallenge(db, undefined, 'signin'), null);
});

test('reader token: one per contact, replaced on reissue, dead on ban, role read fresh', async () => {
	const { id, token: session } = await activeContact('member');
	const first = await readers.issueReaderToken(db, id);
	assert.equal((await readers.readerForToken(db, first))?.id, id);
	const second = await readers.issueReaderToken(db, id);
	assert.equal(await readers.readerForToken(db, first), null, 'the old feed link stops working');
	assert.equal((await readers.readerForToken(db, second))?.role, 'member');
	await readers.updateContact(db, id, { displayName: 'Sam', role: 'commenter' });
	assert.equal((await readers.readerForToken(db, second))?.role, 'commenter');
	assert.ok(await readers.readerTokenCreatedAt(db, id));
	await readers.banContact(db, id);
	assert.equal(await readers.readerForToken(db, second), null);
	assert.equal(await readers.readerForToken(db, ''), null);
	assert.equal(await readers.readerForToken(db, 'nope'), null);
	// only a signed-in, active contact can make one
	const other = await activeContact('member');
	await readers.endSession(db, other.token);
	await assert.rejects(readers.issueReaderToken(db, other.id), { code: 'not_active' });
	void session;
});

test('reader token: an owner sign-in link wipes it', async () => {
	const { id } = await activeContact('member');
	const token = await readers.issueReaderToken(db, id);
	const { code } = await readers.mintLink(db, id);
	assert.ok(await readers.redeemLink(db, code));
	assert.equal(await readers.readerForToken(db, token), null);
});

test('sessions roll: renewed once a month into their year, never brought back from the dead', async () => {
	const { id, token } = await activeContact();
	assert.equal((await readers.readerSession(db, token))?.renew, false, 'a fresh session needs no renewal');
	const soon = new Date(Date.now() + 200 * 24 * 60 * 60 * 1000).toISOString();
	await db.run('UPDATE reader_sessions SET expires_at = ? WHERE contact_id = ?', [soon, id]);
	assert.equal((await readers.readerSession(db, token))?.renew, true);
	assert.equal(await readers.extendSession(db, token), true);
	assert.equal((await readers.readerSession(db, token))?.renew, false);
	await readers.banContact(db, id);
	assert.equal(await readers.extendSession(db, token), false);
	assert.equal(await readers.readerSession(db, token), null);
});

test('a reader deleting their own account is the same as being removed', async () => {
	const { id, token } = await activeContact();
	const feed = await readers.issueReaderToken(db, id);
	await readers.removeContact(db, id, { comments: 'placeholder' });
	assert.equal(await readers.readerForSession(db, token), null);
	assert.equal(await readers.readerForToken(db, feed), null);
	assert.equal(await status(id), undefined);
});
