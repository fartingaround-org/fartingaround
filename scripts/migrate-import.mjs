#!/usr/bin/env node
/**
 * Stage 2 of the move off Cloudflare: load an export folder into this install.
 *
 * Run it INSIDE the site container, which has the database, the data volume
 * and sharp:
 *
 *   # on the droplet, after copying the export folder to ./data/import
 *   docker compose exec site node scripts/migrate-import.mjs /data/import
 *
 * Uses the container's own DATA_DIR / DATABASE_URL, so it writes to whichever
 * database the site is running on (SQLite or Postgres). The schema must
 * already exist — it does, because the running server applies migrations at
 * startup.
 *
 * What it does:
 *   1. stories + drafts → upserted into the tables, with any
 *      imagedelivery.net URLs inside the documents rewritten to this site's
 *      /img/<id>/… (image ids are kept, so blocks need no other change)
 *   2. Cloudflare Images originals → objects/images/<id>/orig.<ext> + an
 *      `images` row with dimensions read by sharp
 *   3. R2 objects → objects/<key> (gallery photos, draft audio/video); keys
 *      are unchanged, so r2Key fields on blocks/attachments still resolve
 *   4. a report: counts, and any image id a story references that we don't have
 *
 * Re-runnable; existing rows are replaced, existing files left alone unless
 * --force. Pass --origin https://your.domain if the site isn't fartingaround.fun.
 */

import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const src = path.resolve(args.find((a) => !a.startsWith('--')) || '/data/import');
const force = args.includes('--force');
const originArg = args.indexOf('--origin');
const origin = (originArg >= 0 ? args[originArg + 1] : 'https://fartingaround.fun').replace(/\/+$/, '');

const dataDir = path.resolve(process.env.DATA_DIR || './data');
const objectsDir = path.join(dataDir, 'objects');
const db = await openDb();

if (!existsSync(path.join(src, 'manifest.json'))) {
	console.error(`no manifest.json in ${src} — is that the export folder?`);
	process.exit(1);
}
const manifest = JSON.parse(readFileSync(path.join(src, 'manifest.json'), 'utf8'));
console.log(`import from ${src} (exported ${manifest.exportedAt}) into ${process.env.DATABASE_URL ? 'postgres' : 'sqlite'} + ${objectsDir}`);

const now = new Date().toISOString();
const rewrite = (text) => text.replace(/https:\/\/imagedelivery\.net\/[A-Za-z0-9_-]+\/([A-Za-z0-9_-]+)\/([A-Za-z0-9_=,.%-]+)/g, (_, id, variant) => `${origin}/img/${id}/${variant}`);

// ── 1. stories + drafts ─────────────────────────────────────────────────────
let nStories = 0;
const referencedImages = new Set();
const referencedKeys = new Set(); // exact object keys (r2Key)
const referencedBases = new Set(); // gallery/<id> prefixes that need an orig.*
for (const f of listJson(path.join(src, 'kv/stories'))) {
	const doc = JSON.parse(rewrite(readFileSync(f, 'utf8')));
	if (!doc.slug) { console.warn(`skip ${f}: no slug`); continue; }
	await db.run(
		`INSERT INTO stories (slug, date, draft, updated_at, doc) VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT (slug) DO UPDATE SET date = excluded.date, draft = excluded.draft, updated_at = excluded.updated_at, doc = excluded.doc`,
		[doc.slug, doc.date || '', doc.draft ? 1 : 0, doc.updatedAt || now, JSON.stringify(doc)]
	);
	collectRefs(doc);
	nStories++;
}
let nDrafts = 0;
for (const f of listJson(path.join(src, 'kv/drafts'))) {
	const doc = JSON.parse(rewrite(readFileSync(f, 'utf8')));
	if (!doc.id) { console.warn(`skip ${f}: no id`); continue; }
	await db.run(
		`INSERT INTO drafts (id, status, created_at, updated_at, doc) VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT (id) DO UPDATE SET status = excluded.status, created_at = excluded.created_at, updated_at = excluded.updated_at, doc = excluded.doc`,
		[doc.id, doc.status || 'open', doc.createdAt || now, doc.updatedAt || now, JSON.stringify(doc)]
	);
	collectRefs(doc);
	nDrafts++;
}
console.log(`stories: ${nStories}, drafts: ${nDrafts}`);

