/**
 * Draft store (one row per draft, the Draft document as JSON).
 *
 * A "draft" is raw material captured from the phone app — title, body, tags,
 * location, captured_at, plus a list of media attachments (audio, photo,
 * video). It is NOT a Story. The admin UI promotes a draft into a Story by
 * generating a block sequence from its attachments + body.
 *
 * Media attachments:
 *   - photos go into the image store (images.js); we store the imageId
 *   - audio + video go into the object store under `drafts/<id>/<attachmentId>.<ext>`
 *     (the field is still called `r2Key` for compatibility with the phone app);
 *     they're served back via /api/drafts/<id>/media/<attachmentId>
 *
 * @typedef {Object} BaseAttachment
 * @property {string} id
 * @property {string} createdAt          ISO timestamp
 * @property {string} [caption]
 *
 * @typedef {BaseAttachment & {
 *   kind: 'photo',
 *   imageId: string,
 *   publicUrl?: string,
 *   thumbUrl?: string,
 *   width?: number,
 *   height?: number,
 *   annotationImageId?: string,
 *   annotationPublicUrl?: string,
 *   annotationThumbUrl?: string,
 *   r2?: { base: string, widths: number[], ext: string, w: number, h: number }
 * }} PhotoAttachment   annotation* hold an optional AstroBin-style plate-solved
 *                      star map, uploaded via the media endpoint with
 *                      slot='annotation' and surfaced as a gallery block on promote.
 *                      r2 holds a full-resolution photo stored in R2 (original +
 *                      pre-generated derivatives under gallery/<id>/) instead of
 *                      Cloudflare Images — for astrophotos past the 10 MB cap.
 *                      A photo has imageId OR r2, never both.
 *
 * @typedef {BaseAttachment & {
 *   kind: 'audio',
 *   r2Key: string,
 *   mimeType: string,
 *   durationMs?: number,
 *   transcript?: string,
 *   transcriptModel?: string
 * }} AudioAttachment
 *
 * @typedef {BaseAttachment & {
 *   kind: 'video',
 *   r2Key: string,
 *   mimeType: string,
 *   durationMs?: number,
 *   posterImageId?: string
 * }} VideoAttachment
 *
 * @typedef {PhotoAttachment | AudioAttachment | VideoAttachment} Attachment
 *
 * @typedef {Object} Draft
 * @property {string} id                 client-generated UUID (so offline capture works)
 * @property {string} title
 * @property {string} body
 * @property {string[]} tags
 * @property {string} [location]
 * @property {{ lat: number, lon: number, accuracy?: number }} [coords]
 * @property {import('#lib/types.js').WeatherSnapshot} [weather]   captured alongside coords
 * @property {import('#lib/types.js').AstroSnapshot} [astro]       computed alongside coords
 * @property {string} capturedAt         ISO timestamp — when the thing happened
 * @property {string} createdAt          ISO timestamp — first write to KV
 * @property {string} updatedAt          ISO timestamp — last write
 * @property {Attachment[]} attachments
 * @property {'open'|'promoted'|'archived'} status
 * @property {string} [promotedToSlug]   slug of resulting Story if promoted
 */

import { getDb, nowIso, parseDoc } from './db.js';

/** @returns {Promise<import('./drafts.js').Draft[]>} newest-first by createdAt */
export async function listDrafts() {
	const db = await getDb();
	const rows = await db.all('SELECT doc FROM drafts ORDER BY created_at DESC');
	return rows.map((r) => JSON.parse(r.doc));
}

/**
 * @param {string} id
 * @returns {Promise<import('./drafts.js').Draft | null>}
 */
export async function getDraft(id) {
	const db = await getDb();
	return parseDoc(await db.get('SELECT doc FROM drafts WHERE id = ?', [id]));
}

/**
 * Create or update a draft.
 * @param {import('./drafts.js').Draft} draft
 */
export async function putDraft(draft) {
	const db = await getDb();
	const now = nowIso();
	const existing = await getDraft(draft.id);
	const toSave = {
		...draft,
		createdAt: existing?.createdAt || draft.createdAt || now,
		updatedAt: now,
		status: draft.status || existing?.status || 'open',
		tags: Array.isArray(draft.tags) ? draft.tags : [],
		attachments: Array.isArray(draft.attachments) ? draft.attachments : []
	};
	await db.run(
		`INSERT INTO drafts (id, status, created_at, updated_at, doc) VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT (id) DO UPDATE SET status = excluded.status, created_at = excluded.created_at,
		   updated_at = excluded.updated_at, doc = excluded.doc`,
		[toSave.id, toSave.status, toSave.createdAt, now, JSON.stringify(toSave)]
	);
	return toSave;
}

/** @param {string} id */
export async function deleteDraft(id) {
	const db = await getDb();
	await db.run('DELETE FROM drafts WHERE id = ?', [id]);
}

/**
 * Append an attachment to a draft (replacing one with the same id). The draft must exist.
 * @param {string} draftId
 * @param {import('./drafts.js').Attachment} attachment
 */
export async function addAttachment(draftId, attachment) {
	const draft = await getDraft(draftId);
	if (!draft) throw new Error(`Draft ${draftId} not found`);
	return putDraft({
		...draft,
		attachments: [...draft.attachments.filter((a) => a.id !== attachment.id), attachment]
	});
}

/**
 * Update an existing attachment in place (e.g. to add a transcript).
 * @param {string} draftId
 * @param {string} attachmentId
 * @param {Partial<import('./drafts.js').Attachment>} patch
 */
export async function patchAttachment(draftId, attachmentId, patch) {
	const draft = await getDraft(draftId);
	if (!draft) throw new Error(`Draft ${draftId} not found`);
	const attachments = draft.attachments.map((a) =>
		a.id === attachmentId ? /** @type {any} */ ({ ...a, ...patch }) : a
	);
	return putDraft({ ...draft, attachments });
}
