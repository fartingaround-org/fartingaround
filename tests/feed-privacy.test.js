/** Personal feed links never reach the public Following list (src/lib/feed-privacy.js). */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isSecretFeedUrl, stripSecretFeeds } from '../src/lib/feed-privacy.js';

test('which feed links are keys', () => {
	for (const url of [
		'https://friend.example/feed.json?token=abc123',
		'https://friend.example/rss.xml?TOKEN=abc123',
		'https://pod.example/feed?auth=xyz&format=rss',
		'https://x.example/rss?api_key=1',
		'https://user:pass@x.example/rss',
		'not a url'
	]) assert.equal(isSecretFeedUrl(url), true, url);
	for (const url of ['https://friend.example/feed.json', 'https://x.example/rss?format=xml&page=2', 'https://x.example/feed?keyword=tokens']) {
		assert.equal(isSecretFeedUrl(url), false, url);
	}
});

test('stripSecretFeeds removes only the keys and keeps the document well formed', () => {
	const opml = `<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
  <head><title>Subscriptions</title></head>
  <body>
    <outline type="rss" text="Public" title="Public" xmlUrl="https://a.example/rss.xml" htmlUrl="https://a.example" />
    <outline type="rss" text="Friend (private)" xmlUrl="https://friend.example/feed.json?token=SECRET&amp;x=1" htmlUrl="https://friend.example" />
    <outline text="Folder">
      <outline type="rss" text="Nested private" xmlUrl='https://b.example/rss.xml?token=SECRET2'></outline>
      <outline type="rss" text="Nested public" xmlUrl="https://c.example/rss.xml"/>
    </outline>
  </body>
</opml>
`;
	const out = stripSecretFeeds(opml);
	assert.ok(!out.includes('SECRET'), 'no token survives');
	assert.ok(!out.includes('friend.example'));
	assert.ok(out.includes('https://a.example/rss.xml'));
	assert.ok(out.includes('https://c.example/rss.xml'));
	assert.ok(out.includes('<outline text="Folder">'));
	assert.equal((out.match(/<outline\b/g) || []).length, 3);
	assert.equal((out.match(/<\/outline>/g) || []).length, 1, 'only the folder keeps a closing tag');
	assert.equal(stripSecretFeeds(out), out, 'idempotent');
});
