#!/usr/bin/env node
/**
 * Drafts → stories. One-time step for decisions/create-mode-unified-drafts:
 * every phone-captured Draft becomes a Story with `draft: true`, so there is
 * one document type that both the phone and /admin edit. After this the
 * drafts table and /api/drafts* can go.
 *
 * Run it INSIDE the site container (it has the database and DATA_DIR):
 *
 *   docker compose exec site node scripts/migrate-drafts-to-stories.mjs            # dry run: prints the plan
 *   docker compose exec site node scripts/migrate-drafts-to-stories.mjs --apply    # writes stories
 *   docker compose exec site node scripts/migrate-drafts-to-stories.mjs --apply --archive
 *                                                             # …and marks each draft status=migrated
 *
 * Locally (pnpm dev, SQLite): DATA_DIR=./data node scripts/migrate-drafts-to-stories.mjs
 *
 * What it does, per draft:
 *   - status 'promoted' with a promotedToSlug that exists → skipped (already a story)
 *   - status 'archived' → skipped unless --include-archived
 *   - otherwise builds blocks the way promote did, with one change: 3+ ordinary
 *     photos become ONE `dump` block (w/h from the images table, caption kept),
 *     1–2 photos stay `photo` blocks, astro photos (r2 / star map) stay
 *     single-item galleries, audio → `audio` block (transcript folded in),
 *     video → `video` block, body → leading `text` block.
 *   - slug from the title (de-duplicated against existing stories), date from
 *     capturedAt, location/coords/weather/astro/tags carried over, cover = first photo
 *   - a `migratedFromDraft: <id>` marker on the story makes re-runs idempotent:
 *     a story already carrying the id is left alone.
 *
 * Nothing is deleted. Media keys (imageId, r2Key) are reused as-is.
 */

import { existsSync } from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';

const args = process.argv.slice(2);
const apply = args.includes('--apply');
const archive = args.includes('--archive');
const includeArchived = args.includes('--include-archived');

const dataDir = path.resolve(process.env.DATA_DIR || './data');
const db = await openDb();

const now = new Date().toISOString();
const existingStories = (await db.all('SELECT doc FROM stories')).map((r) => JSON.parse(r.doc));
const bySlug = new Map(existingStories.map((s) => [s.slug, s]));
const migrated = new Set(existingStories.map((s) => s.migratedFromDraft).filter(Boolean));
const drafts = (await db.all('SELECT doc FROM drafts ORDER BY created_at ASC')).map((r) => JSON.parse(r.doc));

console.log(`${drafts.length} drafts, ${existingStories.length} stories (${existingStories.filter((s) => s.draft).length} draft) in ${process.env.DATABASE_URL ? 'postgres' : 'sqlite'}${apply ? '' : '  — DRY RUN, pass --apply to write'}`);

let created = 0, skipped = 0;
for (const d of drafts) {
	const tag = `${d.id.slice(0, 8)}  ${(d.title || '(untitled)').slice(0, 40).padEnd(40)}`;
	if (migrated.has(d.id)) { console.log(`  = ${tag} already migrated`); skipped++; continue; }
	if (d.status === 'promoted' && d.promotedToSlug && bySlug.has(d.promotedToSlug)) {
		console.log(`  = ${tag} promoted → /stories/${d.promotedToSlug}`); skipped++; continue;
	}
	if (d.status === 'archived' && !includeArchived) { console.log(`  - ${tag} archived (use --include-archived)`); skipped++; continue; }

	const story = await buildStory(d);
	console.log(`  + ${tag} → ${story.slug}  [${story.blocks.map((b) => b.type).join(', ') || 'no blocks'}]`);
	if (apply) {
		await db.run(
			`INSERT INTO stories (slug, date, draft, updated_at, doc) VALUES (?, ?, ?, ?, ?)
			 ON CONFLICT (slug) DO UPDATE SET date = excluded.date, draft = excluded.draft, updated_at = excluded.updated_at, doc = excluded.doc`,
			[story.slug, story.date, 1, now, JSON.stringify(story)]
		);
		bySlug.set(story.slug, story);
		if (archive) {
			const doc = { ...d, status: 'migrated', promotedToSlug: story.slug, updatedAt: now };
			await db.run('UPDATE drafts SET status = ?, updated_at = ?, doc = ? WHERE id = ?', ['migrated', now, JSON.stringify(doc), d.id]);
		}
	}
	created++;
}
console.log(`\n${apply ? 'created' : 'would create'} ${created}, skipped ${skipped}`);
await db.close();

