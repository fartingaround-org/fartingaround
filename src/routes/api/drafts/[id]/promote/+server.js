/**
 * Promote a draft into a Story.
 *
 *   POST /api/drafts/:id/promote
 *     body: { slug?: string, date?: string, location?: string, summary?: string }
 *
 * Generates a block sequence from the draft:
 *   - body text → one text block (split on blank lines into paragraphs is
 *     already handled by the renderer)
 *   - each audio attachment with a transcript → text block; without one →
 *     skipped (admin can add it manually)
 *   - each photo → photo block
 *   - each video → text block linking to the proxy URL (we don't have a
 *     video block type yet; this is a v2 polish item)
 *
 * The draft is marked status='promoted' and gets `promotedToSlug` set.
 * It's not deleted automatically — admin can clean up after reviewing.
 *
 * Admin-only.
 */

import { json } from '@sveltejs/kit';
import { getDraft, putDraft } from '#lib/server/drafts.js';
import { putStory, toSlug } from '#lib/server/stories.js';

/** @param {string} prefix */
function blockId(prefix) {
	return `${prefix}-${Math.random().toString(36).slice(2, 9)}`;
}

/** @type {import('./$types').RequestHandler} */
export async function POST({ request, params, locals }) {
	if (!locals.isAdmin) return new Response('forbidden', { status: 403 });


	const draft = await getDraft(params.id);
	if (!draft) return new Response('not found', { status: 404 });

	let opts;
	try {
		opts = await request.json();
	} catch {
		opts = {};
	}

	const slug = toSlug(opts.slug || draft.title || `draft-${params.id.slice(0, 8)}`) || `draft-${params.id.slice(0, 8)}`;
	const date = opts.date || draft.capturedAt.slice(0, 10);

	/** @type {any[]} */
	const blocks = [];

	if (draft.body && draft.body.trim()) {
		blocks.push({ type: 'text', id: blockId('t'), body: draft.body.trim() });
	}

	for (const att of draft.attachments) {
		if (att.kind === 'photo') {
			if (att.r2 && !att.imageId) {
				// Legacy: a photo stored only as a full-res R2 original has no
				// image id, so it can only be shown as a (now display-only)
				// gallery item. Star maps are no longer carried over.
				/** @type {any} */
				const item = {
					caption: att.caption || undefined,
					alt: att.caption || undefined
				};
				item.r2 = att.r2;

				blocks.push({
					type: 'gallery',
					id: blockId('g'),
					items: [item],
					size: 'wide',
					aspect: '3/2'
				});
			} else {
				blocks.push({
					type: 'photo',
					id: blockId('p'),
					imageId: att.imageId,
					caption: att.caption || undefined
				});
			}
		} else if (att.kind === 'audio') {
			// Emit an audio block first so visitors can play the actual
			// recording — much more personal than just reading the transcript.
			// If we have a transcript, fold it into the audio block (collapsed
			// by default in the renderer) so it's still searchable / readable
			// without doubling the visual weight on the page.
			blocks.push({
				type: 'audio',
				id: blockId('au'),
				r2Key: att.r2Key,
				mimeType: att.mimeType,
				durationMs: att.durationMs,
				caption: att.caption || undefined,
				transcript: att.transcript || undefined,
				transcriptModel: att.transcriptModel || undefined
			});
		} else if (att.kind === 'video') {
			// First-class video block — embeds the actual recording with a
			// controls bar. Way more useful than the old "Video recorded
			// 2026-05-26T18:34:33Z" aside line.
			blocks.push({
				type: 'video',
				id: blockId('vd'),
				r2Key: att.r2Key,
				mimeType: att.mimeType,
				durationMs: att.durationMs,
				caption: att.caption || undefined,
				posterImageId: att.posterImageId || undefined,
				size: 'wide'
			});
		}
	}

	const coverImageId = draft.attachments.find((a) => a.kind === 'photo')?.imageId;

	const story = {
		slug,
		title: draft.title || 'Untitled',
		date,
		location: opts.location || draft.location,
		// Carry the captured GPS over so the story page can render a "where"
		// line + map link. Admin can wipe it from the editor if they don't
		// want it public.
		coords: draft.coords,
		// Auto-context captured alongside coords — weather snapshot + moon
		// phase / sun events. Renders in the story header so a story is also
		// a tiny logbook of what the world was doing right then.
		weather: draft.weather,
		astro: draft.astro,
		summary: opts.summary || undefined,
		coverImageId,
		tags: draft.tags,
		blocks,
		createdAt: new Date().toISOString(),
		updatedAt: new Date().toISOString(),
		draft: true
	};

	const saved = await putStory(/** @type {any} */ (story));

	await putDraft({
		...draft,
		status: 'promoted',
		promotedToSlug: saved.slug
	});

	return json({ story: saved });
}
