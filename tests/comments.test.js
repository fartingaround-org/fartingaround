/** Comments (src/lib/server/comments.js): the plain requirements from the spec. */
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { beforeEach, test } from 'node:test';
import { migrate, openSqlite } from '../src/lib/server/db.js';
import * as comments from '../src/lib/server/comments.js';
import * as readers from '../src/lib/server/readers.js';

/** @type {import('../src/lib/server/db.js').Db} */
let db;
/** @type {string} */ let sam;
/** @type {string} */ let ana;
const owner = /** @type {const} */ ({ owner: true });
const S = 'a-story';

beforeEach(async () => {
	db = await openSqlite(path.join(mkdtempSync(path.join(tmpdir(), 'fa-comments-')), 'test.db'));
	await migrate(db);
	await db.run('INSERT INTO stories (slug, date, draft, updated_at, doc) VALUES (?, ?, 0, ?, ?)', [S, '2026-01-01', 'x', '{}']);
	await db.run('INSERT INTO stories (slug, date, draft, updated_at, doc) VALUES (?, ?, 0, ?, ?)', ['other', '2026-01-01', 'x', '{}']);
	sam = await readers.createContact(db, { displayName: 'Sam', role: 'member' });
	ana = await readers.createContact(db, { displayName: 'Ana' });
});

const say = (/** @type {comments.Author} */ author, /** @type {string} */ body, /** @type {string | null} */ parentId = null) =>
	comments.addComment(db, { storySlug: S, author, body, parentId });
const view = (/** @type {{ isAdmin?: boolean, readerId?: string | null }} */ viewer = {}) => comments.listComments(db, S, viewer);

test('posting and one level of threading', async () => {
	const top = await say({ contactId: sam }, 'First!');
	const reply = await say(owner, 'Thanks, Sam.', top);
	const [c] = await view();
	assert.equal(c.name, 'Sam');
	assert.equal(c.body, 'First!');
	assert.equal(c.replies.length, 1);
	assert.equal(c.replies[0].name, 'Author');
	assert.equal(c.replies[0].byOwner, true);
	await assert.rejects(say({ contactId: ana }, 'nested', reply), { code: 'bad_parent' }, 'no reply to a reply');
	await assert.rejects(comments.addComment(db, { storySlug: 'other', author: owner, body: 'x', parentId: top }), { code: 'bad_parent' });
	await assert.rejects(say(owner, 'x', 'missing'), { code: 'bad_parent' });
});

test('limits: empty, too long, too many links, too fast', async () => {
	await assert.rejects(say({ contactId: sam }, '   '), { code: 'empty' });
	await assert.rejects(say({ contactId: sam }, 'x'.repeat(comments.MAX_BODY + 1)), { code: 'too_long' });
	await assert.rejects(say({ contactId: sam }, 'http://a http://b https://c http://d http://e http://f'), { code: 'too_many_links' });
	for (let i = 0; i < comments.PER_MINUTE; i++) await say({ contactId: sam }, `n${i}`);
	await assert.rejects(say({ contactId: sam }, 'one more'), { code: 'too_fast' });
	await say({ contactId: ana }, 'someone else is fine');
	await say(owner, 'the owner is not limited');
});

test('a contact may edit and delete only their own comments', async () => {
	const id = await say({ contactId: sam }, 'mine');
	await assert.rejects(comments.editComment(db, id, { contactId: ana }, 'hijack'), { code: 'not_found' });
	await assert.rejects(comments.deleteOwnComment(db, id, { contactId: ana }), { code: 'not_found' });
	await assert.rejects(comments.editComment(db, id, owner, 'owner edits a reader'), { code: 'not_found' });
	await comments.editComment(db, id, { contactId: sam }, 'mine, edited');
	const [c] = await view({ readerId: sam });
	assert.equal(c.body, 'mine, edited');
	assert.ok(c.editedAt);
	assert.equal(c.mine, true);
	assert.equal((await view({ readerId: ana }))[0].mine, false);
	await comments.deleteOwnComment(db, id, { contactId: sam });
	assert.deepEqual(await view(), [], 'gone from public view');
	assert.equal((await db.get('SELECT body FROM comments WHERE id = ?', [id]))?.body, '', 'the words are erased');
});

test('a deleted comment leaves a placeholder only while it has replies', async () => {
	const top = await say({ contactId: sam }, 'to be deleted');
	await say({ contactId: ana }, 'a reply', top);
	await comments.deleteOwnComment(db, top, { contactId: sam });
	const [c] = await view();
	assert.equal(c.deleted, true);
	assert.equal(c.body, '');
	assert.equal(c.replies.length, 1);
});

