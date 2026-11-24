/**
 * The link that connects the phone app to a site: its address and the token
 * the app signs its requests with, as one line a QR code can carry.
 *
 *   fartingaround://connect?site=https%3A%2F%2Fexample.org&token=…
 *
 * The app reads the same format (capture/…/data/SiteAddress.kt). It is a
 * private scheme on purpose: a camera app that scans it by accident has
 * nowhere to send the token.
 * @param {string} origin  the site's own address, e.g. https://example.org
 * @param {string} token   CAPTURE_API_TOKEN
 */
export function appConnectLink(origin, token) {
	return `fartingaround://connect?site=${encodeURIComponent(origin.replace(/\/+$/, ''))}&token=${encodeURIComponent(token)}`;
}
