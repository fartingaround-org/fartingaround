import test from 'node:test';
import assert from 'node:assert/strict';
import { hasTag, matchesSearch, searchTerms, storyText, tagCounts } from '../src/lib/story-search.js';

const story = {
	title: 'Setting up backups',
	summary: 'Where your site goes when the server does not come back.',
	location: 'Minneapolis',
	tags: ['Setup', 'backups'],
	blocks: [
		{ type: 'text', id: 't', body: 'Run fa backup once a week.' },
		{ type: 'aside', id: 'a', body: 'Restic is optional.', label: 'Good to know' },
		{ type: 'photo', id: 'p', imageId: 'i1', caption: 'The dashboard', alt: 'A screenshot of the café page' },
		{ type: 'diptych', id: 'd', left: { imageId: 'l', caption: 'Before' }, right: { imageId: 'r', alt: 'After the restore' } },
		{ type: 'gallery', id: 'g', items: [{ imageId: 'g1', caption: 'Orion' }] },
		{ type: 'dump', id: 'du', photos: [{ imageId: 'x', caption: 'Volume', section: 'Storage', audio: { id: 'au', transcript: 'spoken words here' } }] },
		{ type: 'audio', id: 'au2', r2Key: 'k', mimeType: 'audio/mp4', transcript: 'a longer recording' },
		{ type: 'divider', id: 'dv', place: 'Duluth' }
	]
};

test('search reads every part of a story a reader can read', () => {
	const text = storyText(story);
	for (const piece of ['Setting up backups', 'does not come back', 'Minneapolis', 'Setup', 'fa backup', 'Good to know', 'The dashboard', 'café page', 'Before', 'After the restore', 'Orion', 'Volume', 'Storage', 'spoken words here', 'a longer recording', 'Duluth']) {
		assert.ok(text.includes(piece), `missing: ${piece}`);
	}
	// Ids and file keys are not words anyone searches for.
	assert.ok(!text.includes('i1') && !text.includes('audio/mp4'));
});

test('every word must be there, in any order and any case', () => {
	assert.equal(matchesSearch(story, searchTerms('BACKUP week')), true);
	assert.equal(matchesSearch(story, searchTerms('week backup')), true);
	assert.equal(matchesSearch(story, searchTerms('backup month')), false);
	assert.equal(matchesSearch(story, searchTerms('')), true);
	assert.equal(matchesSearch(story, searchTerms('   ')), true);
});

test('accents do not get in the way', () => {
	assert.equal(matchesSearch(story, searchTerms('cafe')), true);
	assert.equal(matchesSearch({ title: 'cafe' }, searchTerms('Café')), true);
});

test('a search is at most eight words', () => {
	assert.equal(searchTerms('a b c d e f g h i j').length, 8);
});

test('a story with nothing in it matches nothing but the empty search', () => {
	assert.equal(matchesSearch({}, searchTerms('x')), false);
	assert.equal(matchesSearch({}, []), true);
});

test('tags match whatever their case', () => {
	assert.equal(hasTag(story, 'setup'), true);
	assert.equal(hasTag(story, ' BACKUPS '), true);
	assert.equal(hasTag(story, 'set'), false);
	assert.equal(hasTag({}, 'setup'), false);
});

test('tags are counted once per story, most used first, then by name', () => {
	const counts = tagCounts([
		{ tags: ['Setup', 'news'] },
		{ tags: ['setup', 'SETUP', 'phone app'] },
		{ tags: ['News', ' ', 'setup'] },
		{}
	]);
	assert.deepEqual(counts, [
		{ tag: 'Setup', count: 3 },
		{ tag: 'news', count: 2 },
		{ tag: 'phone app', count: 1 }
	]);
});
