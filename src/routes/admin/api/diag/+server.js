import { error, json } from '@sveltejs/kit';
import { config } from '#lib/server/config.js';
import { getDb } from '#lib/server/db.js';
import { access, statfs } from 'node:fs/promises';

/**
 * "What does the server see?" — database, data directory, which secrets are
 * set (masked), free disk. Admin only.
 */
/** @type {import('./$types').RequestHandler} */
export const GET = async ({ locals }) => {
	if (!locals.isAdmin) throw error(401, 'Unauthorized');

	const mask = (/** @type {string} */ v) => (v ? `${v.slice(0, 4)}…${v.slice(-4)} (${v.length} chars)` : '(missing)');

	/** @type {Record<string, unknown>} */
	const result = {
		database: config.databaseUrl ? 'postgres' : `sqlite (${config.sqlitePath})`,
		dataDir: config.dataDir,
		secrets: {
			ADMIN_PASSWORD_SET: Boolean(config.adminPassword && config.adminPassword !== 'change-me'),
			CAPTURE_API_TOKEN: mask(config.captureApiToken),
			UPLOAD_SECRET: config.uploadSecret ? 'set' : '(missing)'
		}
	};

	try {
		const db = await getDb();
		const counts = {
			stories: (await db.get('SELECT COUNT(*) AS n FROM stories'))?.n,
			drafts: (await db.get('SELECT COUNT(*) AS n FROM drafts'))?.n,
			images: (await db.get('SELECT COUNT(*) AS n FROM images'))?.n,
			migrations: (await db.all('SELECT name FROM schema_migrations ORDER BY name')).map((r) => r.name)
		};
		result.db = { ok: true, ...counts };
	} catch (e) {
		result.db = { ok: false, error: e instanceof Error ? e.message : String(e) };
	}

	try {
		await access(config.objectsDir);
		const fs = await statfs(config.dataDir);
		result.disk = { freeGB: Math.round((fs.bavail * fs.bsize) / 1e9), totalGB: Math.round((fs.blocks * fs.bsize) / 1e9) };
		result.objectsDirWritable = true;
	} catch (e) {
		result.objectsDirWritable = false;
		result.objectsDirError = e instanceof Error ? e.message : String(e);
	}

	try {
		const { default: sharp } = await import('sharp');
		result.sharp = sharp.versions;
	} catch (e) {
		result.sharp = { error: e instanceof Error ? e.message : String(e) };
	}

	return json(result);
};
