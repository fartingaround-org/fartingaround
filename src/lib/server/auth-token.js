import { config } from './config.js';

/**
 * Tiny bearer-token auth for the phone -> web API.
 *
 * We don't want to share the admin cookie with the phone app — it lives in a
 * Capacitor WebView at a different origin and has no SSO. So phone requests
 * carry `Authorization: Bearer <CAPTURE_API_TOKEN>` and we compare against
 * the CAPTURE_API_TOKEN environment variable.
 *
 * @param {Request} request
 * @returns {boolean}
 */
export function isCaptureRequest(request) {
	const expected = config.captureApiToken;
	if (!expected) return false;
	const header = request.headers.get('authorization') || '';
	const match = header.match(/^Bearer\s+(.+)$/i);
	if (!match) return false;
	// constant-time-ish comparison (length first, then char-by-char without early exit)
	const provided = match[1];
	if (provided.length !== expected.length) return false;
	let diff = 0;
	for (let i = 0; i < provided.length; i++) {
		diff |= provided.charCodeAt(i) ^ expected.charCodeAt(i);
	}
	return diff === 0;
}

/**
 * CORS headers for the capture API.
 *
 * The Capacitor WebView origin varies by Android version and config — it
 * can be https://localhost, capacitor://localhost, or something else
 * depending on the scheme + hostname. Since the real gate on this API is
 * the bearer token (constant-time compared against CAPTURE_API_TOKEN),
 * we use a wildcard origin instead of an allowlist. We do NOT send
 * Allow-Credentials, so the cookie auth path can only succeed same-origin
 * (which is exactly what we want — admin always uses fartingaround.fun
 * directly).
 *
 * @param {Request} request
 */
export function corsHeaders(request) {
	return {
		'Access-Control-Allow-Origin': '*',
		'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
		'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Kind, X-Caption, X-Duration-Ms',
		'Access-Control-Max-Age': '86400'
	};
}

/**
 * Build a standard 401 response for the capture API.
 * @param {Request} request
 */
export function unauthorized(request) {
	return new Response(JSON.stringify({ error: 'unauthorized' }), {
		status: 401,
		headers: { 'content-type': 'application/json', ...corsHeaders(request) }
	});
}
