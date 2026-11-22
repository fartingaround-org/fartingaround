/**
 * Comments. Plain requirements from specs/comments-and-accounts-plan.md
 * (not modelled in Alloy): who may comment is decided by the caller with
 * canReadStory; this module owns what a comment is and who sees which.
 *
 * Like readers.js, every function takes the Db so tests can run on SQLite.
 */
import { randomUUID } from 'node:crypto';
import { nowIso } from './db.js';

/** @typedef {import('./db.js').Db} Db */
/** @typedef {{ contactId: string } | { owner: true }} Author */
/**
 * @typedef {{
 *   id: string, name: string, byOwner: boolean, body: string, createdAt: string, editedAt: string | null,
 *   mine: boolean, held: boolean, deleted: boolean, hidden: boolean, photoId: string | null,
 *   replies: PublicComment[]
 * }} PublicComment
 *   photoId: the dump photo this comment is about, or null. A reply carries its parent's.
 *   held: hidden by the owner, shown only to its author. hidden: only ever true for the owner's view.
 */

export const MAX_BODY = 4000;
export const MAX_LINKS = 5;
export const PER_MINUTE = 10;

const messages = {
	empty: 'Write something first.',
	too_long: `Keep it under ${MAX_BODY} characters.`,
	too_many_links: `No more than ${MAX_LINKS} links in one comment.`,
	too_fast: 'You are commenting very quickly. Wait a minute and try again.',
	bad_parent: "That comment can't be replied to.",
	bad_photo: 'That photo is no longer in this story.',
	not_found: 'That comment is not yours to change, or no longer exists.'
};

export class CommentError extends Error {
	/** @param {keyof typeof messages} code */
	constructor(code) {
		super(messages[code]);
		this.code = code;
	}
}

