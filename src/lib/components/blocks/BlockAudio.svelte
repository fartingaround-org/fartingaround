<script>
	import { page } from '$app/state';
	import { getContext } from 'svelte';

	/** @type {{ block: import('#lib/types.js').AudioBlock }} */
	let { block } = $props();

	// We're inside a story page so the slug is in the URL — grab it from
	// route params so we don't have to pass it down through props. The
	// public playback endpoint at /api/stories/:slug/audio/:blockId resolves
	// the block by id back to its r2Key and streams from R2.
	const slugFromParent = /** @type {(() => string | undefined) | undefined} */ (getContext('story-slug'));
	const slug = $derived(slugFromParent?.() ?? page.params.slug);
	const src = $derived(`/api/stories/${slug}/audio/${block.id}`);

	function fmtDuration(/** @type {number | undefined} */ ms) {
		if (!ms) return '';
		const s = Math.round(ms / 1000);
		return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
	}

	// ── Follow-along transcript ──────────────────────────────────────────────
	// When the block carries word timings (recordings transcribed in the app),
	// the word being spoken is emphasised as the clip plays. Without timings the
	// transcript is simply shown as text.
	/** @type {HTMLAudioElement | undefined} */
	let audioEl = $state();
	let time = $state(0);
	let playing = $state(false);
	const words = $derived(block.words?.length ? block.words : null);

	// currentTime only fires `timeupdate` a few times a second, too coarse for
	// single words, so poll it each frame while playing.
	$effect(() => {
		if (!playing || !words || typeof requestAnimationFrame === 'undefined') return;
		let raf = 0;
		const tick = () => {
			if (audioEl) time = audioEl.currentTime;
			raf = requestAnimationFrame(tick);
		};
		raf = requestAnimationFrame(tick);
		return () => cancelAnimationFrame(raf);
	});
</script>

<figure class="story-audio">
	<audio
		controls
		preload="metadata"
		{src}
		bind:this={audioEl}
		onplay={() => (playing = true)}
		onpause={() => (playing = false)}
		onseeked={() => { if (audioEl) time = audioEl.currentTime; }}
		onended={() => { playing = false; time = 0; }}
	>
		<a href={src}>Play audio</a>
	</audio>
	{#if words}
		<!-- `following` only while a play-through is under way, so the transcript
		     reads as ordinary text before and after. -->
		<p class="transcript timed" class:following={playing || time > 0}>
			{#each words as w, i (i)}<span class:said={time >= w.e} class:saying={time >= w.s && time < w.e}>{w.t}</span>{' '}{/each}
		</p>
	{:else if block.transcript}
		<p class="transcript">{block.transcript}</p>
	{/if}
	<!-- Last in the DOM (a11y: figcaption must be first or last child of
	     figure); grid `order` keeps it visually between audio and transcript. -->
	<figcaption>
		<span class="label">audio</span>
		{#if block.durationMs}<span class="dur">{fmtDuration(block.durationMs)}</span>{/if}
		{#if block.caption}<span class="caption">— {block.caption}</span>{/if}
	</figcaption>
</figure>

<style>
	.story-audio {
		max-width: 38rem;
		margin: 2rem auto !important;
		padding: 1rem 1.2rem;
		background: var(--color-paper-deep);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 4px;
		display: grid;
		gap: 0.6rem;
	}
	audio { width: 100%; order: 1; }
	figcaption {
		order: 2;
		display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: baseline;
		font-family: var(--font-mono); font-size: 0.72rem;
		color: var(--color-pencil);
	}
	.transcript {
		order: 3;
		margin: 0.2rem 0 0; line-height: 1.65;
		color: var(--color-ink-soft);
	}
	/* A plain transcript keeps its own line breaks. */
	.transcript:not(.timed) { white-space: pre-wrap; }
	/* While playing: words still to come are quiet, words said are normal, and
	   the word being spoken is heavier. The weight comes from a stroke rather
	   than font-weight so the line doesn't reflow as the highlight moves. */
	.transcript.following span { color: var(--color-pencil); transition: color 0.12s ease; }
	.transcript.following span.said { color: var(--color-ink-soft); }
	.transcript.following span.saying { color: var(--color-ink); -webkit-text-stroke: 0.6px currentColor; }
	.label { letter-spacing: 0.18em; text-transform: uppercase; }
	.dur { font-variant-numeric: tabular-nums; }
	.caption { color: var(--color-ink-soft); font-style: italic; }
</style>
