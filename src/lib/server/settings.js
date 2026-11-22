/** Site-wide settings the owner changes in the admin. */
import { getDb, nowIso } from './db.js';
import { commentsModeOf, visibilityOf } from '../story-access.js';

/** @param {string} key @returns {Promise<string | null>} */
async function get(key) {
	const db = await getDb();
	const row = await db.get('SELECT value FROM site_settings WHERE key = ?', [key]);
	return row ? String(row.value) : null;
}

/** @param {string} key @param {string} value */
async function set(key, value) {
	const db = await getDb();
	await db.run(
		'INSERT INTO site_settings (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at',
		[key, value, nowIso()]
	);
}

/** Whether a brand-new story starts public or private. Public until the owner says otherwise. */
export async function defaultVisibility() {
	return visibilityOf(await get('stories.default_visibility')) ?? 'public';
}

/** @param {unknown} v */
export async function setDefaultVisibility(v) {
	const vis = visibilityOf(v);
	if (!vis) throw new Error('visibility must be public or private');
	await set('stories.default_visibility', vis);
}

/**
 * Who sees comment sections: 'everyone' (the default), 'readers' (only the
 * owner and signed-in invited readers) or 'off'.
 * @returns {Promise<import('../story-access.js').CommentsMode>}
 */
export async function commentsMode() {
	const mode = commentsModeOf(await get('comments.mode'));
	if (mode) return mode;
	// Before this setting existed there was only an on/off switch.
	return (await get('comments.enabled')) === 'off' ? 'off' : 'everyone';
}

/** @param {unknown} v */
export async function setCommentsMode(v) {
	const mode = commentsModeOf(v);
	if (!mode) throw new Error('comments mode must be off, readers or everyone');
	await set('comments.mode', mode);
}

/** Whether /following and /following.opml are shown to the public. On by default. */
export async function followingPublic() {
	return (await get('following.public')) !== 'off';
}

/** @param {boolean} on */
export async function setFollowingPublic(on) {
	await set('following.public', on ? 'on' : 'off');
}

/**
 * The name the owner's own comments are signed with. "Author" until they
 * choose one.
 */
export async function authorName() {
	return (await get('site.author_name'))?.trim() || 'Author';
}

/** @param {unknown} v Blank goes back to "Author". */
export async function setAuthorName(v) {
	await set('site.author_name', String(v ?? '').trim().slice(0, 80));
}

// ── The site's own name, words and links ─────────────────────────────────────
//
// What makes one install different from the next. The code carries the
// project's look and its default words; anything about a particular owner
// lives here, so the same build runs anybody's site.

/** The words a site shows until its owner writes their own. */
export const SITE_DEFAULTS = Object.freeze({
	supportLabel: 'Buy me a coffee',
	homeHeadline: 'Postcards\nfrom nowhere\nin particular.',
	homeIntro:
		"This is where I log mine. Travels, projects that don't belong anywhere else, thoughts that arrive on the walk to buy an envelope. Mostly pictures and a little prose.",
	aboutHeadline: 'Going to buy an envelope.'
});

/** Setting key and longest value for each field of {@link siteIdentity}. */
const SITE_FIELDS = /** @type {const} */ ({
	name: ['site.name', 80],
	tagline: ['site.tagline', 120],
	ownerName: ['site.owner_name', 80],
	ownerUrl: ['site.owner_url', 300],
	contactEmail: ['site.contact_email', 200],
	supportUrl: ['site.support_url', 300],
	supportLabel: ['site.support_label', 60],
	homeEyebrow: ['home.eyebrow', 80],
	homeHeadline: ['home.headline', 200],
	homeIntro: ['home.intro', 600],
	aboutHeadline: ['about.headline', 120],
	about: ['about.body', 8000]
});

/** Only links a browser can follow safely; anything else is dropped. @param {string} v */
const webUrl = (v) => (/^https?:\/\/\S+$/i.test(v) ? v : '');
/** @param {string} v */
const emailish = (v) => (/^[^\s@<>"']+@[^\s@<>"']+\.[^\s@<>"']+$/.test(v) ? v : '');

/**
 * Everything a page needs to show whose site this is. One query, since every
 * page asks.
 * @param {string} host  the address the site is being visited at: its name until the owner sets one
 */
export async function siteIdentity(host) {
	const db = await getDb();
	const rows = await db.all(
		"SELECT key, value FROM site_settings WHERE key LIKE 'site.%' OR key LIKE 'home.%' OR key LIKE 'about.%'"
	);
	const stored = new Map(rows.map((r) => [String(r.key), String(r.value)]));
	/** @param {keyof typeof SITE_FIELDS} field @param {string} [fallback] */
	const text = (field, fallback = '') => stored.get(SITE_FIELDS[field][0])?.trim() || fallback;
	return {
		name: text('name', host),
		tagline: text('tagline'),
		/** The owner as they want to be named on the About page, and where that name links. */
		ownerName: text('ownerName'),
		ownerUrl: webUrl(text('ownerUrl')),
		contactEmail: emailish(text('contactEmail')),
		/** A tip-jar link for the footer and the About page; none unless set. */
		supportUrl: webUrl(text('supportUrl')),
		supportLabel: text('supportLabel', SITE_DEFAULTS.supportLabel),
		homeEyebrow: text('homeEyebrow'),
		/** Up to three lines, each drawn in its own style. */
		homeHeadline: text('homeHeadline', SITE_DEFAULTS.homeHeadline).split(/\r?\n/).map((l) => l.trim()).filter(Boolean).slice(0, 3),
		homeIntro: text('homeIntro', SITE_DEFAULTS.homeIntro),
		aboutHeadline: text('aboutHeadline', SITE_DEFAULTS.aboutHeadline),
		/** The About page's text, in the same plain format as a story's text block. Blank shows the built-in page. */
		about: text('about')
	};
}

/**
 * Save the fields the owner filled in. A blank goes back to the default.
 * Fields not passed are left alone.
 * @param {Partial<Record<keyof typeof SITE_FIELDS, unknown>>} fields
 */
export async function setSiteIdentity(fields) {
	for (const [field, [key, max]] of Object.entries(SITE_FIELDS)) {
		if (!(field in fields)) continue;
		let v = String(fields[/** @type {keyof typeof SITE_FIELDS} */ (field)] ?? '').replace(/\r\n/g, '\n').trim().slice(0, max);
		if (field === 'ownerUrl' || field === 'supportUrl') v = webUrl(v);
		if (field === 'contactEmail') v = emailish(v);
		await set(key, v);
	}
}
