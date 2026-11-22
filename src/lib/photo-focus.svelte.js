/**
 * "Open this photo" requests, from anywhere on a story page to whichever
 * photo dump holds the photo. The comment list sets `imageId` when a photo
 * comment's thumbnail is clicked; the dump that has that photo opens its
 * viewer on it (with the comments showing) and clears the request.
 *
 * Only ever written in the browser, in response to a click.
 */
export const photoFocus = $state(/** @type {{ imageId: string | null }} */ ({ imageId: null }));
