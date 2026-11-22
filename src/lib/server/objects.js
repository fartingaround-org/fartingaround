/**
 * Object store: every stored file, as plain files under DATA_DIR/objects/<key>.
 *
 * Replaces the R2 bucket with the same four verbs, so the audio/video and
 * full-res-photo code paths kept their shape. Keys look like
 * `drafts/<draftId>/<attachmentId>.m4a` or `images/<id>/orig.jpg`; they are
 * validated so a key can never escape the objects directory.
 */

import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, rename, rm, stat, unlink } from 'node:fs/promises';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { config } from './config.js';

/** Resolve a key to an absolute path, refusing traversal. */
export function objectPath(/** @type {string} */ key) {
	if (!/^[A-Za-z0-9][A-Za-z0-9._\-\/=,]*$/.test(key) || key.includes('..') || key.includes('//')) {
		throw new Error(`Invalid object key: ${key}`);
	}
	const file = path.resolve(config.objectsDir, key);
	if (!file.startsWith(config.objectsDir + path.sep)) throw new Error(`Invalid object key: ${key}`);
	return file;
}

/**
 * Write an object. Accepts bytes, a Blob/File, a web ReadableStream (a request
 * body) or a Node Readable. Written to a temp name then renamed, so a
 * half-uploaded file is never visible under its real key.
 * @param {string} key
 * @param {ArrayBuffer | Uint8Array | Blob | ReadableStream | Readable} body
 * @returns {Promise<{ key: string, size: number }>}
 */
export async function putObject(key, body) {
	const file = objectPath(key);
	await mkdir(path.dirname(file), { recursive: true });
	const tmp = `${file}.${process.pid}.${Date.now()}.part`;
	/** @type {Readable} */
	let source;
	if (body instanceof Readable) source = body;
	else if (body instanceof Blob) source = Readable.fromWeb(/** @type {any} */ (body.stream()));
	else if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) source = Readable.fromWeb(/** @type {any} */ (body));
	else source = Readable.from([body instanceof ArrayBuffer ? new Uint8Array(body) : body]);
	try {
		await pipeline(source, createWriteStream(tmp));
		await rename(tmp, file);
	} catch (e) {
		await unlink(tmp).catch(() => {});
		throw e;
	}
	const { size } = await stat(file);
	return { key, size };
}

/**
 * Metadata for an object, or null when it doesn't exist.
 * @param {string} key
 * @returns {Promise<{ key: string, size: number, mtime: Date } | null>}
 */
export async function headObject(key) {
	try {
		const s = await stat(objectPath(key));
		return s.isFile() ? { key, size: s.size, mtime: s.mtime } : null;
	} catch {
		return null;
	}
}

/**
 * A readable stream of an object, optionally a byte range (inclusive end), or
 * null when it doesn't exist.
 * @param {string} key
 * @param {{ start?: number, end?: number }} [range]
 */
export async function getObjectStream(key, range) {
	const head = await headObject(key);
	if (!head) return null;
	return { ...head, stream: createReadStream(objectPath(key), range) };
}

/** Delete one object. Missing is fine. */
export async function deleteObject(/** @type {string} */ key) {
	await unlink(objectPath(key)).catch(() => {});
}

/** Delete everything under a prefix (an image's folder, a draft's media). */
export async function deletePrefix(/** @type {string} */ prefix) {
	const dir = objectPath(prefix.replace(/\/$/, ''));
	await rm(dir, { recursive: true, force: true });
}

// ── Key conventions (unchanged from the R2 days) ────────────────────────────

/**
 * @param {string} draftId
 * @param {string} attachmentId
 * @param {string} ext  without leading dot
 */
export function draftMediaKey(draftId, attachmentId, ext) {
	const safe = ext.replace(/^\.+/, '').replace(/[^a-z0-9]/gi, '').toLowerCase() || 'bin';
	return `drafts/${draftId}/${attachmentId}.${safe}`;
}

/**
 * Key for an audio/video clip that belongs to a story block (or a dump
 * photo's spoken caption): `audio/<id>.<ext>` / `video/<id>.<ext>`. The id is
 * also the public playback id under /api/stories/:slug/{audio,video}/:id.
 * @param {'audio' | 'video'} kind
 * @param {string} id
 * @param {string} ext  without leading dot
 */
export function storyMediaKey(kind, id, ext) {
	const safe = ext.replace(/^\.+/, '').replace(/[^a-z0-9]/gi, '').toLowerCase() || 'bin';
	if (!/^[A-Za-z0-9_-]{6,}$/.test(id)) throw new Error(`Invalid media id: ${id}`);
	return `${kind}/${id}.${safe}`;
}

/** Best-effort extension from a MIME type. */
export function extFromMime(/** @type {string} */ mime) {
	const m = (mime || '').toLowerCase();
	if (m.includes('audio/webm')) return 'webm';
	if (m.includes('audio/mp4')) return 'm4a';
	if (m.includes('audio/mpeg')) return 'mp3';
	if (m.includes('audio/ogg')) return 'ogg';
	if (m.includes('audio/wav')) return 'wav';
	if (m.includes('video/webm')) return 'webm';
	if (m.includes('video/mp4')) return 'mp4';
	if (m.includes('video/quicktime')) return 'mov';
	if (m.includes('image/jpeg')) return 'jpg';
	if (m.includes('image/png')) return 'png';
	if (m.includes('image/webp')) return 'webp';
	if (m.includes('image/avif')) return 'avif';
	if (m.includes('image/heic')) return 'heic';
	return 'bin';
}

/** MIME type from a key's extension, for serving. */
export function mimeFromKey(/** @type {string} */ key) {
	const ext = key.split('.').pop()?.toLowerCase() || '';
	return (
		{
			jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp', avif: 'image/avif', gif: 'image/gif', heic: 'image/heic',
			m4a: 'audio/mp4', mp3: 'audio/mpeg', ogg: 'audio/ogg', wav: 'audio/wav',
			mp4: 'video/mp4', mov: 'video/quicktime', webm: 'video/webm'
		}[ext] || 'application/octet-stream'
	);
}
