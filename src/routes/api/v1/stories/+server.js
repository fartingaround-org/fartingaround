/**
 * Stories, for authoring clients.
 *
 *   GET  /api/v1/stories[?draft=1|0]   → { stories: [summary…] }  newest first
 *   POST /api/v1/stories               → 201 { story }
 *        body: a Story without `slug` (one is made from the title, or from the date when there is no title, and
 *        de-duplicated), or with a `slug` the server must honour (409 if
 *        taken). `draft` defaults to true: the phone creates drafts.
 *
 * Auth: admin cookie or bearer token. Both endpoints include drafts — this is
 * the author's view; visitors use /api/stories and the feeds.
 */
import { listStories, getStory, putStory, summarizeStory, toSlug, uniqueSlug } from '#lib/server/stories.js';
import { normalizeStory } from '#lib/server/story-validate.js';
import { fail, isAuthor, preflight, readJson, reply } from '#lib/server/api-v1.js';

/** @type {import('./$types').RequestHandler} */
export const OPTIONS = async ({ request }) => preflight(request);

/** @type {import('./$types').RequestHandler} */
export const GET = async ({ request, locals, url }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	let stories = await listStories();
	const draft = url.searchParams.get('draft');
	if (draft === '1') stories = stories.filter((s) => s.draft);
	else if (draft === '0') stories = stories.filter((s) => !s.draft);
	// Drafts are being worked on; most recently edited first is the useful order.
	if (draft === '1') stories.sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1));
	return reply(request, { stories: stories.map(summarizeStory) });
};

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, locals }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');
	const body = await readJson(request);

	let slug;
	if (typeof body.slug === 'string' && body.slug) {
		slug = toSlug(body.slug);
		if (!slug) return fail(request, 400, 'bad_slug', { slug: body.slug });
		if (await getStory(slug)) return fail(request, 409, 'slug_taken', { slug });
	} else {
		// No title: the story's date is its address (2026-11-19, then -2, -3).
		const day = typeof body.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(body.date) ? body.date : new Date().toISOString().slice(0, 10);
		const base = toSlug(String(body.title || '')) || day;
		slug = await uniqueSlug(base);
	}

	const normalized = normalizeStory({ ...body, slug, draft: body.draft === undefined ? true : body.draft });
	if ('error' in normalized) return fail(request, 400, normalized.error, normalized.detail);

	const story = await putStory(normalized.story);
	return reply(request, { story }, 201, { location: `/api/v1/stories/${slug}` });
};
