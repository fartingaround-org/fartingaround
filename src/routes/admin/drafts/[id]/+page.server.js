/**
 * Single draft review page.
 *
 * Server actions:
 *   - promote: POST to /api/drafts/:id/promote with title-based slug
 *   - archive: PATCH /api/drafts/:id with status='archived'
 *   - delete:  DELETE /api/drafts/:id
 */

import { error, fail, redirect } from '@sveltejs/kit';
import { getDraft, putDraft, deleteDraft } from '#lib/server/drafts.js';
import { deleteObject } from '#lib/server/objects.js';
import { putStory, toSlug } from '#lib/server/stories.js';

/** @type {import('./$types').PageServerLoad} */
export async function load({ params }) {
	const draft = await getDraft(params.id);
	if (!draft) throw error(404, 'Draft not found');
	return { draft };
}

/** @param {string} prefix */
function blockId(prefix) {
	return `${prefix}-${Math.random().toString(36).slice(2, 9)}`;
}

/** @type {import('./$types').Actions} */
export const actions = {
	async promote({ params, request }) {

		const draft = await getDraft(params.id);
		if (!draft) return fail(404, { error: 'not_found' });

		const form = await request.formData();
		const slugInput = String(form.get('slug') || draft.title || `draft-${params.id.slice(0, 8)}`);
		const date = String(form.get('date') || draft.capturedAt.slice(0, 10));
		const location = String(form.get('location') || draft.location || '');
		const summary = String(form.get('summary') || '');

		const slug = toSlug(slugInput) || `draft-${params.id.slice(0, 8)}`;

		/** @type {any[]} */
		const blocks = [];
		if (draft.body && draft.body.trim()) {
			blocks.push({ type: 'text', id: blockId('t'), body: draft.body.trim() });
		}
		for (const att of draft.attachments) {
			if (att.kind === 'photo') {
				blocks.push({
					type: 'photo', id: blockId('p'),
					imageId: att.imageId,
					caption: att.caption || undefined
				});
			} else if (att.kind === 'audio') {
				if (att.transcript) {
					blocks.push({ type: 'text', id: blockId('t'), body: att.transcript });
				} else {
					blocks.push({
						type: 'aside', id: blockId('a'),
						label: 'audio (no transcript)',
						body: att.caption || `Audio recorded ${att.createdAt}`
					});
				}
			} else if (att.kind === 'video') {
				blocks.push({
					type: 'aside', id: blockId('a'),
					label: 'video',
					body: att.caption || `Video recorded ${att.createdAt}`
				});
			}
		}

		const coverImageId = draft.attachments.find((a) => a.kind === 'photo')?.imageId;

		await putStory(/** @type {any} */ ({
			slug,
			title: draft.title || 'Untitled',
			date,
			location: location || undefined,
			// Carry the captured GPS (and the weather/astro snapshot taken
			// alongside it) over to the story. Without coords the story is
			// invisible to /map, which filters on coords.lat/lon. This mirrors
			// the API promote endpoint at /api/drafts/:id/promote.
			coords: draft.coords,
			weather: draft.weather,
			astro: draft.astro,
			summary: summary || undefined,
			coverImageId,
			tags: draft.tags,
			blocks,
			createdAt: new Date().toISOString(),
			updatedAt: new Date().toISOString(),
			draft: true
		}));

		await putDraft({ ...draft, status: 'promoted', promotedToSlug: slug });

		throw redirect(303, `/admin/${slug}`);
	},

	async archive({ params }) {
		const draft = await getDraft(params.id);
		if (!draft) return fail(404, { error: 'not_found' });
		await putDraft({ ...draft, status: 'archived' });
		throw redirect(303, '/admin/drafts');
	},

	async destroy({ params }) {
		const draft = await getDraft(params.id);
		if (draft) {
			await Promise.all(
				draft.attachments
					.filter((a) => a.kind === 'audio' || a.kind === 'video')
					.map((a) => deleteObject(/** @type {any} */ (a).r2Key).catch(() => null))
			);
		}
		await deleteDraft(params.id);
		throw redirect(303, '/admin/drafts');
	}
};
