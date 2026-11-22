/**
 * The contact book: who the owner has let in, and the links that let them in.
 * Admin-only (checked in the admin layout). All rules live in
 * src/lib/server/readers.js.
 */
import { fail } from '@sveltejs/kit';
import { getDb } from '#lib/server/db.js';
import {
	ReaderError,
	banContact,
	createContact,
	listContacts,
	mintLink,
	removeContact,
	unbanContact,
	updateContact
} from '#lib/server/readers.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async () => {
	return { contacts: await listContacts(await getDb()) };
};

/**
 * Run one contact-book change; turn an owner mistake into a form error.
 * @template T
 * @param {() => Promise<T>} fn
 * @returns {Promise<{ ok: true, value: T } | { ok: false, error: string }>}
 */
async function attempt(fn) {
	try {
		return { ok: true, value: await fn() };
	} catch (e) {
		if (e instanceof ReaderError) return { ok: false, error: e.message === e.code ? messages[e.code] : e.message };
		throw e;
	}
}

const messages = {
	not_found: 'That person is no longer in the contact book.',
	banned: 'Unban this person before sending them a link.',
	not_banned: 'That person is not banned.',
	not_active: 'That person is not signed in.',
	invalid: 'Check the details and try again.'
};

/** @param {Request} request */
const fields = async (request) => Object.fromEntries(await request.formData());

/** @type {import('./$types').Actions} */
export const actions = {
	add: async ({ request }) => {
		const f = await fields(request);
		const db = await getDb();
		const r = await attempt(() => createContact(db, f));
		return r.ok ? { added: r.value } : fail(400, { error: r.error });
	},
	save: async ({ request }) => {
		const f = await fields(request);
		const db = await getDb();
		const r = await attempt(() => updateContact(db, String(f.id), f));
		return r.ok ? { saved: String(f.id) } : fail(400, { error: r.error, id: String(f.id) });
	},
	link: async ({ request, url }) => {
		const f = await fields(request);
		const db = await getDb();
		const r = await attempt(() => mintLink(db, String(f.id), { ttlHours: Number(f.ttlHours) || undefined }));
		if (!r.ok) return fail(400, { error: r.error, id: String(f.id) });
		// The only time the code exists outside the reader's hands: shown once, never stored.
		return { link: { id: String(f.id), url: `${url.origin}/in/${r.value.code}`, expiresAt: r.value.expiresAt } };
	},
	ban: async ({ request }) => {
		const f = await fields(request);
		const db = await getDb();
		const r = await attempt(() => banContact(db, String(f.id), { hideComments: f.hideComments === 'on' }));
		return r.ok ? { done: 'banned' } : fail(400, { error: r.error, id: String(f.id) });
	},
	unban: async ({ request }) => {
		const f = await fields(request);
		const db = await getDb();
		const r = await attempt(() => unbanContact(db, String(f.id)));
		return r.ok ? { done: 'unbanned' } : fail(400, { error: r.error, id: String(f.id) });
	},
	remove: async ({ request }) => {
		const f = await fields(request);
		const db = await getDb();
		const r = await attempt(() => removeContact(db, String(f.id), { comments: f.comments === 'delete' ? 'delete' : 'placeholder' }));
		return r.ok ? { done: 'removed' } : fail(400, { error: r.error });
	}
};