// ── 2. images ───────────────────────────────────────────────────────────────
const sharp = await import('sharp').then((m) => m.default || m).catch(() => null);
if (!sharp) console.warn('sharp not available — image dimensions will be left null');
const index = JSON.parse(readFileSync(path.join(src, 'images/index.json'), 'utf8'));
let nImages = 0;
for (const img of index) {
	const from = path.join(src, 'images', img.id, `orig.${img.ext}`);
	if (!existsSync(from)) { console.warn(`missing file for image ${img.id}`); continue; }
	const key = `images/${img.id}/orig.${img.ext}`;
	const to = path.join(objectsDir, key);
	copyIfNeeded(from, to);
	let width = null, height = null;
	if (sharp) {
		try {
			const meta = await sharp(to).rotate().metadata();
			const swapped = (meta.orientation || 1) >= 5;
			width = (swapped ? meta.height : meta.width) ?? null;
			height = (swapped ? meta.width : meta.height) ?? null;
		} catch { /* unreadable by sharp; keep nulls */ }
	}
	await db.run(
		`INSERT INTO images (id, ext, mime, width, height, bytes, filename, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (id) DO UPDATE SET ext = excluded.ext, mime = excluded.mime, width = excluded.width, height = excluded.height, bytes = excluded.bytes, filename = excluded.filename`,
		[img.id, img.ext, img.mime || 'image/jpeg', width, height, statSync(to).size, img.filename, img.uploaded || now]
	);
	nImages++;
}
console.log(`images: ${nImages}`);

// ── 3. objects ──────────────────────────────────────────────────────────────
let nObjects = 0;
const objRoot = path.join(src, 'objects');
if (existsSync(objRoot)) {
	for (const rel of walk(objRoot)) {
		copyIfNeeded(path.join(objRoot, rel), path.join(objectsDir, rel));
		nObjects++;
	}
}
console.log(`objects: ${nObjects}`);

// ── 4. report ───────────────────────────────────────────────────────────────
const haveImages = new Set((await db.all('SELECT id FROM images')).map((r) => r.id));
const missingImages = [...referencedImages].filter((id) => !haveImages.has(id));
const missingKeys = [...referencedKeys].filter((k) => !existsSync(path.join(objectsDir, k)));
const missingBases = [...referencedBases].filter((b) => !hasOriginal(b));
console.log(`\nreferenced image ids: ${referencedImages.size} (${missingImages.length} missing)`);
for (const id of missingImages) console.log(`  missing image: ${id}`);
console.log(`referenced object keys: ${referencedKeys.size} (${missingKeys.length} missing)`);
for (const k of missingKeys) console.log(`  missing object: ${k}`);
console.log(`referenced gallery originals: ${referencedBases.size} (${missingBases.length} missing)`);
for (const b of missingBases) console.log(`  missing original under: ${b}/`);
const gaps = missingImages.length + missingKeys.length + missingBases.length;
console.log(gaps ? `\ndone, with ${gaps} gaps listed above` : '\ndone — everything referenced is present');
await db.close();

// ── helpers ─────────────────────────────────────────────────────────────────

/** Walk a document and note every image id, object key and gallery base it points at. */
function collectRefs(doc) {
	const visit = (v, k) => {
		if (Array.isArray(v)) return v.forEach((x) => visit(x, k));
		if (v && typeof v === 'object') return Object.entries(v).forEach(([kk, vv]) => visit(vv, kk));
		if (typeof v !== 'string' || !v) return;
		if (/^(imageId|coverImageId|annotationId|overlayId|starlessId|annotationImageId|posterImageId)$/.test(k)) referencedImages.add(v);
		if (k === 'r2Key') referencedKeys.add(v);
		if (k === 'base' && v.startsWith('gallery/')) referencedBases.add(v);
	};
	visit(doc, '');
}
function hasOriginal(base) {
	const dir = path.join(objectsDir, base);
	return existsSync(dir) && readdirSync(dir).some((f) => f.startsWith('orig.'));
}
function listJson(dir) {
	return existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => path.join(dir, f)) : [];
}
function* walk(root, rel = '') {
	for (const name of readdirSync(path.join(root, rel))) {
		const r = rel ? `${rel}/${name}` : name;
		if (statSync(path.join(root, r)).isDirectory()) yield* walk(root, r);
		else yield r;
	}
}
function copyIfNeeded(from, to) {
	if (!force && existsSync(to)) return;
	mkdirSync(path.dirname(to), { recursive: true });
	copyFileSync(from, to);
}

/** Minimal db access — the same two drivers and `?` placeholders as src/lib/server/db.js. */
async function openDb() {
	const url = process.env.DATABASE_URL;
	if (url) {
		const { default: pg } = await import('pg');
		const pool = new pg.Pool({ connectionString: url, max: 2 });
		const toPg = (sql) => { let i = 0; return sql.replace(/\?/g, () => `$${++i}`); };
		return {
			run: async (sql, p = []) => { await pool.query(toPg(sql), p); },
			all: async (sql, p = []) => (await pool.query(toPg(sql), p)).rows,
			close: () => pool.end()
		};
	}
	const { DatabaseSync } = await import('node:sqlite');
	const file = path.join(dataDir, 'site.db');
	if (!existsSync(file)) { console.error(`${file} does not exist — start the site once first so it creates the schema`); process.exit(1); }
	const sqlite = new DatabaseSync(file);
	sqlite.exec('PRAGMA busy_timeout = 5000');
	return {
		run: async (sql, p = []) => { sqlite.prepare(sql).run(...p.map((x) => (x === undefined ? null : x))); },
		all: async (sql, p = []) => sqlite.prepare(sql).all(...p),
		close: async () => sqlite.close()
	};
}
