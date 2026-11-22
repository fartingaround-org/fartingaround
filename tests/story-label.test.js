import test from 'node:test';
import assert from 'node:assert/strict';
import { photoLocation } from '../src/lib/blocks.js';
import { isNote, leadBlock, noteThumbId, storyLabel, storyPublishedAt } from '../src/lib/story-label.js';

test('a blank or "Untitled" title makes a note', () => {
	assert.equal(isNote({ title: '' }), true);
	assert.equal(isNote({ title: '   ' }), true);
	assert.equal(isNote({ title: 'Untitled' }), true);
	assert.equal(isNote({}), true);
	assert.equal(isNote({ title: 'A walk' }), false);
});

test('a titled story is called by its title', () => {
	assert.equal(storyLabel({ title: ' A walk ', blocks: [{ type: 'photo', caption: 'x' }] }), 'A walk');
});

test('a note is called by its summary, caption, text, transcript, then kind and date', () => {
	const photo = { type: 'photo', id: 'p', imageId: 'img1', caption: 'Testing just a photo' };
	assert.equal(storyLabel({ title: '', summary: 'The summary', blocks: [photo] }), 'The summary');
	assert.equal(storyLabel({ title: '', blocks: [photo] }), 'Testing just a photo');
	assert.equal(storyLabel({ title: '', blocks: [{ type: 'dump', photos: [{ imageId: 'a' }, { imageId: 'b', caption: 'Second one' }] }] }), 'Second one');
	assert.equal(storyLabel({ title: '', blocks: [{ type: 'text', body: 'First line\n\nSecond paragraph' }, photo] }), 'Testing just a photo');
	assert.equal(storyLabel({ title: '', blocks: [{ type: 'text', body: 'First line\nmore' }] }), 'First line');
	assert.equal(storyLabel({ title: '', blocks: [{ type: 'audio', transcript: 'Hello there' }] }), 'Hello there');
	assert.equal(storyLabel({ title: '', date: '2026-11-19', blocks: [{ type: 'photo', imageId: 'x' }] }), 'Photo · Nov 19 · 2026');
	assert.equal(storyLabel({ title: '', date: '2026-11-19', blocks: [{ type: 'video' }] }), 'Video · Nov 19 · 2026');
	assert.equal(storyLabel({ title: '', blocks: [] }), 'Note');
});

test('a long label is clipped at a word', () => {
	const label = storyLabel({ title: '', blocks: [{ type: 'text', body: 'word '.repeat(60) }] });
	assert.ok(label.length <= 80, label);
	assert.ok(label.endsWith('…'));
	assert.ok(!label.includes(' …'));
});

test('the lead block skips dividers; the thumbnail falls back to the first photo', () => {
	const s = { title: '', coverImageId: '', blocks: [{ type: 'divider' }, { type: 'audio', id: 'a' }, { type: 'dump', photos: [{ imageId: 'img9' }] }] };
	assert.equal(leadBlock(s).type, 'audio');
	assert.equal(noteThumbId(s), 'img9');
	assert.equal(noteThumbId({ ...s, coverImageId: 'cover' }), 'cover');
	assert.equal(noteThumbId({ blocks: [{ type: 'text', body: 'x' }] }), '');
});

test('a story written on its own day is published at the moment it was made; a backdated one at noon UTC', () => {
	assert.equal(storyPublishedAt({ date: '2026-11-19', createdAt: '2026-11-19T14:32:10.000Z' }), '2026-11-19T14:32:10.000Z');
	// Evening in Minneapolis is already tomorrow in UTC.
	assert.equal(storyPublishedAt({ date: '2026-11-19', createdAt: '2026-11-20T03:05:00.000Z' }), '2026-11-20T03:05:00.000Z');
	assert.equal(storyPublishedAt({ date: '2026-08-04', createdAt: '2026-11-19T14:32:10.000Z' }), '2026-08-04T12:00:00Z');
	assert.equal(storyPublishedAt({ date: '2026-08-04' }), '2026-08-04T12:00:00Z');
});

test('the publish moment wins over the creation moment', () => {
	assert.equal(storyPublishedAt({ date: '2026-11-19', createdAt: '2026-11-19T13:00:00.000Z', publishedAt: '2026-11-19T23:10:00.000Z' }), '2026-11-19T23:10:00.000Z');
	// Published months after the day it is about: its own date decides.
	assert.equal(storyPublishedAt({ date: '2026-08-04', createdAt: '2026-08-04T13:00:00.000Z', publishedAt: '2026-11-19T23:10:00.000Z' }), '2026-08-04T12:00:00Z');
});

test('a story takes its location from the first photo that knows where it was taken', () => {
	assert.equal(photoLocation({ blocks: [{ type: 'text', body: 'x' }, { type: 'photo', imageId: 'a' }] }), null);
	assert.deepEqual(photoLocation({ blocks: [{ type: 'photo', imageId: 'a', exif: { takenAt: 'x' } }, { type: 'photo', imageId: 'b', exif: { lat: 44.98, lon: -93.27 } }] }), { lat: 44.98, lon: -93.27 });
	assert.deepEqual(photoLocation({ blocks: [{ type: 'dump', photos: [{ imageId: 'a' }, { imageId: 'b', exif: { lat: 1, lon: 2 }, coords: { lat: 3, lon: 4 } }] }] }), { lat: 3, lon: 4 });
});
