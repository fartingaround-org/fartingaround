import {
	ADMIN_PASSWORD,
	CAPTURE_API_TOKEN,
	DATABASE_URL,
	DATA_DIR,
	ELEVENLABS_API_KEY,
	ELEVENLABS_MODEL,
	UPLOAD_ORIGIN,
	UPLOAD_SECRET
} from '$app/env/private';
import { createHash } from 'node:crypto';
import path from 'node:path';

/**
 * Runtime configuration, resolved once. Paths are absolute so the rest of the
 * server never has to think about the working directory.
 */
export const config = Object.freeze({
	dataDir: path.resolve(DATA_DIR || './data'),
	/** DATA_DIR/objects — every stored file (originals, derivatives, audio, video). */
	objectsDir: path.resolve(DATA_DIR || './data', 'objects'),
	/** DATA_DIR/site.db — only used when DATABASE_URL is unset. */
	sqlitePath: path.resolve(DATA_DIR || './data', 'site.db'),
	databaseUrl: DATABASE_URL || '',
	adminPassword: ADMIN_PASSWORD || '',
	captureApiToken: CAPTURE_API_TOKEN || '',
	uploadSecret:
		UPLOAD_SECRET || createHash('sha256').update(`upload:${ADMIN_PASSWORD || ''}`).digest('hex'),
	/** Origin for signed upload URLs; empty means "same as the request". */
	uploadOrigin: (UPLOAD_ORIGIN || '').replace(/\/+$/, ''),
	/** Empty → transcription is disabled in the admin. */
	elevenLabsApiKey: ELEVENLABS_API_KEY || '',
	elevenLabsModel: ELEVENLABS_MODEL || 'scribe_v1'
});
