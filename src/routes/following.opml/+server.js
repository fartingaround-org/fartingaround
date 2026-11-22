import { getFollowing } from '#lib/server/following.js';
import { followingPublic } from '#lib/server/settings.js';

/** The blogroll as OPML, for anyone's reader to import. Hidden when the owner keeps the list private. */
export async function GET() {
	// Same answer for everyone: this URL is publicly cached.
	const f = (await followingPublic()) ? await getFollowing() : null;
	if (!f) return new Response('', { status: 404, headers: { 'cache-control': 'no-store' } });
	return new Response(f.opml, {
		headers: {
			'content-type': 'text/x-opml; charset=utf-8',
			'content-disposition': 'inline; filename="following.opml"',
			'cache-control': 'public, max-age=300'
		}
	});
}
