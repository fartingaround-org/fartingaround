<script>
	/** @type {{ block: import('#lib/types.js').AsideBlock }} */
	let { block } = $props();
</script>

<aside class="story-aside">
	{#if block.label}<span class="label">{block.label}</span>{/if}
	<div class="body">{block.body}</div>
</aside>

<style>
	.story-aside {
		font-family: var(--font-hand);
		font-size: 1.3rem;
		line-height: 1.35;
		color: var(--color-wax);
		max-width: 26rem;
		margin: 2rem auto !important;
		padding: 1rem 1.2rem;
		border-top: 1px dashed var(--color-pencil-soft);
		border-bottom: 1px dashed var(--color-pencil-soft);
	}
	.label {
		display: block;
		font-family: var(--font-mono);
		font-size: 0.65rem;
		letter-spacing: 0.2em;
		text-transform: uppercase;
		color: var(--color-pencil);
		margin-bottom: 0.4rem;
	}
</style>
