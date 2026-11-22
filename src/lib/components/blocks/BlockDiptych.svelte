<script>
	import { imageUrl, imageSrcset } from '#lib/image.js';

	/** @type {{ block: import('#lib/types.js').DiptychBlock }} */
	let { block } = $props();

	const size = $derived(block.size || 'wide');
	const aspect = $derived(block.aspect || '4/5');
	const widths = [400, 600, 800, 1000];
	const sizes = '(min-width: 720px) 28rem, 92vw';
</script>

<figure
	class="story-figure"
	class:story-figure--wide={size === 'wide'}
	class:story-figure--full={size === 'full'}
>
	<div class="pair">
		<div class="frame" style:aspect-ratio={aspect}>
			<img
				src={imageUrl(block.left.imageId, { w: 800, q: 85, aspect })}
				srcset={imageSrcset(block.left.imageId, widths, { aspect })}
				{sizes}
				alt={block.left.alt ?? ''}
				loading="lazy"
				decoding="async"
			/>
			{#if block.left.caption}<span class="micro">{block.left.caption}</span>{/if}
		</div>
		<div class="frame" style:aspect-ratio={aspect}>
			<img
				src={imageUrl(block.right.imageId, { w: 800, q: 85, aspect })}
				srcset={imageSrcset(block.right.imageId, widths, { aspect })}
				{sizes}
				alt={block.right.alt ?? ''}
				loading="lazy"
				decoding="async"
			/>
			{#if block.right.caption}<span class="micro">{block.right.caption}</span>{/if}
		</div>
	</div>
	{#if block.caption}<figcaption>{block.caption}</figcaption>{/if}
</figure>

<style>
	.story-figure { margin: 2.5rem auto; width: 100%; max-width: min(60rem, 92vw); }
	.story-figure--full {
		max-width: none; width: 100vw;
		margin-left: calc(50% - 50vw);
		margin-right: calc(50% - 50vw);
		padding: 0 1.5rem;
	}
	.pair { display: grid; grid-template-columns: 1fr; gap: 0.75rem; }
	@media (min-width: 640px) {
		.pair { grid-template-columns: 1fr 1fr; gap: 1rem; }
	}
	.frame {
		position: relative;
		overflow: hidden;
		border-radius: 2px;
		background: var(--color-paper-deep);
		box-shadow: 0 1px 0 rgba(0, 0, 0, 0.04), 0 10px 24px -18px rgba(0, 0, 0, 0.3);
	}
	img { display: block; width: 100%; height: 100%; object-fit: cover; }
	.micro {
		position: absolute; bottom: 0.4rem; left: 0.6rem;
		font-family: var(--font-mono); font-size: 0.65rem; letter-spacing: 0.08em;
		color: rgba(245, 239, 225, 0.95);
		text-shadow: 0 1px 3px rgba(0, 0, 0, 0.6);
	}
	figcaption {
		font-family: var(--font-mono);
		font-size: 0.78rem;
		color: var(--color-pencil);
		margin-top: 0.7rem;
		max-width: 36rem;
		margin-left: auto;
		margin-right: auto;
	}
</style>
