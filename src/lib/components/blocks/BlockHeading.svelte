<script>
	/** @type {{ block: import('#lib/types.js').HeadingBlock }} */
	let { block } = $props();
</script>

{#if block.body?.trim()}
	<h2 class="story-heading">{block.body}</h2>
{/if}

<style>
	/* Face, size and measure come from .prose-story h2; this adds the rule that
	   marks a new section. */
	.story-heading {
		margin-top: 3rem !important;
		padding-top: 1.4rem;
		border-top: 1px solid var(--color-pencil-soft);
	}
</style>
