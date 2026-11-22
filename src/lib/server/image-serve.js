/** Render (or reuse) one variant of an image and send it. Shared by /img and /private/img. */
import { ensureVariant, getImage, parseVariant } from './images.js';
import { serveObject } from './serve.js';

/**
 * @param {Request} request @param {string} id @param {string} variantParam
 * @param {{ cacheControl?: string }} [opts]  default: public, immutable, one year
 */
export async function serveImage(request, id, variantParam, opts = {}) {
	const image = await getImage(id);
	if (!image) return new Response('not found', { status: 404 });

	const accept = request.headers.get('accept') || '';
	const variant = parseVariant(variantParam, accept);
	let rendered;
	try {
		rendered = await ensureVariant(image, variant);
	} catch (e) {
		console.error('[img] render failed', id, variantParam, e);
		return new Response('could not render image', { status: 500 });
	}
	const res = await serveObject(request, rendered.key, { mime: rendered.mime, cacheControl: opts.cacheControl });
	if (variantParam.includes('format=auto')) res.headers.set('vary', 'accept');
	return res;
}
