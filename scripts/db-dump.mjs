#!/usr/bin/env node
/**
 * Copy a site's content from one database to another, whichever engines they
 * are on (SQLite or Postgres). One file, two halves:
 *
 *   node scripts/db-dump.mjs export > dump.jsonl
 *   node scripts/db-dump.mjs import dump.jsonl --yes
 *
 * Both use DATABASE_URL / DATA_DIR exactly as the site does. The dump is one
 * JSON object per line: {"table": "...", "row": {...}}.
 *
 * WHAT IS COPIED: stories, old drafts, the image index, the Following list,
 * the contact book, site settings and comments.
 * WHAT IS NOT: anything that lets someone sign in — sessions, sign-in links,
 * passkeys, feed tokens, admin sessions. They would be useless on another
 * host and are better not sitting in a file.
 *
 * `import` REPLACES the copied tables in the target database. It is meant for
 * loading the live site into a development copy (see scripts/pull-prod.sh);
 * it refuses a Postgres target that isn't on this machine unless --force.
 * A SQLite target is backed up beside itself first.
 *
 * The export half is written to run from stdin inside the site container
 * (`docker exec -i … node --input-type=module - export < scripts/db-dump.mjs`),
 * so the live site doesn't need this file deployed.
 */

import { copyFileSync, createReadStream, existsSync } from 'node:fs';
import path from 'node:path';
import { createInterface } from 'node:readline';

/** In insert order: a table comes after the ones it references. */
const TABLES = [
	{ name: 'stories', order: 'slug' },
	{ name: 'drafts', order: 'id' },
	{ name: 'images', order: 'id' },
	{ name: 'following', order: null },
	{ name: 'contacts', order: 'id' },
	{ name: 'site_settings', order: 'key' },
	// Oldest first, so a reply is inserted after the comment it answers.
	{ name: 'comments', order: 'created_at, id' }
];
/** Emptied on import (they reference contacts) but never copied. */
const CREDENTIALS = ['reader_sessions', 'reader_links', 'reader_passkeys', 'reader_tokens', 'reader_challenges'];

const [mode, ...rest] = process.argv.slice(2);
const flags = new Set(rest.filter((a) => a.startsWith('--')));
const file = rest.find((a) => !a.startsWith('--'));

if (mode === 'export') await exportAll();
else if (mode === 'import') await importAll();
else {
	console.error('usage: db-dump.mjs export > dump.jsonl\n       db-dump.mjs import dump.jsonl --yes [--force]');
	process.exit(2);
}

async function exportAll() {
	const db = await openDb();
	const counts = {};
	for (const t of TABLES) {
		let rows;
		try {
			rows = await db.all(`SELECT * FROM ${t.name}${t.order ? ` ORDER BY ${t.order}` : ''}`);
		} catch (e) {
			// An older install may not have a newer table yet.
			console.error(`skipping ${t.name}: ${e.message}`);
			continue;
		}
		counts[t.name] = rows.length;
		for (const row of rows) process.stdout.write(JSON.stringify({ table: t.name, row }) + '\n');
	}
	await db.close();
	console.error('exported: ' + Object.entries(counts).map(([k, v]) => `${k} ${v}`).join(', '));
}

