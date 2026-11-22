<script>
	import StoryBlocks from './StoryBlocks.svelte';
	import { imageUrl, imageSrcset } from '#lib/image.js';

	/**
	 * A note (a story with no title) shown where it is listed: its first block,
	 * as content, not a card to tap through. specs/notes-titleless-posts.md.
	 *
	 * A photo links to the note's page; audio, video and text are the real
	 * blocks. Anything else (a photo dump, a diptych) shows its first picture.
	 * @type {{ slug: string, lead: any, thumbId?: string, sizes?: string }}
	 */
	let { slug, lead, thumbId = '', sizes = '92vw' } = $props();
	const href = $derived(`/stories/${slug}`);
	const live = $derived(['audio', 'video', 'text', 'pull', 'aside'].includes(lead?.type));
	const pictureId = $derived(lead?.type === 'photo' ? lead.imageId : thumbId);
	const long = $derived(lead?.type === 'text' && (lead.body?.length ?? 0) > 420);
	const count = $derived(lead?.type === 'dump' ? (lead.photos?.length ?? 0) : 0);
</script>

{#if live}
	<div class="note-blocks" class:note-blocks--long={long}>
		<StoryBlocks blocks={[lead]} {slug} />
	</div>
{:else if pictureId}
	<figure class="note-photo">
		<a {href}>
			<img
				src={imageUrl(pictureId, { w: 1200, q: 85 })}
				srcset={imageSrcset(pictureId, [400, 600, 800, 1200, 1600])}
				{sizes}
				alt={lead?.alt || ''}
				loading="lazy"
				decoding="async"
			/>
		</a>
		{#if lead?.caption || count > 1}
			<figcaption>
				{#if lead?.caption}{lead.caption}{/if}
				{#if count > 1}<span class="count">{count} photos</span>{/if}
			</figcaption>
		{/if}
	</figure>
{/if}

<style>
	.note-photo { margin: 0; }
	.note-photo a { display: block; }
	.note-photo img {
		display: block;
		width: 100%;
		height: auto;
		max-height: 80vh;
		object-fit: contain;
		background: var(--color-paper-deep);
		border-radius: 2px;
	}
	.note-photo figcaption {
		margin-top: 0.6rem;
		color: var(--color-ink-soft);
		font-size: 0.95rem;
		line-height: 1.5;
	}
	.count {
		font-family: var(--font-mono);
		font-size: 0.72rem;
		letter-spacing: 0.06em;
		color: var(--color-pencil);
	}
	figcaption .count:not(:first-child) { margin-left: 0.6em; }

	/* The blocks bring the story page's column and spacing with them; here
	   the card is the column. */
	.note-blocks :global(.prose-story) { max-width: none; margin: 0; padding: 0; }
	/* The blocks set their own margins with !important; the doubled class outranks them. */
	.note-blocks.note-blocks :global(.prose-story > *) { max-width: none; margin: 0 !important; }
	/* A long text note is cut short; "More" under it leads to the rest. */
	.note-blocks--long { max-height: 16rem; overflow: hidden; -webkit-mask-image: linear-gradient(#000 75%, transparent); mask-image: linear-gradient(#000 75%, transparent); }
</style>
