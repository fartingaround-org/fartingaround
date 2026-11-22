import { json, error } from '@sveltejs/kit';
import { uploadImage } from '#lib/server/images.js';

// Generous but bounded: a single admin photo upload. Full-resolution astro
// frames go through the signed-PUT gallery path instead, which has no cap
// beyond BODY_SIZE_LIMIT.
const MAX_BYTES = 50 * 1024 * 1024;

/** @type {import('./$types').RequestHandler} */
export const POST = async ({ request, locals }) => {
	if (!locals.isAdmin) throw error(401, 'Unauthorized');

	const form = await request.formData();
	const file = form.get('file');
	if (!(file instanceof File)) throw error(400, 'No file uploaded.');

	if (file.size > MAX_BYTES) {
		throw error(413, `File is ${(file.size / 1024 / 1024).toFixed(1)} MB — the limit here is ${MAX_BYTES / 1024 / 1024} MB.`);
	}

	try {
		const result = await uploadImage(file, { filename: file.name });
		return json({ imageId: result.id, filename: result.filename });
	} catch (err) {
		console.error('[upload] failed:', err);
		throw error(500, err instanceof Error ? err.message : 'Upload failed');
	}
};