async function importAll() {
	if (!file || !existsSync(file)) fail(`no such dump file: ${file ?? '(none given)'}`);
	if (!flags.has('--yes')) fail('import replaces the stories, comments, contacts and settings in the target database.\nRe-run with --yes if that is what you want.');
	const url = process.env.DATABASE_URL;
	if (url && !flags.has('--force')) {
		const host = new URL(url).hostname;
		if (!['localhost', '127.0.0.1', '::1', '[::1]', ''].includes(host)) {
			fail(`DATABASE_URL points at ${host}, which is not this machine. Refusing to overwrite it (use --force if you really mean it).`);
		}
	}

	const db = await openDb({ backup: true });
	const known = new Set(TABLES.map((t) => t.name));
	const counts = {};
	const skippedColumns = new Set();
	await db.run('BEGIN');
	try {
		for (const t of [...CREDENTIALS, ...TABLES.map((x) => x.name).reverse()]) {
			await db.run(`DELETE FROM ${t}`).catch((e) => console.error(`could not empty ${t}: ${e.message}`));
		}
		const columns = new Map();
		for (const t of TABLES) columns.set(t.name, await db.columns(t.name));

		const lines = createInterface({ input: createReadStream(file), crlfDelay: Infinity });
		for await (const line of lines) {
			if (!line.trim()) continue;
			const { table, row } = JSON.parse(line);
			if (!known.has(table)) continue;
			// Only the columns this database has: the live site may be a
			// migration ahead of, or behind, the code checked out here.
			const have = columns.get(table);
			const cols = Object.keys(row).filter((c) => have.has(c) || (skippedColumns.add(`${table}.${c}`), false));
			await db.run(
				`INSERT INTO ${table} (${cols.join(', ')}) VALUES (${cols.map(() => '?').join(', ')})`,
				cols.map((c) => row[c])
			);
			counts[table] = (counts[table] || 0) + 1;
		}
		await db.run('COMMIT');
	} catch (e) {
		await db.run('ROLLBACK').catch(() => {});
		await db.close();
		fail(`import failed, nothing was changed: ${e.message}`);
	}
	await db.close();
	console.error('imported: ' + Object.entries(counts).map(([k, v]) => `${k} ${v}`).join(', '));
	if (skippedColumns.size) console.error('columns in the dump that this database does not have (skipped): ' + [...skippedColumns].join(', '));
	console.error('Readers will need new sign-in links here: sessions, links and passkeys are not copied.');
}

/** @param {string} msg */
function fail(msg) {
	console.error(msg);
	process.exit(1);
}

/** The same choice the site makes: Postgres if DATABASE_URL is set, else SQLite at DATA_DIR/site.db. */
async function openDb({ backup = false } = {}) {
	const url = process.env.DATABASE_URL;
	if (url) {
		const { default: pg } = await import('pg');
		// One connection, so BEGIN … COMMIT are the same session.
		const client = new pg.Client({ connectionString: url });
		await client.connect();
		const toPg = (/** @type {string} */ sql) => { let i = 0; return sql.replace(/\?/g, () => `$${++i}`); };
		return {
			run: async (sql, p = []) => { await client.query(toPg(sql), p); },
			all: async (sql, p = []) => (await client.query(toPg(sql), p)).rows,
			columns: async (table) =>
				new Set((await client.query('SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = $1', [table])).rows.map((r) => r.column_name)),
			close: () => client.end()
		};
	}
	const { DatabaseSync } = await import('node:sqlite');
	const dbFile = path.join(path.resolve(process.env.DATA_DIR || './data'), 'site.db');
	if (!existsSync(dbFile)) fail(`${dbFile} does not exist — start the site once first so it creates the schema`);
	const sqlite = new DatabaseSync(dbFile);
	sqlite.exec('PRAGMA busy_timeout = 5000');
	if (backup) {
		// Fold the write-ahead log into the file so the copy is complete.
		sqlite.exec('PRAGMA wal_checkpoint(TRUNCATE)');
		const stamp = new Date().toISOString().replace(/[:.]/g, '-');
		const copy = `${dbFile}.before-import-${stamp}`;
		copyFileSync(dbFile, copy);
		console.error(`backed up the current database to ${copy}`);
	}
	return {
		run: async (sql, p = []) => { sqlite.prepare(sql).run(...p.map((x) => (x === undefined ? null : x))); },
		all: async (sql, p = []) => sqlite.prepare(sql).all(...p),
		columns: async (table) => new Set(sqlite.prepare(`PRAGMA table_info(${table})`).all().map((r) => r.name)),
		close: async () => sqlite.close()
	};
}
