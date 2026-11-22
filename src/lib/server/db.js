/**
 * The one place that knows which database we're on.
 *
 *   DATABASE_URL unset        → SQLite via node:sqlite at DATA_DIR/site.db
 *   DATABASE_URL=postgres://… → Postgres via `pg`
 *
 * Both are driven through the same tiny interface:
 *   db.all(sql, params)  → rows
 *   db.get(sql, params)  → first row or undefined
 *   db.run(sql, params)  → { changes }
 *   db.exec(sql)         → run a multi-statement script (migrations)
 *   db.tx(fn)            → run fn(db) inside a transaction
 *
 * SQL is written once, in the shared subset (see db/migrations), with `?`
 * placeholders; the Postgres driver rewrites them to $1, $2… on the way out.
 * Keep literal question marks out of SQL strings.
 *
 * Deliberately not an ORM. The data model is "a JSON document plus a couple
 * of indexed columns", so the whole repository layer is a handful of
 * statements in stories.js / drafts.js / images.js.
 */

import { mkdirSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
// config.js is loaded lazily inside getDb(): it reads SvelteKit's env module,
// and keeping it out of the static imports lets tests open a database with
// plain `node --test` (see tests/readers.test.js).

/**
 * @typedef {Record<string, any>} Row
 * @typedef {{
 *   dialect: 'sqlite' | 'postgres',
 *   all(sql: string, params?: unknown[]): Promise<Row[]>,
 *   get(sql: string, params?: unknown[]): Promise<Row | undefined>,
 *   run(sql: string, params?: unknown[]): Promise<{ changes: number }>,
 *   exec(sql: string): Promise<void>,
 *   tx<T>(fn: (db: Db) => Promise<T>): Promise<T>
 * }} Db
 */

/** @type {Promise<Db> | null} */
let instance = null;

/** The shared connection. Opens (and migrates) on first use. */
export function getDb() {
	if (!instance) {
		instance = import('./config.js')
			.then(({ config }) => (config.databaseUrl ? openPostgres(config.databaseUrl) : openSqlite(config.sqlitePath)))
			.then(async (db) => {
				await migrate(db);
				return db;
			});
		instance.catch(() => {
			instance = null; // let the next request retry instead of caching a failure
		});
	}
	return instance;
}

// ── SQLite ──────────────────────────────────────────────────────────────────

/** @param {string} file @returns {Promise<Db>} */
export async function openSqlite(file) {
	// node:sqlite ships with Node ≥ 22.13 — no native module to build.
	const { DatabaseSync } = await import('node:sqlite');
	mkdirSync(path.dirname(file), { recursive: true });
	const sqlite = new DatabaseSync(file);
	sqlite.exec('PRAGMA journal_mode = WAL');
	sqlite.exec('PRAGMA busy_timeout = 5000');
	sqlite.exec('PRAGMA foreign_keys = ON');

	/** @type {Db} */
	const db = {
		dialect: 'sqlite',
		async all(sql, params = []) {
			return /** @type {Row[]} */ (sqlite.prepare(sql).all(...normalize(params)));
		},
		async get(sql, params = []) {
			return /** @type {Row | undefined} */ (sqlite.prepare(sql).get(...normalize(params)));
		},
		async run(sql, params = []) {
			const r = sqlite.prepare(sql).run(...normalize(params));
			return { changes: Number(r.changes) };
		},
		async exec(sql) {
			sqlite.exec(sql);
		},
		async tx(fn) {
			// One connection, so transactions must not interleave: a second
			// request awaiting inside its own tx would otherwise land inside ours.
			// Chain them through a promise mutex; traffic here is one author.
			const prev = txLock;
			let release = () => {};
			txLock = new Promise((r) => (release = r));
			await prev;
			try {
				sqlite.exec('BEGIN');
				try {
					const out = await fn(db);
					sqlite.exec('COMMIT');
					return out;
				} catch (e) {
					sqlite.exec('ROLLBACK');
					throw e;
				}
			} finally {
				release();
			}
		}
	};
	/** @type {Promise<void>} */
	let txLock = Promise.resolve();
	return db;
}

/** node:sqlite only binds null/number/bigint/string/Uint8Array — coerce the rest. */
/** @returns {(string | number | bigint | null | Uint8Array)[]} */
function normalize(/** @type {unknown[]} */ params) {
	return params.map((p) => {
		if (p === undefined || p === null) return null;
		if (typeof p === 'boolean') return p ? 1 : 0;
		if (p instanceof Date) return p.toISOString();
		if (typeof p === 'string' || typeof p === 'number' || typeof p === 'bigint' || p instanceof Uint8Array) return p;
		return JSON.stringify(p);
	});
}

// ── Postgres ────────────────────────────────────────────────────────────────

/** @param {string} url @returns {Promise<Db>} */
async function openPostgres(url) {
	const { default: pg } = await import('pg');
	const pool = new pg.Pool({ connectionString: url, max: 5 });

	/** @param {import('pg').Pool | import('pg').PoolClient} client @returns {Db} */
	const wrap = (client) => ({
		dialect: 'postgres',
		async all(sql, params = []) {
			return (await client.query(toPg(sql), normalize(params))).rows;
		},
		async get(sql, params = []) {
			return (await client.query(toPg(sql), normalize(params))).rows[0];
		},
		async run(sql, params = []) {
			const r = await client.query(toPg(sql), normalize(params));
			return { changes: r.rowCount ?? 0 };
		},
		async exec(sql) {
			await client.query(sql);
		},
		async tx(fn) {
			const c = await pool.connect();
			try {
				await c.query('BEGIN');
				const out = await fn(wrap(c));
				await c.query('COMMIT');
				return out;
			} catch (e) {
				await c.query('ROLLBACK').catch(() => {});
				throw e;
			} finally {
				c.release();
			}
		}
	});
	return wrap(pool);
}

/** `?` → `$1, $2, …` */
function toPg(/** @type {string} */ sql) {
	let i = 0;
	return sql.replace(/\?/g, () => `$${++i}`);
}

// ── Migrations ──────────────────────────────────────────────────────────────

/**
 * Apply db/migrations/*.sql in name order, once each, recorded in
 * schema_migrations. Same files for both databases, so write them in the
 * shared subset. Runs at startup; a failure stops the server rather than
 * letting it serve against a half-migrated schema.
 * @param {Db} db
 */
export async function migrate(db) {
	await db.exec(
		'CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)'
	);
	const applied = new Set((await db.all('SELECT name FROM schema_migrations')).map((r) => r.name));
	const dir = migrationsDir();
	const files = readdirSync(dir).filter((f) => f.endsWith('.sql')).sort();
	for (const name of files) {
		if (applied.has(name)) continue;
		const sql = readFileSync(path.join(dir, name), 'utf8');
		await db.tx(async (t) => {
			await t.exec(sql);
			await t.run('INSERT INTO schema_migrations (name, applied_at) VALUES (?, ?)', [name, new Date().toISOString()]);
		});
		console.log(`[db] applied migration ${name}`);
	}
}

/** db/migrations next to the project root — in the container, next to build/. */
function migrationsDir() {
	for (const candidate of [path.resolve('db/migrations'), path.resolve(process.cwd(), '../db/migrations')]) {
		try {
			readdirSync(candidate);
			return candidate;
		} catch {
			/* try next */
		}
	}
	throw new Error('db/migrations directory not found (cwd: ' + process.cwd() + ')');
}

// ── Small helpers shared by the repositories ────────────────────────────────

/** Parse a `doc` column. */
export function parseDoc(/** @type {Row | undefined} */ row) {
	return row ? JSON.parse(row.doc) : null;
}

export function nowIso() {
	return new Date().toISOString();
}
