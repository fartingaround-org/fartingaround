/**
 * Pre-render the common sizes of every image, so pages full of photos (dumps,
 * galleries) don't pay the first-view render cost. Run once after a migration:
 *
 *   curl -N -X POST -H "Cookie: admin=$ADMIN_PASSWORD" http://localhost:8406/admin/api/warm-images
 *
 * Streams one line per image so you can watch it go; safe to re-run (already
 * rendered sizes are skipped).
 */

import { error } from '@sveltejs/kit';
import { warmAllImages } from '#lib/server/images.js';

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ locals }) => {
	if (!locals.isAdmin) throw error(401, 'Unauthorized');

	const encoder = new TextEncoder();
	const stream = new ReadableStream({
		async start(controller) {
			const started = Date.now();
			controller.enqueue(encoder.encode('warming images…\n'));
			try {
				const total = await warmAllImages((done, all, id) => {
					controller.enqueue(encoder.encode(`${done}/${all} ${id}\n`));
				});
				controller.enqueue(encoder.encode(`done: ${total} images in ${Math.round((Date.now() - started) / 1000)}s\n`));
			} catch (e) {
				controller.enqueue(encoder.encode(`failed: ${e instanceof Error ? e.message : e}\n`));
			}
			controller.close();
		}
	});
	return new Response(stream, { headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' } });
};
