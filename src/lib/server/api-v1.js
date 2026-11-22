/**
 * Shared bits for the /api/v1 authoring surface: the phone app's (and any
 * other client's) way to create, edit and publish stories against any
 * instance. Auth is the admin cookie (same origin) or the bearer token.
 */
import { corsHeaders, isCaptureRequest } from './auth-token.js';

export { corsHeaders };

/**
 * @param {Request} request
 * @param {App.Locals} locals
 */
export function isAuthor(request, locals) {
	return Boolean(locals.isAdmin) || isCaptureRequest(request);
}

/**
 * JSON response with CORS headers.
 * @param {Request} request
 * @param {unknown} body
 * @param {number} [status]
 * @param {Record<string, string>} [extraHeaders]
 */
export function reply(request, body, status = 200, extraHeaders = {}) {
	return new Response(JSON.stringify(body), {
		status,
		headers: { 'content-type': 'application/json; charset=utf-8', ...corsHeaders(request), ...extraHeaders }
	});
}

/**
 * Error response: `{ error, ...detail }`.
 * @param {Request} request
 * @param {number} status
 * @param {string} error   snake_case code the client can switch on
 * @param {Record<string, unknown>} [detail]
 */
export function fail(request, status, error, detail = {}) {
	return reply(request, { error, ...detail }, status);
}

/** @param {Request} request */
export function preflight(request) {
	return new Response(null, { status: 204, headers: corsHeaders(request) });
}

/** @param {Request} request */
export async function readJson(request) {
	try {
		const v = await request.json();
		return v && typeof v === 'object' ? /** @type {Record<string, any>} */ (v) : {};
	} catch {
		return {};
	}
}

const SLUG_RE = /^[a-z0-9][a-z0-9-]{0,79}$/;
export const isSlug = (/** @type {string} */ s) => SLUG_RE.test(s);
