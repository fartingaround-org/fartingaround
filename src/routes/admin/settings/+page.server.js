/** Site-wide choices: the site's own name and words, who sees new stories, the Following list, and comment sections. */
import { fail } from '@sveltejs/kit';
import {
	authorName,
	commentsMode,
	defaultVisibility,
	followingPublic,
	locationPrecision,
	setAuthorName,
	setCommentsMode,
	setDefaultVisibility,
	setFollowingPublic,
	setLocationPrecision,
	setSiteIdentity,
	siteIdentity,
	SITE_DEFAULTS
} from '#lib/server/settings.js';
import { commentsModeOf } from '#lib/story-access.js';
import { locationPrecisionOf } from '#lib/location-privacy.js';
import { config } from '#lib/server/config.js';
import { qrSvg } from '#lib/server/qr.js';
import { appConnectLink } from '#lib/app-link.js';

/** @type {import('./$types').PageServerLoad} */
export const load = async ({ url }) => ({
	// What the site shows now, the words it falls back to, and the address it is named after until it has a name.
	site: await siteIdentity(url.host),
	siteDefaults: SITE_DEFAULTS,
	host: url.host,
	authorName: await authorName(),
	defaultVisibility: await defaultVisibility(),
	locationPrecision: await locationPrecision(),
	followingPublic: await followingPublic(),
	commentsMode: await commentsMode(),
	// The code that connects the phone app. It carries the site's token, so it
	// is only made when asked for (?phone=1), never on an ordinary visit to
	// this page, and it is drawn here on the server.
	phone: {
		available: Boolean(config.captureApiToken),
		qr: url.searchParams.get('phone') === '1' && config.captureApiToken ? await qrSvg(appConnectLink(url.origin, config.captureApiToken)) : null
	}
});

/** @type {import('./$types').Actions} */
export const actions = {
	save: async ({ request }) => {
		const f = await request.formData();
		const visibility = f.get('visibility');
		const mode = commentsModeOf(f.get('mode'));
		if (visibility !== 'public' && visibility !== 'private') return fail(400, { error: 'Choose public or private.' });
		if (!mode) return fail(400, { error: 'Choose who can see comments.' });
		const precision = locationPrecisionOf(f.get('locationPrecision'));
		if (!precision) return fail(400, { error: 'Choose how precisely locations are shown.' });
		await setAuthorName(f.get('authorName'));
		await setSiteIdentity({
			name: f.get('siteName'),
			tagline: f.get('tagline'),
			ownerName: f.get('ownerName'),
			ownerUrl: f.get('ownerUrl'),
			contactEmail: f.get('contactEmail'),
			supportUrl: f.get('supportUrl'),
			supportLabel: f.get('supportLabel'),
			homeEyebrow: f.get('homeEyebrow'),
			homeHeadline: f.get('homeHeadline'),
			homeIntro: f.get('homeIntro'),
			aboutHeadline: f.get('aboutHeadline'),
			about: f.get('about')
		});
		await setDefaultVisibility(visibility);
		await setLocationPrecision(precision);
		await setFollowingPublic(f.get('following') !== 'hidden');
		await setCommentsMode(mode);
		return { saved: true };
	}
};
