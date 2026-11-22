/**
 * The WebAuthn ceremonies for reader passkeys, on top of @simplewebauthn/server.
 * Storage and the rules about who may do what are in readers.js.
 *
 * The relying party is the site's own hostname, so a passkey made here only
 * ever works here. Challenges are kept server-side and are single-use.
 */
import {
	generateAuthenticationOptions,
	generateRegistrationOptions,
	verifyAuthenticationResponse,
	verifyRegistrationResponse
} from '@simplewebauthn/server';
import {
	ReaderError,
	addPasskey,
	passkeyDescriptors,
	passkeyForSignIn,
	saveChallenge,
	signInWithPasskey,
	takeChallenge
} from './readers.js';

/** @typedef {import('./db.js').Db} Db */
/** @typedef {import('@sveltejs/kit').Cookies} Cookies */

const CHALLENGE_COOKIE = 'fa_wa';
const challengeCookie = /** @type {const} */ ({ path: '/', httpOnly: true, secure: true, sameSite: 'strict', maxAge: 5 * 60 });

/** @param {Cookies} cookies */
function takeCookie(cookies) {
	const id = cookies.get(CHALLENGE_COOKIE);
	cookies.delete(CHALLENGE_COOKIE, { path: '/' });
	return id;
}

/**
 * Step 1 of adding a passkey, for the signed-in reader.
 * @param {Db} db @param {URL} url @param {Cookies} cookies @param {import('./readers.js').Reader} reader
 */
export async function registrationOptions(db, url, cookies, reader) {
	const options = await generateRegistrationOptions({
		rpName: url.hostname,
		rpID: url.hostname,
		userName: reader.displayName,
		userDisplayName: reader.displayName,
		userID: new TextEncoder().encode(reader.id),
		attestationType: 'none',
		excludeCredentials: await passkeyDescriptors(db, reader.id),
		// Discoverable, so sign-in needs no username: the passkey says who it is.
		authenticatorSelection: { residentKey: 'required', userVerification: 'preferred' }
	});
	const id = await saveChallenge(db, { purpose: 'register', contactId: reader.id, challenge: options.challenge });
	cookies.set(CHALLENGE_COOKIE, id, challengeCookie);
	return options;
}

/**
 * Step 2 of adding a passkey.
 * @param {Db} db @param {URL} url @param {Cookies} cookies @param {import('./readers.js').Reader} reader
 * @param {{ response?: unknown, label?: unknown }} body
 * @returns {Promise<string>} the new passkey's id
 */
export async function verifyRegistration(db, url, cookies, reader, body) {
	const challenge = await takeChallenge(db, takeCookie(cookies), 'register', reader.id);
	if (!challenge || !body?.response) throw new ReaderError('invalid', 'That took too long. Please try again.');
	let result;
	try {
		result = await verifyRegistrationResponse({
			response: /** @type {import('@simplewebauthn/server').RegistrationResponseJSON} */ (body.response),
			expectedChallenge: challenge,
			expectedOrigin: url.origin,
			expectedRPID: url.hostname,
			requireUserVerification: false
		});
	} catch (e) {
		console.warn('[passkeys] registration rejected:', e instanceof Error ? e.message : e);
		throw new ReaderError('invalid', 'That passkey could not be verified.');
	}
	if (!result.verified) throw new ReaderError('invalid', 'That passkey could not be verified.');
	const { credential } = result.registrationInfo;
	return addPasskey(db, reader.id, {
		credentialId: credential.id,
		publicKey: credential.publicKey,
		counter: credential.counter,
		transports: credential.transports,
		label: typeof body.label === 'string' ? body.label : ''
	});
}

/** Step 1 of signing in. No username: any passkey for this site may answer. @param {Db} db @param {URL} url @param {Cookies} cookies */
export async function authenticationOptions(db, url, cookies) {
	const options = await generateAuthenticationOptions({ rpID: url.hostname, userVerification: 'preferred' });
	const id = await saveChallenge(db, { purpose: 'signin', challenge: options.challenge });
	cookies.set(CHALLENGE_COOKIE, id, challengeCookie);
	return options;
}

/**
 * Step 2 of signing in. Returns a new session, or null if anything is off;
 * the caller shows one generic message either way.
 * @param {Db} db @param {URL} url @param {Cookies} cookies
 * @param {{ response?: unknown }} body @param {string | null} userAgent
 */
export async function verifyAuthentication(db, url, cookies, body, userAgent) {
	const challenge = await takeChallenge(db, takeCookie(cookies), 'signin');
	const response = /** @type {import('@simplewebauthn/server').AuthenticationResponseJSON | undefined} */ (body?.response);
	if (!challenge || !response || typeof response.id !== 'string') return null;
	const found = await passkeyForSignIn(db, response.id);
	if (!found) return null;
	let result;
	try {
		result = await verifyAuthenticationResponse({
			response,
			expectedChallenge: challenge,
			expectedOrigin: url.origin,
			expectedRPID: url.hostname,
			credential: found.credential,
			requireUserVerification: false
		});
	} catch (e) {
		console.warn('[passkeys] sign-in rejected:', e instanceof Error ? e.message : e);
		return null;
	}
	if (!result.verified) return null;
	return signInWithPasskey(db, found.passkeyId, result.authenticationInfo.newCounter, { userAgent });
}
