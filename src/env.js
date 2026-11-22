import { defineEnvVars } from '@sveltejs/kit/env';

/**
 * Explicit environment variables (SvelteKit 3). All runtime config for the
 * self-hosted server lives here; see .env.example for what each one means.
 * Everything is dynamic (read when the container starts), so one image serves
 * every install.
 */
const optional = (/** @type {string | undefined} */ v) => v ?? '';

export const variables = defineEnvVars({
	/** Where the database file (SQLite mode) and all media live. The Docker volume. */
	DATA_DIR: { schema: (v) => v || './data', description: 'Data directory: site.db + media/. Default ./data (the container mounts /data).' },
	/** Unset → SQLite at DATA_DIR/site.db. postgres://… → Postgres. */
	DATABASE_URL: { schema: optional, description: 'Optional. postgres://user:pw@host:5432/db to use Postgres instead of SQLite.' },
	// Optional here so the Docker image builds without any config; the server
	// refuses to START without it (src/hooks.server.js init).
	ADMIN_PASSWORD: { schema: optional, description: 'Password for /admin. Required at runtime.' },
	CAPTURE_API_TOKEN: { schema: optional, description: 'Bearer token for the phone app (/api/drafts*). openssl rand -hex 32' },
	/** Signs the short-lived PUT URLs handed to the phone app for large uploads. */
	UPLOAD_SECRET: { schema: optional, description: 'Optional. HMAC secret for upload URLs; derived from ADMIN_PASSWORD when unset.' },
	/**
	 * Where signed upload URLs point. Set this to a DNS-only hostname when the
	 * site itself sits behind Cloudflare's proxy, whose 100 MB body limit would
	 * otherwise reject big video PUTs. Defaults to the request's own origin.
	 */
	UPLOAD_ORIGIN: { schema: optional, description: 'Optional. e.g. https://upload.example.org — host for large uploads, bypassing a proxy body limit.' },
	/** Enables the Transcribe button in the admin (audio captions on dump photos). */
	ELEVENLABS_API_KEY: { schema: optional, description: 'Optional. ElevenLabs API key; enables transcription of audio captions in /admin.' },
	ELEVENLABS_MODEL: { schema: (v) => v || 'scribe_v1', description: 'Optional. ElevenLabs speech-to-text model id. Default scribe_v1.' }
});
