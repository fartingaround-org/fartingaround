<script>
	/** @type {{ block: import('#lib/types.js').PullBlock }} */
	let { block } = $props();
</script>

<blockquote class="pull">{block.body}</blockquote>

<style>
	.pull {
		font-family: var(--font-display);
		font-style: italic;
		font-variation-settings: 'opsz' 144;
		font-size: clamp(1.6rem, 3.5vw, 2.3rem);
		line-height: 1.2;
		color: var(--color-ink);
		text-align: center;
		max-width: 32rem;
		margin: 3.5rem auto !important;
		padding: 1rem 0;
		border: none;
		position: relative;
	}
	.pull::before, .pull::after {
		content: '"';
		color: var(--color-wax);
		font-size: 1.4em;
		line-height: 0;
		vertical-align: -0.15em;
	}
	.pull::before { margin-right: 0.15em; }
	.pull::after { margin-left: 0.1em; }
</style>
