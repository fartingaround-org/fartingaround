/**
 * JSON Feed 1.1 — https://jsonfeed.org/version/1.1
 *
 *   GET /feed.json
 *
 * Same stories as rss.xml, with full HTML content for ordinary readers plus a
 * `_fartingaround` extension on each item carrying the story's blocks, so an
 * aware reader (the Consume mode of the capture app) can render photo dumps,
 * galleries, audio and maps natively. The extension's shape is documented in
 * docs/feed-extension.md — that document is the contract.
 *
 * Nothing in here is private: stories go through publicStory() first, which
 * strips unapplied photo EXIF, exactly as the story page does.
 */

import { listStoriesFor, publicStory } from '#lib/server/stories.js';
import { storyToHtml, storySummary, withReaderToken } from '#lib/server/feed-html.js';
import { readerFeed } from '#lib/server/reader-feed.js';
import { isPrivate } from '#lib/story-access.js';
import { siteIdentity } from '#lib/server/settings.js';
import { imageUrl } from '#lib/image.js';
import { isNote, storyPublishedAt } from '#lib/story-label.js';

/** Bump when the extension's shape changes incompatibly. */
const EXTENSION_VERSION = 1;

/** @type {import('./$types').RequestHandler} */
export async function GET({ setHeaders, url }) {
	const origin = url.origin;
	// /feed.json is public stories only and the same for everyone. A member's
	// personal link, /feed.json?token=…, adds the private stories they may read.
	const personal = await readerFeed(url);
	if (personal === 'invalid') return new Response('This feed link is no longer valid.', { status: 404, headers: { 'cache-control': 'no-store' } });
	const stories = (await listStoriesFor(personal ? { reader: personal.reader } : {})).map(publicStory);
	/** Media URLs of a private story carry the member's token. @param {import('#lib/types.js').Story} s @param {string} text */
	const mine = (s, text) => (personal && isPrivate(s) ? withReaderToken(text, origin, personal.token) : text);

	const feed = {
		version: 'https://jsonfeed.org/version/1.1',
		title: (await siteIdentity(url.host)).name,
		home_page_url: `${origin}/`,
		feed_url: personal ? `${origin}/feed.json?token=${encodeURIComponent(personal.token)}` : `${origin}/feed.json`,
		description: 'A logbook of farting around.',
		language: 'en',
		// Tells aware readers this feed carries block-based stories.
		_fartingaround: {
			version: EXTENSION_VERSION,
			base: origin,
			spec: 'https://github.com/fartingaround-org/fartingaround/blob/main/docs/feed-extension.md'
		},
		items: stories.map((s) => ({
			id: `${origin}/stories/${s.slug}`,
			url: `${origin}/stories/${s.slug}`,
			// A note (no title) goes out without one, as JSON Feed allows.
			title: isNote(s) ? undefined : s.title,
			content_html: mine(s, storyToHtml(s, origin)),
			summary: storySummary(s) || undefined,
			image: s.coverImageId ? mine(s, `${origin}${imageUrl(s.coverImageId, { w: 1200, q: 85 })}`) : undefined,
			date_published: storyPublishedAt(s),
			date_modified: s.updatedAt || undefined,
			tags: [...(s.tags || []), ...(s.location ? [s.location] : [])].filter(Boolean),
			_fartingaround: {
				slug: s.slug,
				// Private: an aware reader must add `?token=<the feed's token>` to
				// every /img, /media and /api/stories URL it builds for this item.
				private: isPrivate(s) || undefined,
				date: s.date,
				location: s.location || undefined,
				coords: s.coords || undefined,
				weather: s.weather || undefined,
				astro: s.astro || undefined,
				coverImageId: s.coverImageId || undefined,
				coverFocus: s.coverFocus || undefined,
				blocks: s.blocks || []
			}
		}))
	};

	setHeaders({
		'content-type': 'application/feed+json; charset=utf-8',
		'cache-control': personal ? 'private, no-store' : 'public, max-age=300'
	});
	return new Response(JSON.stringify(feed));
}
