/**
 * Upload a photo for use in any block.
 *
 *   POST /api/v1/media/photo    multipart/form-data: file
 *   → { imageId, w, h, takenAt?, exif? }
 *
 * The response is shaped so a client can drop it straight into a DumpPhoto:
 * `w`/`h` let the grid lay out before the image loads; `takenAt` orders the
 * map path; `exif` ({ lat, lon, takenAt }) is the PRIVATE raw reading — keep
 * it on the photo so a location can be applied later, never publish it
 * yourself. The server strips it from public views.
 *
 * Cap matches the admin uploader (50 MB).
 */
import { uploadImage } from '#lib/server/images.js';
import { readStoredPhotoExif } from '#lib/server/photo-exif.js';
import { fail, isAuthor, preflight, reply } from '#lib/server/api-v1.js';

const MAX_BYTES = 50 * 1024 * 1024;

/** @type {import('./$types').RequestHandler} */
export const OPTIONS = async ({ request }) => preflight(request);

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, locals }) => {
	if (!isAuthor(request, locals)) return fail(request, 401, 'unauthorized');

	let form;
	try {
		form = await request.formData();
	} catch {
		return fail(request, 400, 'invalid_form');
	}
	const file = form.get('file');
	if (!(file instanceof Blob)) return fail(request, 400, 'missing_file');
	if (file.size > MAX_BYTES) return fail(request, 413, 'too_large', { bytes: file.size, max: MAX_BYTES });
	if (file.type && !file.type.startsWith('image/')) return fail(request, 415, 'not_an_image', { type: file.type });

	try {
		const up = await uploadImage(file, { filename: file instanceof File ? file.name : 'photo' });
		const exif = await readStoredPhotoExif(`images/${up.id}/orig.${up.ext}`);
		/** @type {Record<string, unknown>} */
		const out = { imageId: up.id, w: up.width, h: up.height };
		if (exif?.takenAt) out.takenAt = exif.takenAt;
		if (exif) out.exif = exif;
		return reply(request, out, 201);
	} catch (err) {
		console.error('[v1/media/photo] failed:', err);
		return fail(request, 500, 'upload_failed', { message: err instanceof Error ? err.message : String(err) });
	}
};
