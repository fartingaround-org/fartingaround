/**
 * The story access rule (src/lib/story-access.js), which is `canRead` and
 * the PrivateOnlyForActiveMembers check from docs/models/readers.als.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canReadStory, isPrivate, isPublished, resolveVisibility, visibilityOf } from '../src/lib/story-access.js';

const stranger = {};
const signedOut = { isAdmin: false, reader: null };
const commenter = { reader: { role: /** @type {const} */ ('commenter') } };
const member = { reader: { role: /** @type {const} */ ('member') } };
const owner = { isAdmin: true };

const stories = {
	legacy: {}, // saved before the feature existed
	public: { visibility: 'public' },
	private: { visibility: 'private' },
	garbage: { visibility: 'Public ' }, // not exactly 'public'
	draft: { draft: true },
	privateDraft: { draft: true, visibility: 'private' }
};

test('PrivateOnlyForActiveMembers: the full table of who reads what', () => {
	/** @type {[string, object, Record<keyof typeof stories, boolean>][]} */
	const table = [
		['stranger', stranger, { legacy: true, public: true, private: false, garbage: false, draft: false, privateDraft: false }],
		['signed out', signedOut, { legacy: true, public: true, private: false, garbage: false, draft: false, privateDraft: false }],
		['commenter', commenter, { legacy: true, public: true, private: false, garbage: false, draft: false, privateDraft: false }],
		['member', member, { legacy: true, public: true, private: true, garbage: true, draft: false, privateDraft: false }],
		['owner', owner, { legacy: true, public: true, private: true, garbage: true, draft: true, privateDraft: true }]
	];
	for (const [who, viewer, expected] of table) {
		for (const [name, want] of Object.entries(expected)) {
			assert.equal(canReadStory(stories[/** @type {keyof typeof stories} */ (name)], viewer), want, `${who} reading ${name}`);
		}
	}
});

test('fails closed: any visibility that is not exactly "public" is private', () => {
	assert.equal(isPrivate({}), false);
	assert.equal(isPrivate({ visibility: 'public' }), false);
	for (const v of ['private', 'PUBLIC', '', null, 0, true, {}]) assert.equal(isPrivate({ visibility: v }), true, String(v));
});

test('isPublished: what feeds and search engines get is never a draft or private', () => {
	assert.equal(isPublished(stories.legacy), true);
	assert.equal(isPublished(stories.public), true);
	for (const s of [stories.private, stories.garbage, stories.draft, stories.privateDraft]) assert.equal(isPublished(s), false);
	// and it agrees with what a complete stranger may read
	for (const s of Object.values(stories)) assert.equal(isPublished(s), canReadStory(s, stranger));
});

test('resolveVisibility: silence never makes a private story public', () => {
	// an update that says nothing keeps what was stored
	assert.equal(resolveVisibility(undefined, { visibility: 'private' }, 'public'), 'private');
	assert.equal(resolveVisibility('nonsense', { visibility: 'private' }, 'public'), 'private');
	assert.equal(resolveVisibility(undefined, { visibility: 'public' }, 'private'), 'public');
	assert.equal(resolveVisibility(undefined, {}, 'private'), undefined, 'a legacy story stays as it was');
	assert.equal(resolveVisibility(undefined, { visibility: 'odd' }, 'public'), 'private', 'a bad stored value is repaired to private');
	// an explicit choice wins
	assert.equal(resolveVisibility('public', { visibility: 'private' }, 'private'), 'public');
	assert.equal(resolveVisibility('private', {}, 'public'), 'private');
	// a new story takes the site default
	assert.equal(resolveVisibility(undefined, null, 'private'), 'private');
	assert.equal(resolveVisibility(undefined, null, 'public'), 'public');
	assert.equal(visibilityOf('private'), 'private');
	assert.equal(visibilityOf('else'), undefined);
});

test('comment sections: everyone, signed-in readers only, or off', async () => {
	const { canSeeComments, commentsModeOf } = await import('../src/lib/story-access.js');
	/** @type {[string, object, boolean, boolean, boolean][]} who, viewer, everyone, readers, off */
	const table = [
		['stranger', stranger, true, false, false],
		['signed out', signedOut, true, false, false],
		['commenter', commenter, true, true, false],
		['member', member, true, true, false],
		['owner', owner, true, true, false]
	];
	for (const [who, viewer, everyone, readers, off] of table) {
		assert.equal(canSeeComments('everyone', viewer), everyone, `${who}, everyone`);
		assert.equal(canSeeComments('readers', viewer), readers, `${who}, readers`);
		assert.equal(canSeeComments('off', viewer), off, `${who}, off`);
	}
	assert.equal(commentsModeOf('readers'), 'readers');
	assert.equal(commentsModeOf('anyone'), undefined);
});
