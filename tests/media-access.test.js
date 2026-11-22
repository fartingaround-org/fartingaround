/**
 * Which files are public (src/lib/media-access.js). `restricted` and the
 * checks from docs/models/media.als.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildMediaIndex, imageFlags, mayFetchPrivate, objectFlags } from '../src/lib/media-access.js';

/** @param {Record<string, any>} extra @param {any[]} blocks */
const story = (extra, blocks) => /** @type {any} */ ({ slug: 's', title: '', date: '2026-01-01', blocks, createdAt: '', updatedAt: '', ...extra });
const photo = (/** @type {string} */ id) => ({ type: 'photo', id: `b-${id}`, imageId: id });

const stranger = {};
const commenter = { reader: { role: /** @type {const} */ ('commenter') } };
const member = { reader: { role: /** @type {const} */ ('member') } };
const owner = { isAdmin: true };
const phone = { isAuthorApp: true };

test('a file is public only while a published public story uses it', () => {
	const index = buildMediaIndex([
		story({ slug: 'pub' }, [photo('in-public'), photo('shared')]),
		story({ slug: 'priv', visibility: 'private' }, [photo('in-private'), photo('shared')]),
		story({ slug: 'draft', draft: true }, [photo('in-draft')]),
		story({ slug: 'priv-draft', draft: true, visibility: 'private' }, [photo('in-private-draft')]),
		story({ slug: 'cover', coverImageId: 'cover-of-private', visibility: 'private' }, [])
	]);
	assert.deepEqual(imageFlags(index, 'in-public'), { public: true, member: true });
	assert.deepEqual(imageFlags(index, 'shared'), { public: true, member: true }, 'also in a public story, so public');
	assert.deepEqual(imageFlags(index, 'in-private'), { public: false, member: true });
	assert.deepEqual(imageFlags(index, 'cover-of-private'), { public: false, member: true });
	assert.deepEqual(imageFlags(index, 'in-draft'), { public: false, member: false });
	assert.deepEqual(imageFlags(index, 'in-private-draft'), { public: false, member: false });
	assert.deepEqual(imageFlags(index, 'just-uploaded'), { public: false, member: false }, 'not attached to any story yet');
});

test('AlwaysPrivateNeverLeaves: who may fetch a restricted file from /private', () => {
	const priv = { public: false, member: true }; // in a published private story
	const draft = { public: false, member: false }; // draft-only, or unattached
	for (const [flags, name] of /** @type {const} */ ([[priv, 'private'], [draft, 'draft']])) {
		assert.equal(mayFetchPrivate(flags, stranger), false, `stranger, ${name}`);
		assert.equal(mayFetchPrivate(flags, commenter), false, `commenter, ${name}`);
		assert.equal(mayFetchPrivate(flags, owner), true, `owner, ${name}`);
		assert.equal(mayFetchPrivate(flags, phone), true, `phone app, ${name}`);
	}
	assert.equal(mayFetchPrivate(priv, member), true);
	assert.equal(mayFetchPrivate(draft, member), false, 'a member cannot fetch draft media');
});

test('every kind of media a story holds is covered', () => {
	const index = buildMediaIndex([
		story({ visibility: 'private' }, [
			{ type: 'diptych', id: 'd', left: { imageId: 'left' }, right: { imageId: 'right' } },
			{ type: 'gallery', id: 'g', items: [{ imageId: 'g1', annotationId: 'g1a', overlayId: 'g1o', starlessId: 'g1s', r2: { base: 'gallery/abc' } }] },
			{ type: 'dump', id: 'du', photos: [{ imageId: 'dp', audio: { id: 'a', key: 'audio/caption.webm' } }] },
			{ type: 'audio', id: 'au', r2Key: 'audio/clip.webm' },
			{ type: 'video', id: 'v', r2Key: 'video/clip.mp4', posterImageId: 'poster' }
		])
	]);
	for (const id of ['left', 'right', 'g1', 'g1a', 'g1o', 'g1s', 'dp', 'poster']) {
		assert.deepEqual(imageFlags(index, id), { public: false, member: true }, id);
	}
	for (const key of ['audio/caption.webm', 'audio/clip.webm', 'video/clip.mp4', 'gallery/abc/orig.jpg', 'gallery/abc/w1600.jpg']) {
		assert.deepEqual(objectFlags(index, key), { public: false, member: true }, key);
	}
	assert.deepEqual(objectFlags(index, 'gallery/abcd/orig.jpg'), { public: false, member: false }, 'a prefix match stops at the folder');
	assert.deepEqual(objectFlags(index, 'gallery/other/orig.jpg'), { public: false, member: false });
});

test('the lingering window is only ever a flip: publishing makes public, unpublishing restricts again', () => {
	const blocks = [photo('p')];
	const flags = (/** @type {Record<string, any>} */ extra) => imageFlags(buildMediaIndex([story(extra, blocks)]), 'p').public;
	assert.equal(flags({ draft: true }), false);
	assert.equal(flags({}), true);
	assert.equal(flags({ visibility: 'private' }), false);
	assert.equal(flags({ draft: true, visibility: 'public' }), false);
});