/** @param {unknown} raw */
function clean(raw) {
	const body = String(raw ?? '').replace(/\r\n/g, '\n').trim();
	if (!body) throw new CommentError('empty');
	if (body.length > MAX_BODY) throw new CommentError('too_long');
	if ((body.match(/https?:\/\//gi) || []).length > MAX_LINKS) throw new CommentError('too_many_links');
	return body;
}

const isOwner = (/** @type {Author} */ a) => 'owner' in a;

/**
 * Post a comment or a reply. The caller has already checked that this
 * author may read (and so comment on) the story.
 * @param {Db} db
 * @param {{ storySlug: string, author: Author, body: unknown, parentId?: string | null, photoId?: string | null }} c
 *   photoId: a photo of this story the comment is about. The caller has checked
 *   it is one. A reply ignores it and takes its parent's.
 * @returns {Promise<string>} the new comment's id
 */
export async function addComment(db, c) {
	const body = clean(c.body);
	return db.tx(async (t) => {
		const now = nowIso();
		let photoId = c.photoId ? String(c.photoId) : null;
		if (c.parentId) {
			const parent = await t.get('SELECT story_slug, parent_id, status, photo_id FROM comments WHERE id = ?', [c.parentId]);
			if (!parent || parent.story_slug !== c.storySlug || parent.parent_id || parent.status !== 'visible') throw new CommentError('bad_parent');
			// A reply stays with the photo its parent is about.
			photoId = parent.photo_id ? String(parent.photo_id) : null;
		}
		if (!isOwner(c.author)) {
			const recent = await t.get('SELECT COUNT(*) AS n FROM comments WHERE contact_id = ? AND created_at > ?', [
				/** @type {{ contactId: string }} */ (c.author).contactId,
				new Date(Date.now() - 60_000).toISOString()
			]);
			if (Number(recent?.n) >= PER_MINUTE) throw new CommentError('too_fast');
		}
		const id = randomUUID();
		await t.run(
			'INSERT INTO comments (id, story_slug, contact_id, by_owner, parent_id, body, status, created_at, photo_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
			[id, c.storySlug, isOwner(c.author) ? null : /** @type {{ contactId: string }} */ (c.author).contactId, isOwner(c.author) ? 1 : 0, c.parentId || null, body, 'visible', now, photoId]
		);
		return id;
	});
}

/** WHERE clause that matches only this author's own, not-deleted comment. @param {Author} a */
function own(a) {
	return isOwner(a)
		? { sql: 'by_owner = 1', params: [] }
		: { sql: 'by_owner = 0 AND contact_id = ?', params: [/** @type {{ contactId: string }} */ (a).contactId] };
}

/** An author edits their own comment. @param {Db} db @param {string} id @param {Author} author @param {unknown} rawBody */
export async function editComment(db, id, author, rawBody) {
	const body = clean(rawBody);
	const o = own(author);
	const r = await db.run(`UPDATE comments SET body = ?, edited_at = ? WHERE id = ? AND status <> 'deleted' AND ${o.sql}`, [body, nowIso(), id, ...o.params]);
	if (r.changes !== 1) throw new CommentError('not_found');
}

/**
 * An author deletes their own comment. The words are erased; the row stays
 * only so replies to it still have somewhere to hang.
 * @param {Db} db @param {string} id @param {Author} author
 */
export async function deleteOwnComment(db, id, author) {
	const o = own(author);
	const r = await db.run(`UPDATE comments SET body = '', status = 'deleted', edited_at = ? WHERE id = ? AND status <> 'deleted' AND ${o.sql}`, [nowIso(), id, ...o.params]);
	if (r.changes !== 1) throw new CommentError('not_found');
}

/**
 * The owner hides, shows or deletes any comment. Deleting removes the row
 * and, with it, any replies.
 * @param {Db} db @param {string} id @param {'hide' | 'show' | 'delete'} action
 */
export async function moderateComment(db, id, action) {
	const r =
		action === 'delete'
			? await db.run('DELETE FROM comments WHERE id = ?', [id])
			: await db.run(`UPDATE comments SET status = ? WHERE id = ? AND status <> 'deleted'`, [action === 'hide' ? 'hidden' : 'visible', id]);
	if (r.changes !== 1) throw new CommentError('not_found');
}

/**
 * A story's comments as this viewer should see them, oldest first, replies
 * nested under their parent.
 *
 *  - visible: everyone who can read the story.
 *  - hidden by the owner: only the owner, and its author (marked `held`).
 *    Replies under a hidden comment are hidden with it.
 *  - deleted by its author: a placeholder, and only while it has replies.
 *
 * @param {Db} db @param {string} storySlug
 * @param {{ isAdmin?: boolean, readerId?: string | null, ownerName?: string, photoIds?: Set<string> }} viewer
 *   ownerName: what the owner's comments are signed with.
 *   photoIds: the photos currently in the story. A comment about a photo that
 *   is no longer among them is returned as an ordinary comment (photoId null);
 *   nothing is deleted, so it points again if the photo comes back. Omit to
 *   get every pointer as stored.
 * @returns {Promise<PublicComment[]>}
 */
export async function listComments(db, storySlug, viewer) {
	const rows = await db.all(
		`SELECT m.id, m.contact_id, m.by_owner, m.parent_id, m.body, m.status, m.created_at, m.edited_at, m.photo_id, c.display_name
		 FROM comments m LEFT JOIN contacts c ON c.id = m.contact_id
		 WHERE m.story_slug = ? ORDER BY m.created_at, m.id`,
		[storySlug]
	);
	/** @param {import('./db.js').Row} r @returns {PublicComment | null} */
	const shape = (r) => {
		const byOwner = Number(r.by_owner) === 1;
		const mine = byOwner ? !!viewer.isAdmin : !!viewer.readerId && r.contact_id === viewer.readerId;
		const hidden = r.status === 'hidden';
		if (hidden && !viewer.isAdmin && !mine) return null;
		const deleted = r.status === 'deleted';
		const photo = r.photo_id ? String(r.photo_id) : null;
		return {
			id: String(r.id),
			name: byOwner ? viewer.ownerName || 'Author' : r.display_name ? String(r.display_name) : 'Someone no longer here',
			byOwner,
			body: deleted ? '' : String(r.body),
			createdAt: String(r.created_at),
			editedAt: r.edited_at && !deleted ? String(r.edited_at) : null,
			mine: mine && !deleted,
			held: hidden && !viewer.isAdmin,
			hidden: hidden && !!viewer.isAdmin,
			deleted,
			photoId: photo && (!viewer.photoIds || viewer.photoIds.has(photo)) ? photo : null,
			replies: []
		};
	};
	/** @type {Map<string, PublicComment>} */
	const top = new Map();
	for (const r of rows) {
		if (r.parent_id) continue;
		const c = shape(r);
		if (c) top.set(c.id, c);
	}
	for (const r of rows) {
		if (!r.parent_id) continue;
		const parent = top.get(String(r.parent_id));
		// A reply under a comment the owner has hidden is not shown to anyone but the owner.
		if (!parent || (parent.held && !viewer.isAdmin)) continue;
		const c = shape(r);
		if (c && !(c.deleted && !viewer.isAdmin)) parent.replies.push(c);
	}
	return [...top.values()].filter((c) => !c.deleted || c.replies.length > 0 || viewer.isAdmin);
}

/**
 * The owner's moderation list: newest first, across all stories.
 * @param {Db} db @param {number} [limit] @param {string} [ownerName] what the owner's comments are signed with
 */
export async function recentComments(db, limit = 100, ownerName = 'Author') {
	const rows = await db.all(
		`SELECT m.id, m.story_slug, m.by_owner, m.parent_id, m.body, m.status, m.created_at, m.photo_id, c.display_name
		 FROM comments m LEFT JOIN contacts c ON c.id = m.contact_id
		 WHERE m.status <> 'deleted' ORDER BY m.created_at DESC LIMIT ${Math.max(1, Math.min(500, Math.floor(limit)))}`
	);
	return rows.map((r) => ({
		id: String(r.id),
		storySlug: String(r.story_slug),
		name: Number(r.by_owner) === 1 ? ownerName : r.display_name ? String(r.display_name) : 'Someone no longer here',
		isReply: !!r.parent_id,
		body: String(r.body),
		hidden: r.status === 'hidden',
		photoId: r.photo_id ? String(r.photo_id) : null,
		createdAt: String(r.created_at)
	}));
}
