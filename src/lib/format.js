/**
 * Format an ISO date as "April 12, 2026".
 * @param {string} iso
 */
export function formatDate(iso) {
	if (!iso) return '';
	const d = new Date(iso);
	if (Number.isNaN(d.getTime())) return iso;
	return d.toLocaleDateString('en-US', {
		year: 'numeric',
		month: 'long',
		day: 'numeric',
		timeZone: 'UTC'
	});
}

/**
 * @param {string} iso
 */
export function formatDateShort(iso) {
	if (!iso) return '';
	const d = new Date(iso);
	if (Number.isNaN(d.getTime())) return iso;
	const m = d.toLocaleDateString('en-US', { month: 'short', timeZone: 'UTC' });
	return `${m} ${d.getUTCDate()} · ${d.getUTCFullYear()}`;
}

/**
 * @param {string} iso
 */
export function getYear(iso) {
	if (!iso) return 0;
	const d = new Date(iso);
	return Number.isNaN(d.getTime()) ? 0 : d.getUTCFullYear();
}
