<script>
	import { linkify } from '#lib/linkify.js';

	/** @type {{ block: import('#lib/types.js').TextBlock }} */
	let { block } = $props();

	/** @param {string} s */
	const escape = (s) =>
		s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

	// Split into paragraphs on double newline; preserve single newlines as <br>.
	// Escape first, then linkify [text](url) and bare URLs.
	const paragraphs = $derived(
		block.body
			.split(/\n{2,}/)
			.map((p) => p.trim())
			.filter(Boolean)
			.map((p) => linkify(escape(p)).replace(/\n/g, '<br>'))
	);
</script>

{#each paragraphs as p}
	<p>{@html p}</p>
{/each}