// ── building ───────────────────────────────────────────────────────────────

async function buildStory(d) {
	const blocks = [];
	if (d.body && d.body.trim()) blocks.push({ type: 'text', id: bid('text'), body: d.body.trim() });

	const atts = Array.isArray(d.attachments) ? d.attachments : [];
	const plainPhotos = atts.filter((a) => a.kind === 'photo' && a.imageId && !a.r2 && !a.annotationImageId);
	const asDump = plainPhotos.length >= 3;

	if (asDump) {
		const photos = [];
		for (const a of plainPhotos) {
			const dims = (a.width && a.height) ? { w: a.width, h: a.height } : await imageDims(a.imageId);
			photos.push({ imageId: a.imageId, ...(dims || {}), ...(a.caption ? { caption: a.caption } : {}) });
		}
		blocks.push({ type: 'dump', id: bid('dump'), photos, size: 'wide', rowSize: 'm', map: 'none' });
	}

	for (const a of atts) {
		if (a.kind === 'photo') {
			if (a.r2 || a.annotationImageId) {
				const item = { ...(a.caption ? { caption: a.caption, alt: a.caption } : {}) };
				if (a.r2) item.r2 = a.r2; else item.imageId = a.imageId;
				if (a.annotationImageId) item.annotationId = a.annotationImageId;
				blocks.push({ type: 'gallery', id: bid('gallery'), items: [item], size: 'wide', aspect: '3/2' });
			} else if (!asDump && a.imageId) {
				blocks.push({ type: 'photo', id: bid('photo'), imageId: a.imageId, ...(a.caption ? { caption: a.caption } : {}) });
			}
		} else if (a.kind === 'audio' && a.r2Key) {
			blocks.push(strip({ type: 'audio', id: a.id, r2Key: a.r2Key, mimeType: a.mimeType, durationMs: a.durationMs, caption: a.caption, transcript: a.transcript, transcriptModel: a.transcriptModel }));
		} else if (a.kind === 'video' && a.r2Key) {
			blocks.push(strip({ type: 'video', id: a.id, r2Key: a.r2Key, mimeType: a.mimeType, durationMs: a.durationMs, caption: a.caption, posterImageId: a.posterImageId, size: 'wide' }));
		}
	}

	const base = toSlug(d.title || '') || `draft-${(d.capturedAt || d.createdAt || now).slice(0, 10)}`;
	const slug = uniqueSlug(base);
	const cover = atts.find((a) => a.kind === 'photo' && a.imageId)?.imageId;

	return strip({
		slug,
		title: d.title || 'Untitled',
		date: (d.capturedAt || d.createdAt || now).slice(0, 10),
		location: d.location || '',
		coords: d.coords,
		weather: d.weather,
		astro: d.astro,
		summary: '',
		coverImageId: cover || '',
		tags: Array.isArray(d.tags) ? d.tags : [],
		blocks,
		draft: true,
		createdAt: d.createdAt || now,
		updatedAt: now,
		migratedFromDraft: d.id
	});
}

async function imageDims(id) {
	const rows = await db.all('SELECT width, height FROM images WHERE id = ?', [id]);
	const r = rows[0];
	return r && r.width && r.height ? { w: Number(r.width), h: Number(r.height) } : null;
}
function uniqueSlug(base) {
	let slug = base; let i = 2;
	while (bySlug.has(slug)) slug = `${base}-${i++}`;
	return slug;
}
function toSlug(input) {
	return input.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80);
}
function bid(type) { return `${type.slice(0, 2)}-${randomBytes(5).toString('base64url')}`; }
function strip(o) { return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)); }

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
