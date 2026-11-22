/**
 * Step 2 of a large upload: check the bytes landed.
 *
 *   POST /api/v1/media/confirm  { key }  or  { keys: [...] }
 *   → { objects: [{ key, size }] }      409 object_not_found if any is missing
 *
 * Only keys this API hands out are accepted (audio/, video/, gallery/).
 * Confirming doesn't register anything — the block you then PUT in the
 * story is the registration. This just saves you from saving a story that
 * points at a half-uploaded file.
 */
import { headObject } from '#lib/server/objects.js';
import { fail, isAuthor, preflight, readJson, reply } from '#lib/server/api-v1.js';

const KEY_RE = /^(?:(?:audio|video)\/[A-Za-z0-9_-]{6,64}\.[a-z0-9]{1,5}|gallery\/[A-Za-z0-9_-]{6,64}\/(?:orig\.[a-z0-9]{1,5}|w\d{1,4}\.jpg))$/;

/** @type {import('./$types').RequestHandler} */
export const OPTIONS = async ({ request }) => preflight(request);

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, locals }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	const body = await readJson(request);
	/** @type {string[]} */
	const keys = Array.isArray(body.keys) ? body.keys : typeof body.key === 'string' ? [body.key] : [];
	if (!keys.length) return fail(request, 400, 'missing_key');
	const bad = keys.find((k) => typeof k !== 'string' || !KEY_RE.test(k));
	if (bad !== undefined) return fail(request, 400, 'bad_key', { key: bad });

	const heads = await Promise.all(keys.map((k) => headObject(k)));
	const missing = keys.filter((_, i) => !heads[i]);
	if (missing.length) return fail(request, 409, 'object_not_found', { missing });
	return reply(request, { objects: heads.map((h) => ({ key: /** @type {any} */ (h).key, size: /** @type {any} */ (h).size })) });
};
