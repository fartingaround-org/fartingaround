/**
 * Personal feeds. `/feed.json?token=…` and `/rss.xml?token=…` identify a
 * member by their reader token. Cookies are deliberately ignored here: the
 * token-less feed URLs are cached publicly and must be the same for everyone.
 */
import { getDb } from './db.js';
import { readerForToken } from './readers.js';

/**
 * @param {URL} url
 * @returns {Promise<null | 'invalid' | { reader: import('./readers.js').Reader, token: string }>}
 *   null: no token, serve the public feed. 'invalid': a token that is unknown or revoked.
 */
export async function readerFeed(url) {
	const token = url.searchParams.get('token');
	if (token === null) return null;
	const reader = await readerForToken(await getDb(), token);
	return reader ? { reader, token } : 'invalid';
}
