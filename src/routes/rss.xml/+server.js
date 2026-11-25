/**
 * RSS 2.0 feed for the site.
 *
 *   GET /rss.xml
 *
 * Returns every non-draft story, newest first, with a short description and
 * the full story as HTML in content:encoded (shared with feed.json).
 * Cached aggressively at the edge — the feed only changes when a new story
 * goes live, which the existing admin save path doesn't currently
 * invalidate. Five-minute cache is short enough for personal use.
 */

import { listStoriesFor, publicStory } from '#lib/server/stories.js';
import { storyToHtml, storySummary, withReaderToken } from '#lib/server/feed-html.js';
import { readerFeed } from '#lib/server/reader-feed.js';
import { isPrivate } from '#lib/story-access.js';
import { locationPrecision, siteIdentity } from '#lib/server/settings.js';
import { isNote, storyPublishedAt } from '#lib/story-label.js';

/** Escape characters that have meaning in XML so user-authored prose can't break the feed. */
function xmlEscape(/** @type {string} */ s) {
	return s
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;')
		.replace(/'/g, '&apos;');
}

/** @type {import('./$types').RequestHandler} */
export async function GET({ setHeaders, url }) {
	// Absolute links built from the request origin, so any install's feed is right.
	const SITE = url.origin;
	// /rss.xml is public stories only and the same for everyone. A member's
	// personal link, /rss.xml?token=…, adds the private stories they may read.
	const personal = await readerFeed(url);
	if (personal === 'invalid') return new Response('This feed link is no longer valid.', { status: 404, headers: { 'cache-control': 'no-store' } });
	const precision = await locationPrecision();
	const live = (await listStoriesFor(personal ? { reader: personal.reader } : {})).map((s) => publicStory(s, precision));
	/** @param {import('#lib/types.js').Story} s */
	const html = (s) => {
		const out = storyToHtml(s, SITE);
		return personal && isPrivate(s) ? withReaderToken(out, SITE, personal.token) : out;
	};
	const self = personal ? `${SITE}/rss.xml?token=${encodeURIComponent(personal.token)}` : `${SITE}/rss.xml`;

	const lastPub =
		live.length > 0
			? new Date(live[0].date).toUTCString()
			: new Date().toUTCString();

	const items = live
		.map((s) => {
			// RSS pubDate uses RFC 822; story.date is yyyy-mm-dd so anchor at noon UTC
			// to dodge timezone-boundary weirdness in feed readers.
			const pubDate = new Date(storyPublishedAt(s)).toUTCString();
			const link = `${SITE}/stories/${s.slug}`;
			return [
				'    <item>',
				// A note (no title) goes out without one; RSS allows an item with only a description.
				...(isNote(s) ? [] : [`      <title>${xmlEscape(s.title)}</title>`]),
				`      <link>${link}</link>`,
				`      <guid isPermaLink="true">${link}</guid>`,
				`      <pubDate>${pubDate}</pubDate>`,
				`      <description>${xmlEscape(storySummary(s))}</description>`,
				`      <content:encoded><![CDATA[${html(s).replace(/\]\]>/g, ']]]]><![CDATA[>')}]]></content:encoded>`,
				...(s.tags || []).map((t) => `      <category>${xmlEscape(t)}</category>`),
				...(s.location ? [`      <category>${xmlEscape(s.location)}</category>`] : []),
				'    </item>'
			].join('\n');
		})
		.join('\n');

	const xml = [
		'<?xml version="1.0" encoding="UTF-8"?>',
		'<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:content="http://purl.org/rss/1.0/modules/content/">',
		'  <channel>',
		`    <title>${xmlEscape((await siteIdentity(url.host)).name)}</title>`,
		`    <link>${SITE}</link>`,
		`    <atom:link href="${xmlEscape(self)}" rel="self" type="application/rss+xml" />`,
		'    <description>A logbook of farting around.</description>',
		'    <language>en</language>',
		`    <lastBuildDate>${lastPub}</lastBuildDate>`,
		items,
		'  </channel>',
		'</rss>'
	].join('\n');

	setHeaders({
		'content-type': 'application/rss+xml; charset=utf-8',
		'cache-control': personal ? 'private, no-store' : 'public, max-age=300'
	});
	return new Response(xml);
}
