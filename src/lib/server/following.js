/**
 * The author's subscription list ("following"), stored as the OPML the phone
 * app uploads. The app is the source of truth — subscriptions live on the
 * phone — and this is its backup plus the public blogroll.
 */

import { getDb, nowIso } from './db.js';
import { isSecretFeedUrl, stripSecretFeeds } from '../feed-privacy.js';

const MAX_BYTES = 512 * 1024;

/** @returns {Promise<{ opml: string, updatedAt: string } | null>} */
export async function getFollowing() {
	const db = await getDb();
	const row = await db.get('SELECT opml, updated_at FROM following WHERE id = 1');
	if (!row) return null;
	// Personal feed links are keys and must never be published. They are
	// stripped when the list is stored; this also cleans a list stored
	// before that rule existed, and rewrites it so the key is gone for good.
	const stored = String(row.opml);
	const opml = stripSecretFeeds(stored);
	if (opml !== stored) await db.run('UPDATE following SET opml = ? WHERE id = 1', [opml]);
	return { opml, updatedAt: String(row.updated_at) };
}

/**
 * Store a new OPML document. Rejects anything that isn't recognisably OPML.
 * @param {string} opml
 */
export async function putFollowing(opml) {
	if (typeof opml !== 'string' || opml.length > MAX_BYTES) throw new Error('OPML too large');
	if (!/<opml[\s>]/i.test(opml) || !/<\/opml>/i.test(opml)) throw new Error('Not an OPML document');
	// Never store a personal feed link (a key to someone's private stories):
	// this list is published as the blogroll.
	opml = stripSecretFeeds(opml);
	const db = await getDb();
	const now = nowIso();
	if (db.dialect === 'postgres') {
		await db.run(
			'INSERT INTO following (id, opml, updated_at) VALUES (1, ?, ?) ON CONFLICT (id) DO UPDATE SET opml = EXCLUDED.opml, updated_at = EXCLUDED.updated_at',
			[opml, now]
		);
	} else {
		await db.run(
			'INSERT INTO following (id, opml, updated_at) VALUES (1, ?, ?) ON CONFLICT (id) DO UPDATE SET opml = excluded.opml, updated_at = excluded.updated_at',
			[opml, now]
		);
	}
	return { updatedAt: now };
}

/**
 * @typedef {{ title: string, xmlUrl: string, htmlUrl: string | null }} FollowedFeed
 */

/**
 * Pull the feeds out of an OPML document for the blogroll page. A regex
 * scan is enough: we only read `<outline>` attributes, and anything odd
 * is just skipped.
 * @param {string} opml
 * @returns {FollowedFeed[]}
 */
export function parseOpml(opml) {
	/** @type {FollowedFeed[]} */
	const out = [];
	const seen = new Set();
	for (const m of opml.matchAll(/<outline\b([^>]*)\/?>/gi)) {
		const attrs = m[1];
		const xmlUrl = attr(attrs, 'xmlUrl');
		if (!xmlUrl || !/^https?:\/\//i.test(xmlUrl) || seen.has(xmlUrl) || isSecretFeedUrl(xmlUrl)) continue;
		seen.add(xmlUrl);
		const htmlUrl = attr(attrs, 'htmlUrl');
		out.push({
			title: attr(attrs, 'title') || attr(attrs, 'text') || hostOf(htmlUrl || xmlUrl),
			xmlUrl,
			htmlUrl: htmlUrl && /^https?:\/\//i.test(htmlUrl) ? htmlUrl : null
		});
	}
	out.sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }));
	return out;
}

/** @param {string} attrs @param {string} name */
function attr(attrs, name) {
	const m = attrs.match(new RegExp(`\\b${name}\\s*=\\s*"([^"]*)"`, 'i')) || attrs.match(new RegExp(`\\b${name}\\s*=\\s*'([^']*)'`, 'i'));
	return m ? decode(m[1]) : null;
}

/** @param {string} s */
function decode(s) {
	return s
		.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#39;/g, "'")
		.replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
		.replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
		.replace(/&amp;/g, '&');
}

/** @param {string} url */
function hostOf(url) {
	try { return new URL(url).host.replace(/^www\./, ''); } catch { return url; }
}
