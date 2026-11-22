/**
 * One-shot migration: convert legacy "video as an aside" story blocks
 * into real VideoBlocks.
 *
 *   POST /admin/api/migrate-video-blocks?dry=1
 *
 * Before this codebase had a VideoBlock type, the promote endpoint
 * rendered each video attachment as an aside block with body
 * "Video recorded {timestamp}". This script finds those asides on every
 * promoted story, locates the source draft (which still carries the
 * video attachment's r2Key, mimeType, durationMs, caption, posterImageId),
 * and replaces the aside with a proper VideoBlock so the story finally
 * shows the video.
 *
 * Strategy:
 *   1. Build a map of promotedToSlug → Draft once, so each story lookup
 *      is O(1).
 *   2. For each story:
 *      a. Collect "video asides" in order (aside blocks where label='video').
 *      b. Collect video attachments from the source draft in order.
 *      c. Pair them by index — Nth aside becomes a VideoBlock built from
 *         the Nth attachment. If counts mismatch, pair what we can and
 *         leave extras alone.
 *      d. If at least one block was rebuilt, save the updated story.
 *
 * Idempotent: only touches aside blocks where label === 'video' AND the
 * source draft has matching video attachments. Real VideoBlocks already
 * present (because the story was promoted under the new logic) are left
 * untouched.
 *
 * Run from the browser while signed in as admin:
 *   - Dry run:     visit /admin/migrations and click "Dry run"
 *   - Apply:       click "Apply" (only after reading the dry-run report)
 * Or hit it directly with curl using the admin session cookie:
 *   curl -X POST 'https://fartingaround.fun/admin/api/migrate-video-blocks?dry=1' \
 *     -H 'Cookie: <your admin cookie>' | jq
 */

import { json } from '@sveltejs/kit';
import { listStories, putStory } from '#lib/server/stories.js';
import { listDrafts } from '#lib/server/drafts.js';

/** @param {string} prefix */
function blockId(prefix) {
	return `${prefix}-${Math.random().toString(36).slice(2, 9)}`;
}

/** @type {import('./$types').RequestHandler} */
export async function POST({ url, locals }) {
	if (!locals.isAdmin) return new Response('forbidden', { status: 403 });

	const dry = url.searchParams.get('dry') === '1';

	const [stories, drafts] = await Promise.all([listStories(), listDrafts()]);

	/** @type {Map<string, import('#lib/server/drafts.js').Draft>} */
	const draftBySlug = new Map();
	for (const d of drafts) {
		if (d.promotedToSlug) draftBySlug.set(d.promotedToSlug, d);
	}

	/** @type {Array<{slug: string, title: string, action: string, replaced: any[], reason?: string}>} */
	const report = [];
	let totalMigrated = 0;
	let totalSkipped = 0;
	let totalNoChange = 0;

	for (const story of stories) {
		const videoAsideIdx = (story.blocks || [])
			.map((b, i) => ({ b, i }))
			.filter(({ b }) => b.type === 'aside' && /** @type {any} */ (b).label === 'video')
			.map(({ i }) => i);

		if (videoAsideIdx.length === 0) {
			totalNoChange++;
			continue;
		}

		const draft = draftBySlug.get(story.slug);
		if (!draft) {
			report.push({
				slug: story.slug,
				title: story.title,
				action: 'skipped',
				replaced: [],
				reason: 'no source draft (deleted after promotion); cannot recover r2Key'
			});
			totalSkipped++;
			continue;
		}

		const videoAtts = (draft.attachments || []).filter(
			(a) => a.kind === 'video' && /** @type {any} */ (a).r2Key
		);

		if (videoAtts.length === 0) {
			report.push({
				slug: story.slug,
				title: story.title,
				action: 'skipped',
				replaced: [],
				reason: 'source draft has no video attachments with r2Key'
			});
			totalSkipped++;
			continue;
		}

		const pairs = Math.min(videoAsideIdx.length, videoAtts.length);
		const newBlocks = [...story.blocks];
		const replaced = [];
		for (let n = 0; n < pairs; n++) {
			const at = videoAsideIdx[n];
			const oldBlock = newBlocks[at];
			const att = /** @type {any} */ (videoAtts[n]);
			const newBlock = {
				type: 'video',
				id: blockId('vd'),
				r2Key: att.r2Key,
				mimeType: att.mimeType,
				durationMs: att.durationMs,
				caption: att.caption || undefined,
				posterImageId: att.posterImageId || undefined,
				size: 'wide'
			};
			newBlocks[at] = /** @type {any} */ (newBlock);
			replaced.push({
				oldBlockId: oldBlock.id,
				newBlockId: newBlock.id,
				r2Key: att.r2Key,
				mimeType: att.mimeType
			});
		}

		// Note any mismatch in the report so the user sees it
		const unmatchedAsides = videoAsideIdx.length - pairs;
		const unmatchedAtts = videoAtts.length - pairs;

		if (!dry) {
			await putStory({ .../** @type {any} */ (story), blocks: newBlocks });
		}

		report.push({
			slug: story.slug,
			title: story.title,
			action: dry ? 'would-migrate' : 'migrated',
			replaced,
			reason:
				unmatchedAsides > 0 || unmatchedAtts > 0
					? `mismatch: ${unmatchedAsides} aside(s) unmatched, ${unmatchedAtts} attachment(s) unused`
					: undefined
		});
		totalMigrated++;
	}

	return json({
		dry,
		summary: {
			totalStories: stories.length,
			migrated: totalMigrated,
			skipped: totalSkipped,
			noChange: totalNoChange
		},
		stories: report
	});
}
