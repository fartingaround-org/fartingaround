/**
 * One-shot migration: convert legacy audio representations in stories
 * into proper AudioBlocks.
 *
 *   POST /admin/api/migrate-audio-blocks?dry=1
 *
 * Pre-AudioBlock, the promote endpoint emitted audio in two ways:
 *   - With transcript:   a text block whose body == the transcript
 *   - Without transcript: an aside block with label='audio (no transcript)'
 *                         and body 'Audio recorded {timestamp}' or a caption
 *
 * Strategy:
 *   1. Build a map of promotedToSlug → Draft once.
 *   2. For each story:
 *      a. Pass A — text-as-transcript matching. For each audio attachment
 *         on the source draft that has a transcript, search the story's
 *         text blocks for one whose body matches (whitespace-normalized
 *         equality). If exactly one match exists, replace that text block
 *         with an AudioBlock carrying the transcript folded in. Skip
 *         ambiguous (0 or >1) matches and report them.
 *      b. Pass B — audio-aside matching. For each remaining audio
 *         attachment (no transcript, or transcript-with-no-text-match),
 *         pair by index against the story's aside blocks where label
 *         starts with 'audio'. Replace each paired aside with an AudioBlock.
 *
 * Idempotent: AudioBlocks already present are ignored; matching is by
 * shape, not by id. Re-running after a partial migration is safe.
 *
 * Auth: admin-only (locals.isAdmin set by hooks.server.js).
 */

import { json } from '@sveltejs/kit';
import { listStories, putStory } from '#lib/server/stories.js';
import { listDrafts } from '#lib/server/drafts.js';

/** @param {string} prefix */
function blockId(prefix) {
	return `${prefix}-${Math.random().toString(36).slice(2, 9)}`;
}

/** Whitespace-normalize and trim — lets us match transcripts even if a
 *  text block got tidied (extra spaces collapsed, surrounding blanks). */
function norm(/** @type {string} */ s) {
	return (s || '').replace(/\s+/g, ' ').trim();
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

	/** @type {Array<{slug:string,title:string,action:string,replaced:any[],ambiguous:any[],reason?:string}>} */
	const report = [];
	let totalMigrated = 0;
	let totalSkipped = 0;
	let totalNoChange = 0;

	for (const story of stories) {
		const blocks = story.blocks || [];

		const draft = draftBySlug.get(story.slug);
		if (!draft) {
			// Only count as skipped if there's plausibly-audio content to
			// migrate; otherwise it's a no-op.
			const hasAudioAside = blocks.some(
				(b) => b.type === 'aside' && /^audio/i.test(/** @type {any} */ (b).label || '')
			);
			if (!hasAudioAside) {
				totalNoChange++;
				continue;
			}
			report.push({
				slug: story.slug,
				title: story.title,
				action: 'skipped',
				replaced: [],
				ambiguous: [],
				reason: 'no source draft (deleted after promotion); cannot recover audio metadata'
			});
			totalSkipped++;
			continue;
		}

		const audioAtts = (draft.attachments || []).filter(
			(a) => a.kind === 'audio' && /** @type {any} */ (a).r2Key
		);
		if (audioAtts.length === 0) {
			totalNoChange++;
			continue;
		}

		const newBlocks = [...blocks];
		/** @type {any[]} */
		const replaced = [];
		/** @type {any[]} */
		const ambiguous = [];
		const usedAttIdxs = new Set();

		// --- Pass A: match text blocks against audio transcripts ---
		audioAtts.forEach((att, attIdx) => {
			const transcript = /** @type {any} */ (att).transcript;
			if (!transcript) return;
			const needle = norm(transcript);
			/** @type {number[]} */
			const matches = [];
			newBlocks.forEach((b, i) => {
				if (b.type === 'text' && norm(/** @type {any} */ (b).body) === needle) {
					matches.push(i);
				}
			});
			if (matches.length === 1) {
				const idx = matches[0];
				const oldBlock = newBlocks[idx];
				const newBlock = buildAudioBlock(/** @type {any} */ (att));
				newBlocks[idx] = newBlock;
				replaced.push({
					pass: 'A',
					oldType: 'text',
					oldBlockId: oldBlock.id,
					newBlockId: newBlock.id,
					r2Key: /** @type {any} */ (att).r2Key
				});
				usedAttIdxs.add(attIdx);
			} else if (matches.length > 1) {
				ambiguous.push({
					pass: 'A',
					attIdx,
					reason: `transcript matched ${matches.length} text blocks; skipped to avoid wrong replacement`
				});
			}
			// matches.length === 0 → leave for Pass B
		});

		// --- Pass B: pair remaining audio attachments with audio asides ---
		const remainingAtts = audioAtts
			.map((att, i) => ({ att, i }))
			.filter(({ i }) => !usedAttIdxs.has(i));
		/** @type {number[]} */
		const audioAsideIdxs = [];
		newBlocks.forEach((b, i) => {
			if (b.type === 'aside' && /^audio/i.test(/** @type {any} */ (b).label || '')) {
				audioAsideIdxs.push(i);
			}
		});
		const pairs = Math.min(remainingAtts.length, audioAsideIdxs.length);
		for (let n = 0; n < pairs; n++) {
			const { att } = remainingAtts[n];
			const at = audioAsideIdxs[n];
			const oldBlock = newBlocks[at];
			const newBlock = buildAudioBlock(/** @type {any} */ (att));
			newBlocks[at] = newBlock;
			replaced.push({
				pass: 'B',
				oldType: 'aside',
				oldBlockId: oldBlock.id,
				newBlockId: newBlock.id,
				r2Key: /** @type {any} */ (att).r2Key
			});
		}

		if (replaced.length === 0 && ambiguous.length === 0) {
			totalNoChange++;
			continue;
		}

		if (replaced.length > 0 && !dry) {
			await putStory({ .../** @type {any} */ (story), blocks: newBlocks });
		}

		const mismatch = remainingAtts.length - pairs;
		const extraAsides = audioAsideIdxs.length - pairs;
		report.push({
			slug: story.slug,
			title: story.title,
			action:
				replaced.length === 0
					? 'no-change'
					: dry
					? 'would-migrate'
					: 'migrated',
			replaced,
			ambiguous,
			reason:
				mismatch > 0 || extraAsides > 0
					? `mismatch: ${mismatch} attachment(s) unmatched, ${extraAsides} aside(s) unused`
					: undefined
		});
		if (replaced.length > 0) totalMigrated++;
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

/**
 * @param {any} att audio Attachment
 * @returns {import('#lib/types.js').AudioBlock}
 */
function buildAudioBlock(att) {
	return {
		type: 'audio',
		id: blockId('au'),
		r2Key: att.r2Key,
		mimeType: att.mimeType,
		durationMs: att.durationMs,
		caption: att.caption || undefined,
		transcript: att.transcript || undefined,
		transcriptModel: att.transcriptModel || undefined
	};
}
