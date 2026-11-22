<script>
	/** @type {{ block: import('#lib/types.js').DividerBlock }} */
	let { block } = $props();
	const place = $derived(block.place || '');
	const date = $derived(block.date || '');
</script>

<div class="postmark-divider" aria-hidden="true">
	<svg viewBox="0 0 160 160">
		<defs>
			<path id="pm-top-{block.id}" d="M 80 80 m -56 0 a 56 56 0 1 1 112 0" />
			<path id="pm-bot-{block.id}" d="M 80 80 m -56 0 a 56 56 0 1 0 112 0" />
		</defs>
		<!--
			Two arc segments instead of a closed ring so the dotted line doesn't
			cut through the place name (top) or the date (bottom). The arcs span
			from ~40° past 12 o'clock down to ~25° before 6 o'clock on each
			side. Centre is (80,80), radius 60.
				right arc:  (118.57, 34.04) → (105.36, 134.38)
				left arc:   (54.64, 134.38) → (41.43, 34.04)
		-->
		<path d="M 118.57 34.04 A 60 60 0 0 1 105.36 134.38" fill="none" stroke="currentColor" stroke-width="1.4" stroke-dasharray="2 3" />
		<path d="M 54.64 134.38 A 60 60 0 0 1 41.43 34.04" fill="none" stroke="currentColor" stroke-width="1.4" stroke-dasharray="2 3" />
		<circle cx="80" cy="80" r="48" fill="none" stroke="currentColor" stroke-width="1" />
		{#if place}
			<text font-family="JetBrains Mono, monospace" font-size="10" letter-spacing="2.2" fill="currentColor">
				<textPath href={`#pm-top-${block.id}`} startOffset="50%" text-anchor="middle">· {place.toUpperCase()} ·</textPath>
			</text>
		{/if}
		{#if date}
			<text font-family="JetBrains Mono, monospace" font-size="9" letter-spacing="1.5" fill="currentColor">
				<textPath href={`#pm-bot-${block.id}`} startOffset="50%" text-anchor="middle">{date}</textPath>
			</text>
		{/if}
		<g transform="translate(80 80)">
			<line x1="-12" y1="0" x2="12" y2="0" stroke="currentColor" stroke-width="1" />
			<line x1="0" y1="-12" x2="0" y2="12" stroke="currentColor" stroke-width="1" />
		</g>
	</svg>
</div>

<style>
	.postmark-divider {
		display: block;
		width: 120px;
		margin: 4rem auto !important;
		color: var(--color-postmark);
		opacity: 0.55;
		transform: rotate(-4deg);
	}
	.postmark-divider svg { width: 100%; height: auto; }
</style>
