<script>
	import { page } from '$app/state';
	import { getContext } from 'svelte';
	import { imageUrl } from '#lib/image.js';

	/** @type {{ block: import('#lib/types.js').VideoBlock }} */
	let { block } = $props();

	const slugFromParent = /** @type {(() => string | undefined) | undefined} */ (getContext('story-slug'));
	const slug = $derived(slugFromParent?.() ?? page.params.slug);
	const src = $derived(`/api/stories/${slug}/video/${block.id}`);
	const poster = $derived(block.posterImageId ? imageUrl(block.posterImageId, { w: 1200, fit: 'cover', q: 80 }) : undefined);

	const sizeClass = $derived(block.size || 'wide');

	// Without a poster or a saved shape the player starts as a thin strip and
	// jumps when the file's metadata arrives. Hold 16:9 until then, then take
	// the video's own shape (so a portrait clip isn't boxed into landscape).
	/** @type {string | undefined} */
	let measured = $state();
	let aspect = $derived(block.aspect || measured || '16/9');
	/** @param {Event & { currentTarget: HTMLVideoElement }} e */
	function onMeta(e) {
		const v = e.currentTarget;
		if (v.videoWidth && v.videoHeight) measured = `${v.videoWidth}/${v.videoHeight}`;
	}

	function fmtDuration(/** @type {number | undefined} */ ms) {
		if (!ms) return '';
		const s = Math.round(ms / 1000);
		const m = Math.floor(s / 60);
		const r = s % 60;
		return m > 0 ? `${m}:${String(r).padStart(2, '0')}` : `${r}s`;
	}
</script>

<figure class={`story-video size-${sizeClass}`} style={`--video-aspect: ${aspect}`}>
	<!-- svelte-ignore a11y_media_has_caption -->
	<video
		controls
		preload="metadata"
		playsinline
		{src}
		{poster}
		onloadedmetadata={onMeta}
	>
		<track kind="captions" />
	</video>
	{#if block.caption || block.durationMs}
		<figcaption>
			<span class="label">video</span>
			{#if block.durationMs}<span class="dur">{fmtDuration(block.durationMs)}</span>{/if}
			{#if block.caption}<span class="caption">— {block.caption}</span>{/if}
		</figcaption>
	{/if}
</figure>

<style>
	.story-video {
		margin: 2rem auto !important;
		display: grid;
		gap: 0.5rem;
	}
	/* Aspect-ratio honours the prop if set; otherwise let the browser size to
	   the loaded video's intrinsic dimensions. */
	.story-video video {
		width: 100%;
		height: auto;
		max-height: 80vh;
		display: block;
		background: var(--color-paper-deep);
		border-radius: 2px;
		aspect-ratio: var(--video-aspect, auto);
		object-fit: contain;
	}
	.size-inline { max-width: 38rem; }
	.size-wide { max-width: 56rem; }
	.size-full { max-width: 76rem; }

	figcaption {
		display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: baseline;
		font-family: var(--font-mono); font-size: 0.72rem;
		color: var(--color-pencil);
	}
	.label { letter-spacing: 0.18em; text-transform: uppercase; }
	.dur { font-variant-numeric: tabular-nums; }
	.caption { color: var(--color-ink-soft); font-style: italic; }
</style>
