/** The reader's own page: passkeys, adding another device, signing out. */
import { fail, redirect } from '@sveltejs/kit';
import QRCode from 'qrcode';
import { getDb } from '#lib/server/db.js';
import { qrSvg } from '#lib/server/qr.js';
import {
	READER_COOKIE,
	ReaderError,
	endSession,
	issueReaderToken,
	listPasskeys,
	mintDeviceLink,
	readerTokenCreatedAt,
	removeContact,
	removePasskey
} from '#lib/server/readers.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ locals, url, setHeaders }) => {
	setHeaders({ 'cache-control': 'no-store' });
	const db = await getDb();
	return {
		me: locals.reader,
		passkeys: locals.reader ? await listPasskeys(db, locals.reader.id) : [],
		feedSince: locals.reader?.role === 'member' ? await readerTokenCreatedAt(db, locals.reader.id) : null,
		welcome: url.searchParams.has('welcome')
	};
};

/** @type {import('./$types').Actions} */
export const actions = {
	signout: async ({ cookies }) => {
		await endSession(await getDb(), cookies.get(READER_COOKIE));
		cookies.delete(READER_COOKIE, { path: '/' });
		throw redirect(303, '/');
	},
	/** The reader removes themselves: the same thing as the owner removing them. */
	deleteAccount: async ({ locals, request, cookies }) => {
		if (!locals.reader) return fail(401, { error: 'You are not signed in.' });
		const choice = (await request.formData()).get('comments') === 'delete' ? 'delete' : 'placeholder';
		await removeContact(await getDb(), locals.reader.id, { comments: choice });
		cookies.delete(READER_COOKIE, { path: '/' });
		throw redirect(303, '/');
	},
	removePasskey: async ({ locals, request }) => {
		if (!locals.reader) return fail(401, { error: 'You are not signed in.' });
		const id = String((await request.formData()).get('id') || '');
		await removePasskey(await getDb(), locals.reader.id, id);
		return { removed: true };
	},
	/** A member's personal feed link. Making a new one stops the old one working. */
	feed: async ({ locals, url }) => {
		if (locals.reader?.role !== 'member') return fail(403, { error: 'Personal feed links are for members.' });
		try {
			const token = encodeURIComponent(await issueReaderToken(await getDb(), locals.reader.id));
			const json = `${url.origin}/feed.json?token=${token}`;
			// The same link as a code, for the app's scanner. Drawn here: the link is a key.
			return { feed: { json, rss: `${url.origin}/rss.xml?token=${token}`, qr: await qrSvg(json) } };
		} catch (e) {
			if (e instanceof ReaderError) return fail(400, { error: 'You are no longer signed in.' });
			throw e;
		}
	},
	/** A short-lived link for the reader's own second device. */
	device: async ({ locals, url }) => {
		if (!locals.reader) return fail(401, { error: 'You are not signed in.' });
		try {
			const { code, expiresAt } = await mintDeviceLink(await getDb(), locals.reader.id);
			const link = `${url.origin}/in/${code}`;
			// Drawn on the server so the code never goes to a third-party QR service.
			const qr = await QRCode.toString(link, {
				type: 'svg',
				margin: 1,
				errorCorrectionLevel: 'M',
				color: { dark: '#1b1a16', light: '#f5efe1' }
			});
			return { device: { url: link, expiresAt, qr } };
		} catch (e) {
			if (e instanceof ReaderError) return fail(400, { error: 'You are no longer signed in.' });
			throw e;
		}
	}
};
