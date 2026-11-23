import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeStory } from '../src/lib/server/story-validate.js';
import { storyToHtml } from '../src/lib/server/feed-html.js';
import { matchesSearch, searchTerms } from '../src/lib/story-search.js';

const blocks = [
	{ type: 'heading', id: 'h1', body: 'Get <fa>' },
	{ type: 'code', id: 'c1', body: 'fa setup --yes\n  && echo "done" > log', caption: 'On the server' }
];

test('a story may hold heading and code blocks', () => {
	const out = normalizeStory({ slug: 's', title: 'T', date: '2026-11-23', blocks });
	assert.ok('story' in out, JSON.stringify(out));
	assert.deepEqual(out.story.blocks.map((b) => b.type), ['heading', 'code']);
	// Code is kept exactly as written: indentation, quotes and all.
	assert.equal(out.story.blocks[1].body, blocks[1].body);
});

test('feed readers get a real heading and preformatted code, escaped', () => {
	const html = storyToHtml({ slug: 's', title: 'T', date: '2026-11-23', blocks }, 'https://example.org');
	assert.ok(html.includes('<h2>Get &lt;fa&gt;</h2>'), html);
	assert.ok(html.includes('<pre><code>fa setup --yes\n  &amp;&amp; echo &quot;done&quot; &gt; log</code></pre>') || html.includes('<pre><code>fa setup --yes\n  &amp;&amp; echo "done" &gt; log</code></pre>'), html);
	assert.ok(html.includes('On the server'));
});

test('an empty heading or code block adds nothing to the feed', () => {
	const html = storyToHtml({ slug: 's', title: 'T', date: '2026-11-23', blocks: [{ type: 'heading', id: 'h', body: '' }, { type: 'code', id: 'c', body: '' }] }, 'https://example.org');
	assert.ok(!html.includes('<h2>') && !html.includes('<pre>'), html);
});

test('search finds the words in headings and commands', () => {
	const story = { title: 'T', blocks };
	assert.equal(matchesSearch(story, searchTerms('setup --yes')), true);
	assert.equal(matchesSearch(story, searchTerms('get fa')), true);
});
