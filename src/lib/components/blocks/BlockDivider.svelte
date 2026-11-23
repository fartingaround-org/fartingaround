<script>
	/** @type {{ block: import('#lib/types.js').DividerBlock }} */
	let { block } = $props();
	const place = $derived((block.place || '').trim());
	const date = $derived((block.date || '').trim());
</script>

<!--
	A postmark between two parts of a story. The mark is decoration; the place
	and date are set as ordinary text under it. They used to run round the ring
	at about 7px, which nobody could read and a screen reader never saw.
-->
<div class="postmark-divider">
	<svg viewBox="0 0 160 160" aria-hidden="true">
		<circle cx="80" cy="80" r="60" fill="none" stroke="currentColor" stroke-width="1.4" stroke-dasharray="2 3" />
		<circle cx="80" cy="80" r="48" fill="none" stroke="currentColor" stroke-width="1" />
		<g transform="translate(80 80)">
			<line x1="-12" y1="0" x2="12" y2="0" stroke="currentColor" stroke-width="1" />
			<line x1="0" y1="-12" x2="0" y2="12" stroke="currentColor" stroke-width="1" />
		</g>
	</svg>
	{#if place || date}
		<p class="where">
			{#if place}<span class="place">{place}</span>{/if}
			{#if place && date}<span class="dot" aria-hidden="true">·</span>{/if}
			{#if date}<span class="date">{date}</span>{/if}
		</p>
	{/if}
</div>

<style>
	.postmark-divider {
		display: block;
		margin: 3.5rem auto !important;
		text-align: center;
		color: var(--color-postmark);
	}
	.postmark-divider svg {
		display: block;
		width: 72px;
		height: auto;
		margin: 0 auto;
		opacity: 0.55;
		transform: rotate(-4deg);
	}
	/* .prose-story p sets a measure and margins; this line is a label, not prose. */
	.where {
		max-width: 28rem !important;
		margin: 0.9rem auto 0 !important;
		font-family: var(--font-mono);
		font-size: 0.78rem;
		line-height: 1.5;
		letter-spacing: 0.16em;
		text-transform: uppercase;
		color: var(--color-ink-soft);
		hyphens: none;
	}
	.dot { margin: 0 0.5em; color: var(--color-pencil); }
</style>
