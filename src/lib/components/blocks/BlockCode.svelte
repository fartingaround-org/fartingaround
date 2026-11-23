<script>
	/** @type {{ block: import('#lib/types.js').CodeBlock }} */
	let { block } = $props();

	let copied = $state(false);
	async function copy() {
		try {
			await navigator.clipboard.writeText(block.body);
			copied = true;
			setTimeout(() => (copied = false), 1800);
		} catch {
			// No clipboard here (an old browser, a page not on https): the text can still be selected by hand.
		}
	}
</script>

{#if block.body?.trim()}
	<figure class="story-code">
		<!-- tabindex: a long line scrolls sideways, and a keyboard has to be able to reach it to scroll -->
		<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
		<pre tabindex="0"><code>{block.body}</code></pre>
		<button type="button" class="copy" onclick={copy} aria-live="polite">{copied ? 'Copied' : 'Copy'}</button>
		{#if block.caption}<figcaption>{block.caption}</figcaption>{/if}
	</figure>
{/if}

<style>
	.story-code {
		position: relative;
		max-width: var(--measure);
		margin: 1.4rem auto !important;
	}
	pre {
		margin: 0;
		padding: 1rem 4.6rem 1rem 1.1rem;
		overflow-x: auto;
		background: var(--color-paper-deep);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-mono);
		font-size: 0.85rem;
		line-height: 1.6;
		color: var(--color-ink);
		white-space: pre;
		tab-size: 2;
	}
	pre:focus-visible { outline: 2px solid var(--color-wax); outline-offset: 2px; }
	code { font: inherit; }
	.copy {
		position: absolute;
		top: 0.45rem;
		right: 0.45rem;
		min-height: 2rem;
		padding: 0 0.7rem;
		font-family: var(--font-mono);
		font-size: 0.65rem;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: var(--color-ink-soft);
		background: var(--color-paper);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 2px;
		cursor: pointer;
	}
	.copy:hover { color: var(--color-ink); border-color: var(--color-ink); }
	figcaption {
		margin-top: 0.5rem;
		font-family: var(--font-mono);
		font-size: 0.72rem;
		color: var(--color-pencil);
	}
</style>