test('hidden by the owner: invisible to others, "held" to its author, visible to the owner', async () => {
	const top = await say({ contactId: sam }, 'questionable');
	await say({ contactId: ana }, 'reply under it', top);
	await comments.moderateComment(db, top, 'hide');
	assert.deepEqual(await view(), []);
	assert.deepEqual(await view({ readerId: ana }), [], 'a reply under a hidden comment goes with it');
	const [mine] = await view({ readerId: sam });
	assert.equal(mine.held, true);
	assert.equal(mine.replies.length, 0);
	const [admin] = await view({ isAdmin: true });
	assert.equal(admin.hidden, true);
	assert.equal(admin.replies.length, 1);
	await comments.moderateComment(db, top, 'show');
	assert.equal((await view()).length, 1);
	await comments.moderateComment(db, top, 'delete');
	assert.deepEqual(await view({ isAdmin: true }), [], 'owner delete removes it and its replies');
});

test('ban: comments stay unless the owner ticks hide; unban never republishes', async () => {
	await say({ contactId: sam }, 'from sam');
	await say({ contactId: ana }, 'from ana');
	await readers.banContact(db, sam);
	assert.equal((await view()).length, 2, 'a plain ban leaves comments alone');
	await readers.banContact(db, ana, { hideComments: true });
	assert.deepEqual((await view()).map((c) => c.name), ['Sam']);
	await readers.unbanContact(db, ana);
	assert.deepEqual((await view()).map((c) => c.name), ['Sam'], 'still hidden after unban');
});

test('remove: placeholder keeps threads, delete removes what it can', async () => {
	const a = await say({ contactId: sam }, 'has a reply');
	await say({ contactId: ana }, 'reply from ana', a);
	await say({ contactId: sam }, 'no replies');
	await readers.removeContact(db, sam, { comments: 'delete' });
	const after = await view();
	assert.equal(after.length, 1, 'the comment without replies is gone entirely');
	assert.equal(after[0].deleted, true);
	assert.equal(after[0].replies[0].name, 'Ana');
	assert.equal(Number((await db.get("SELECT COUNT(*) AS n FROM comments WHERE body <> '' AND contact_id IS NULL"))?.n), 0, 'none of their words remain');

	await say({ contactId: ana }, 'ana top');
	await readers.removeContact(db, ana);
	const rows = await db.all('SELECT body, status, contact_id FROM comments');
	assert.ok(rows.every((r) => r.body === '' && r.status === 'deleted' && r.contact_id === null));
});

test('deleting a story deletes its comments', async () => {
	await say({ contactId: sam }, 'hello');
	await db.run('DELETE FROM stories WHERE slug = ?', [S]);
	assert.equal(Number((await db.get('SELECT COUNT(*) AS n FROM comments'))?.n), 0);
});

test('comments are listed in the order they were posted, even within one millisecond', async () => {
	// Fast enough that several land on the same clock tick. Without a rule for
	// ties the list fell back to the comments' random ids.
	const bodies = Array.from({ length: 40 }, (_, i) => `comment ${i}`);
	for (const b of bodies) await say(owner, b);
	assert.deepEqual((await view()).map((c) => c.body), bodies);
	const stamps = (await db.all('SELECT created_at FROM comments ORDER BY created_at')).map((r) => String(r.created_at));
	assert.equal(new Set(stamps).size, stamps.length, 'no two comments on a story share a timestamp');
});

test('a comment can point at a photo; replies follow it; the pointer shows only while the photo is in the story', async () => {
	const top = await comments.addComment(db, { storySlug: S, author: { contactId: sam }, body: "That's me", photoId: 'img-1' });
	// A reply takes its parent's photo, whatever it was sent with.
	await comments.addComment(db, { storySlug: S, author: owner, body: 'It is!', parentId: top, photoId: 'img-other' });
	await say({ contactId: ana }, 'An ordinary comment');

	const stored = await view();
	assert.equal(stored[0].photoId, 'img-1');
	assert.equal(stored[0].replies[0].photoId, 'img-1');
	assert.equal(stored[1].photoId, null);

	// Photo still in the story: pointer shown.
	const present = await comments.listComments(db, S, { photoIds: new Set(['img-1', 'img-2']) });
	assert.equal(present[0].photoId, 'img-1');
	// Photo removed from the story: the words stay, as an ordinary comment.
	const gone = await comments.listComments(db, S, { photoIds: new Set(['img-2']) });
	assert.equal(gone.length, 2);
	assert.equal(gone[0].body, "That's me");
	assert.equal(gone[0].photoId, null);
	assert.equal(gone[0].replies[0].photoId, null);
	// Photo put back: it points again, because nothing was rewritten.
	const back = await comments.listComments(db, S, { photoIds: new Set(['img-1']) });
	assert.equal(back[0].photoId, 'img-1');
});
