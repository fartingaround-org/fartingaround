/**
 * Who is asking, for routes that feed readers and apps call as well as
 * browsers: private media, the token feeds, story audio and video.
 *
 * A browser is identified by its cookies (already on `locals`). A feed
 * reader or app has no cookies, so it may present a reader token instead,
 * as `?token=…` or `Authorization: Bearer …`.
 */
import { isCaptureRequest } from './auth-token.js';
import { getDb } from './db.js';
import { readerForToken } from './readers.js';

/** How long a member's own browser may keep a private file. Never a shared cache. */
export const PRIVATE_CACHE = 'private, max-age=86400';

/**
 * @param {{ locals: App.Locals, request: Request, url: URL }} event
 * @returns {Promise<{ isAdmin: boolean, isAuthorApp: boolean, reader: import('./readers.js').Reader | null, token: string | null }>}
 *   `token` is set only when a reader token is what identified them.
 */
export async function viewerFor({ locals, request, url }) {
	const isAuthorApp = isCaptureRequest(request);
	if (locals.isAdmin || locals.reader || isAuthorApp) {
		return { isAdmin: locals.isAdmin, isAuthorApp, reader: locals.reader, token: null };
	}
	const bearer = (request.headers.get('authorization') || '').match(/^Bearer\s+(.+)$/i)?.[1];
	const token = url.searchParams.get('token') || bearer || null;
	const reader = token ? await readerForToken(await getDb(), token) : null;
	return { isAdmin: false, isAuthorApp: false, reader, token: reader ? token : null };
}

/**
 * Send a request for a restricted file from a public address to its
 * /private twin. The answer is the same for everyone, so nothing about the
 * viewer can end up in a shared cache; the private address does the check.
 * @param {URL} url @param {string} privatePath
 */
export function redirectToPrivate(url, privatePath) {
	return new Response(null, {
		status: 302,
		headers: { location: privatePath + url.search, 'cache-control': 'private, max-age=300' }
	});
}

export function privateNotFound() {
	return new Response('not found', { status: 404, headers: { 'cache-control': 'no-store' } });
}
