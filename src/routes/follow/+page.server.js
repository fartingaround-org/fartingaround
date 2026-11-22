/** How to follow the site: the feed addresses, and a QR code of the feed for the app's scanner. */
import { qrSvg } from '#lib/server/qr.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ url, locals }) => {
	const json = `${url.origin}/feed.json`;
	return {
		json,
		rss: `${url.origin}/rss.xml`,
		qr: await qrSvg(json),
		member: locals.reader?.role === 'member'
	};
};
