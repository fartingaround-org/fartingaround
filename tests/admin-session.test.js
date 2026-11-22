/** The owner's sign-in session (src/lib/server/admin-session.js). */
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { beforeEach, test } from 'node:test';
import { migrate, openSqlite } from '../src/lib/server/db.js';
import * as admin from '../src/lib/server/admin-session.js';

/** @type {import('../src/lib/server/db.js').Db} */
let db;
beforeEach(async () => {
	db = await openSqlite(path.join(mkdtempSync(path.join(tmpdir(), 'fa-admin-')), 'test.db'));
	await migrate(db);
});

test('the cookie is a session token, never the password', async () => {
	const password = 'correct horse battery staple';
	assert.equal(admin.passwordMatches(password, password), true);
	assert.equal(admin.passwordMatches('wrong', password), false);
	assert.equal(admin.passwordMatches('', ''), false, 'an unset password never matches');
	const token = await admin.startAdminSession(db);
	assert.notEqual(token, password);
	assert.equal(await admin.isAdminSession(db, token), true);
	assert.equal(await admin.isAdminSession(db, password), false, 'the old password-as-cookie no longer works');
	assert.equal(await admin.isAdminSession(db, undefined), false);
	assert.equal(await admin.isAdminSession(db, 'x'.repeat(500)), false);
});

test('logging out ends that session only, and sessions expire', async () => {
	const a = await admin.startAdminSession(db);
	const b = await admin.startAdminSession(db);
	await admin.endAdminSession(db, a);
	assert.equal(await admin.isAdminSession(db, a), false);
	assert.equal(await admin.isAdminSession(db, b), true);
	await db.run('UPDATE admin_sessions SET expires_at = ?', ['2000-01-01T00:00:00.000Z']);
	assert.equal(await admin.isAdminSession(db, b), false);
});
