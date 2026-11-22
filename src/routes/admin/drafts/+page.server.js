/**
 * List drafts captured from the phone (or anywhere else with the capture token).
 * Admin-only — checked in the admin layout.
 */

import { listDrafts } from '#lib/server/drafts.js';

/** @type {import('./$types').PageServerLoad} */
export async function load({}) {
	const drafts = await listDrafts();
	return { drafts };
}
