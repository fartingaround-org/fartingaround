/**
 * Site settings an authoring client may change. Owner only (admin cookie or
 * the bearer token).
 *
 *   GET /api/v1/settings   → { authorName }
 *   PUT /api/v1/settings   { authorName }   → { authorName }
 *
 * `authorName` is what the owner's comments are signed with. It is '' while
 * none has been chosen (comments then read "Author"); send '' to go back to
 * that. Other settings stay in /admin/settings for now.
 */
import { authorName, setAuthorName } from '#lib/server/settings.js';
import { fail, isAuthor, preflight, readJson, reply } from '#lib/server/api-v1.js';

const current = async () => {
	const name = await authorName();
	return { authorName: name === 'Author' ? '' : name };
};

/** @type {import('./$types').RequestHandler} */
export const OPTIONS = async ({ request }) => preflight(request);

/** @type {import('./$types').RequestHandler} */
export const GET = async ({ request, locals }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	return reply(request, await current(), 200, { 'cache-control': 'private, no-store' });
};

/** @type {import('./$types').RequestHandler} */
export const PUT = async ({ request, locals }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	const body = await readJson(request);
	if (typeof body.authorName !== 'string') return fail(request, 400, 'bad_author_name', { message: 'authorName must be text.' });
	if (body.authorName.trim().length > 80) return fail(request, 400, 'bad_author_name', { message: 'Keep the name under 80 characters.' });
	await setAuthorName(body.authorName);
	return reply(request, await current(), 200, { 'cache-control': 'private, no-store' });
};
