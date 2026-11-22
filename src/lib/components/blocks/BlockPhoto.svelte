<script>
	import { getContext } from 'svelte';
	import { imageUrl, imageSrcset } from '#lib/image.js';

	/** @type {{ block: import('#lib/types.js').PhotoBlock }} */
	let { block } = $props();

	// In a note (a story with no title) the photo is the post: unless the author
	// chose a frame, it is shown whole and sized to fit the screen instead of
	// being cropped to 4/5.
	const inNote = /** @type {(() => boolean) | undefined} */ (getContext('story-note'));
	const whole = $derived(!!inNote?.() && !block.aspect);

	const size = $derived(whole && !block.size ? 'wide' : block.size || 'inline');
	const aspect = $derived(block.aspect || '4/5');
	const dims = $derived(/** @type {{ w?: number, h?: number }} */ (block));

	const sizes = {
		inline: '(min-width: 720px) 36rem, 92vw',
		wide: '(min-width: 960px) 56rem, 92vw',
		full: '100vw'
	};

	const widthSet = {
		inline: [480, 640, 800, 1200],
		wide: [800, 1200, 1600, 2000],
		full: [800, 1200, 1600, 2000, 2800]
	};

	const srcset = $derived(imageSrcset(block.imageId, widthSet[size], whole ? {} : { aspect }));
	const src = $derived(imageUrl(block.imageId, { w: 1200, q: 85 }));
</script>

<figure
	class="story-figure"
	class:story-figure--wide={size === 'wide'}
	class:story-figure--full={size === 'full'}
>
	<div class="frame" class:frame--whole={whole} style:aspect-ratio={whole ? null : aspect}>
		<img
			{src}
			{srcset}
			width={whole ? dims.w : undefined}
			height={whole ? dims.h : undefined}
			sizes={sizes[size]}
			alt={block.alt ?? ''}
			loading="lazy"
			decoding="async"
		/>
	</div>
	{#if block.caption}<figcaption>{block.caption}</figcaption>{/if}
</figure>

<style>
	.story-figure { margin: 2.5rem auto; width: 100%; max-width: 36rem; }
	.story-figure--wide { max-width: min(56rem, 92vw); }
	.story-figure--full {
		max-width: none; width: 100vw;
		margin-left: calc(50% - 50vw);
		margin-right: calc(50% - 50vw);
	}
	.frame {
		overflow: hidden;
		border-radius: 2px;
		background: var(--color-paper-deep);
		box-shadow: 0 1px 0 rgba(0, 0, 0, 0.04), 0 12px 28px -18px rgba(0, 0, 0, 0.35);
	}
	.story-figure--full .frame { border-radius: 0; box-shadow: none; }
	img { display: block; width: 100%; height: 100%; object-fit: cover; }
	/* Whole photo: its own shape, never taller than the screen, the frame hugging it. */
	.frame--whole { width: fit-content; max-width: 100%; margin: 0 auto; }
	.frame--whole img { width: auto; height: auto; max-width: 100%; max-height: 80vh; object-fit: contain; }
	figcaption {
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.04em;
		color: var(--color-pencil);
		margin-top: 0.7rem;
		padding: 0 0.25rem;
		max-width: 36rem;
		margin-left: auto;
		margin-right: auto;
	}
	.story-figure--full figcaption { padding: 0 1.5rem; }
</style>
