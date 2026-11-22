<script>
	import { page } from '$app/state';
	import { getContext } from 'svelte';
	import { imageUrl, imageSrcset } from '#lib/image.js';
	import DumpMap from './DumpMap.svelte';
	import { enhance } from '$app/forms';
	import { commentHtml, commentDate } from '#lib/comment-html.js';
	import { photoFocus } from '#lib/photo-focus.svelte.js';

	/** @type {{ block: import('#lib/types.js').DumpBlock }} */
	let { block } = $props();

	const size = $derived(block.size || 'wide');
	const rowSize = $derived(block.rowSize || 'm');
	/** @type {import('#lib/types.js').DumpPhoto[]} */
	const photos = $derived((block.photos || []).filter((p) => p && p.imageId));
	const mapMode = $derived(block.map === 'pins' || block.map === 'path' ? block.map : 'none');
	const hasLocated = $derived(photos.some((p) => p.coords));

	// Justified rows without JS: each tile's flex-grow and basis are
	// proportional to its aspect ratio, so every tile in a row ends up the
	// same height and nothing is cropped. Photos uploaded without dimensions
	// (shouldn't happen via the admin, but be forgiving) fall back to 3/2.
	/** @param {import('#lib/types.js').DumpPhoto} p */
	const ratio = (p) => (p.w && p.h ? p.w / p.h : 1.5);

	const thumbWidths = [320, 480, 720, 960, 1280];
	const thumbSizes = '(min-width: 1100px) 28vw, (min-width: 640px) 40vw, 60vw';

	// A long dump is dozens of screens on a phone. Past this many photos the
	// grid starts folded there (CSS decides — wide screens always show it all)
	// behind a "Show all" button. The lightbox still pages through everything.
	const FOLD_AFTER = 24;
	let expanded = $state(false);
	// Sections: a photo with a `section` title starts a new group. Each group gets a
	// heading and a "jump to" link; `i` stays the photo's index in the whole dump,
	// which is what the viewer and the map use. A dump with sections is not folded:
	// the jump links do that job.
	const sections = $derived.by(() => {
		/** @type {{ title: string, id: string, items: { p: import('#lib/types.js').DumpPhoto, i: number }[] }[]} */
		const out = [];
		photos.forEach((p, i) => {
			const title = typeof p.section === 'string' ? p.section.trim() : '';
			if (title || out.length === 0) out.push({ title, id: `${block.id}-s${out.length}`, items: [] });
			out[out.length - 1].items.push({ p, i });
		});
		return out;
	});
	const sectioned = $derived(sections.some((s) => s.title));
	const foldable = $derived(photos.length > FOLD_AFTER && !sectioned);

	// ── Comments about a photo ──────────────────────────────────────────────
	// The story page loads the story's comments; the ones that point at a photo
	// of this dump are shown with that photo. Elsewhere (the editor's preview)
	// there are none and all of this stays hidden.
	/** @type {import('#lib/server/comments.js').PublicComment[]} */
	const storyComments = $derived(page.data?.comments ?? []);
	/** @type {{ enabled: boolean, as: string | null } | undefined} */
	const commenting = $derived(page.data?.commenting);
	const byPhoto = $derived.by(() => {
		/** @type {Map<string, import('#lib/server/comments.js').PublicComment[]>} */
		const m = new Map();
		for (const c of storyComments) {
			if (!c.photoId) continue;
			m.set(c.photoId, [...(m.get(c.photoId) ?? []), c]);
		}
		return m;
	});
	/** How many comments (replies included) a photo has. @param {string} imageId */
	const countFor = (imageId) => (byPhoto.get(imageId) ?? []).reduce((n, c) => n + (c.deleted ? 0 : 1) + c.replies.length, 0);
	/** The comments panel in the viewer. */
	let panel = $state(false);
	let posting = $state(false);
	// A photo comment's thumbnail in the comment list asks for its photo.
	$effect(() => {
		const want = photoFocus.imageId;
		if (!want) return;
		const i = photos.findIndex((p) => p.imageId === want);
		if (i < 0) return;
		photoFocus.imageId = null;
		playing = false;
		index = i;
		panel = true;
	});

	// ── Lightbox ────────────────────────────────────────────────────────────
	let index = $state(-1);
	const open = $derived(index >= 0);
	const current = $derived(open ? photos[index] : null);
	/** A failed post about the photo on screen, from the page's form result. */
	const postError = $derived(page.form?.commentError && page.form?.photoId && page.form.photoId === photos[index]?.imageId ? page.form.commentError : '');

	/** @param {number} i */
	function show(i) { index = i; }
	function close() { index = -1; playing = false; panel = false; stopAudio(); exitFullscreen(); }
	function next() { if (photos.length) index = (index + 1) % photos.length; }
	function prev() { if (photos.length) index = (index - 1 + photos.length) % photos.length; }

	// ── Slideshow ───────────────────────────────────────────────────────────
	let playing = $state(false);
	const intervalMs = $derived((block.slideshowSeconds || 5) * 1000);
	/** @type {HTMLDivElement | undefined} */
	let lightboxEl = $state();

	function startSlideshow() {
		if (!photos.length) return;
		unlockAudio();
		index = 0;
		panel = false;
		playing = true;
	}
	function togglePlay() {
		playing = !playing;
		// A slideshow is for watching: the comments step aside while it runs.
		if (playing) panel = false;
		if (!current?.audio || audioDone) return;
		// Mid-caption: pause/resume the voice with the slideshow.
		if (playing) playAudio();
		else audioEl?.pause();
	}

	// Advance on a timer while playing — but only once the current photo's
	// spoken caption (if any) has finished. Re-arms whenever the index changes,
	// so a manual arrow press restarts the countdown instead of cutting it short.
	$effect(() => {
		if (!open || !playing || !audioDone) return;
		void index;
		const t = setTimeout(next, intervalMs);
		return () => clearTimeout(t);
	});

	// ── Audio captions ──────────────────────────────────────────────────────
	// One <audio> element for the whole lightbox, src swapped per photo. (A
	// fresh element per slide would be blocked by iOS Safari after the first
	// one, since later plays aren't inside a tap.) It's rendered even while the
	// lightbox is closed so a tap on "Slideshow" can prime it.
	const slugFromParent = /** @type {(() => string | undefined) | undefined} */ (getContext('story-slug'));
	const slug = $derived(slugFromParent?.() ?? page.params.slug);
	/** @type {HTMLAudioElement | undefined} */
	let audioEl = $state();
	/** Spoken caption has finished (or there isn't one) → countdown may run. */
	let audioDone = $state(true);
	/** Audio has started for this photo and hasn't ended (paused counts). */
	let audioActive = $state(false);
	/** Actively playing right now. */
	let narrating = $state(false);
	let audioTime = $state(0);
	/** Sound off, clip still running: the words keep following and the slideshow keeps its timing. Stays set across photos. */
	let muted = $state(false);
	const audioSrc = $derived(current?.audio ? `/api/stories/${slug}/audio/${current.audio.id}` : '');

	/** iOS: an element that has been load()ed inside a tap may play() later. */
	function unlockAudio() {
		if (audioEl && audioEl.paused && !audioActive) {
			try { audioEl.load(); } catch { /* ignore */ }
		}
	}
	function stopAudio() {
		if (audioEl) { audioEl.pause(); try { audioEl.currentTime = 0; } catch { /* not loaded */ } }
		narrating = false;
		audioActive = false;
		audioTime = 0;
	}
	function playAudio() {
		if (!audioEl || !current?.audio) return;
		audioEl.play().then(
			() => { audioActive = true; },
			() => { audioDone = true; } // autoplay refused: don't stall the show
		);
	}
	/** The 🔊 button: while a clip is running it only mutes or unmutes, so the
	 *  words carry on; otherwise it starts the clip, with sound. */
	function toggleAudio() {
		if (!audioEl || !current?.audio) return;
		if (narrating) {
			muted = !muted;
			audioEl.muted = muted;
		} else {
			if (audioDone && audioEl.ended) { try { audioEl.currentTime = 0; } catch { /* ignore */ } }
			muted = false;
			audioEl.muted = false;
			playAudio();
		}
	}
	function onAudioEnded() {
		narrating = false;
		audioActive = false;
		audioDone = true;
	}
	function onAudioError() {
		narrating = false;
		audioActive = false;
		audioDone = true;
	}

	// Per-photo setup: reset the voice, point the element at the new clip, and
	// in slideshow mode start speaking (the countdown waits for us).
	$effect(() => {
		void index;
		if (!open) return;
		stopAudio();
		const a = current?.audio;
		audioDone = !a;
		if (!a) return;
		queueMicrotask(() => { if (playing) playAudio(); });
	});

	// Word highlighting needs finer timing than `timeupdate` gives (~4 Hz).
	$effect(() => {
		if (!narrating || typeof requestAnimationFrame === 'undefined') return;
		let raf = 0;
		const tick = () => {
			if (audioEl) audioTime = audioEl.currentTime;
			raf = requestAnimationFrame(tick);
		};
		raf = requestAnimationFrame(tick);
		return () => cancelAnimationFrame(raf);
	});

	/** Words as spoken, or null when there's nothing to light up. */
	const words = $derived(current?.audio?.words?.length ? current.audio.words : null);
	const showWords = $derived(!!words && audioActive);
	/** Caption text once the voice is done: the written one, else the transcript. */
	const captionText = $derived(current ? (current.caption ?? current.audio?.transcript ?? '') : '');

	// Fullscreen (for a TV / projector). Best-effort: unsupported on iOS Safari.
	let fullscreen = $state(false);
	function toggleFullscreen() {
		if (typeof document === 'undefined') return;
		if (document.fullscreenElement) document.exitFullscreen?.();
		else lightboxEl?.requestFullscreen?.().catch(() => {});
	}
	function exitFullscreen() {
		if (typeof document !== 'undefined' && document.fullscreenElement) document.exitFullscreen?.();
	}
	function onFullscreenChange() {
		fullscreen = typeof document !== 'undefined' && !!document.fullscreenElement;
	}

	/** @param {KeyboardEvent} e */
	function onKey(e) {
		if (!open) return;
		// Typing a comment: arrows and space belong to the text box.
		const t = /** @type {HTMLElement | null} */ (e.target);
		if (t && (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT')) {
			if (e.key === 'Escape') t.blur();
			return;
		}
		if (e.key === 'Escape') { if (panel) panel = false; else close(); }
		else if (e.key === 'ArrowRight') next();
		else if (e.key === 'ArrowLeft') prev();
		else if (e.key === ' ') { e.preventDefault(); togglePlay(); }
		else if (e.key === 'f' || e.key === 'F') toggleFullscreen();
	}

	// Lock page scroll while the lightbox is up.
	$effect(() => {
		if (typeof document === 'undefined') return;
		document.documentElement.style.overflow = open ? 'hidden' : '';
		return () => { document.documentElement.style.overflow = ''; };
	});

	// Warm the neighbours so arrowing through feels instant.
	$effect(() => {
		if (!open || typeof Image === 'undefined') return;
		for (const j of [index + 1, index - 1]) {
			const p = photos[(j + photos.length) % photos.length];
			if (p) new Image().src = lbSrc(p);
		}
	});

	/** @param {import('#lib/types.js').DumpPhoto} p */
	const lbSrc = (p) => imageUrl(p.imageId, { w: 2000, q: 88, fit: 'scale-down' });

	// Swipe: horizontal pointer drag on the stage flips photos.
	let swipeX = 0;
	let swipeY = 0;
	/** @param {PointerEvent} e */
	function onSwipeStart(e) { swipeX = e.clientX; swipeY = e.clientY; }
	/** @param {PointerEvent} e */
	function onSwipeEnd(e) {
		const dx = e.clientX - swipeX;
		const dy = e.clientY - swipeY;
		if (Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(dy) * 1.5) dx < 0 ? next() : prev();
	}
</script>

<svelte:document onfullscreenchange={onFullscreenChange} />
<svelte:window onkeydown={onKey} />

<figure
	class="dump"
	class:dump--wide={size === 'wide'}
	class:dump--full={size === 'full'}
	class:rows-s={rowSize === 's'}
	class:rows-l={rowSize === 'l'}
>
	{#if sectioned}
		<nav class="jump" aria-label="Sections of this photo dump">
			<span class="jump-label">Jump to</span>
			{#each sections as s (s.id)}{#if s.title}<a href={`#${s.id}`}>{s.title}</a>{/if}{/each}
		</nav>
	{/if}
	{#each sections as s (s.id)}
	{#if s.title}<h3 class="section" id={s.id}>{s.title} <span>{s.items.length}</span></h3>{/if}
	<div class="grid" class:folded={foldable && !expanded} role="list">
		{#each s.items as { p, i } (p.imageId + i)}
			<button
				type="button"
				class="tile"
				style:--r={ratio(p)}
				onclick={() => show(i)}
				aria-label={(p.alt || p.caption || `Open photo ${i + 1} of ${photos.length}`) + (countFor(p.imageId) ? `, ${countFor(p.imageId)} ${countFor(p.imageId) === 1 ? 'comment' : 'comments'}` : '')}
			>
				<img
					src={imageUrl(p.imageId, { w: 720, q: 82 })}
					srcset={imageSrcset(p.imageId, thumbWidths, { fit: 'scale-down' })}
					sizes={thumbSizes}
					alt={p.alt ?? ''}
					width={p.w}
					height={p.h}
					loading="lazy"
					decoding="async"
					draggable="false"
				/>
				{#if countFor(p.imageId)}<span class="tile-count" aria-hidden="true">{countFor(p.imageId)}</span>{/if}
			</button>
		{/each}
	</div>
	{/each}
	{#if foldable && !expanded}
		<button type="button" class="show-all" onclick={() => (expanded = true)}>Show all {photos.length} photos</button>
	{/if}

	<div class="rail">
		<span aria-hidden="true">{photos.length} {photos.length === 1 ? 'photo' : 'photos'}{#if photos.length > 1} · tap to enlarge{/if}</span>
		{#if photos.length > 1}
			<button type="button" class="play" onclick={startSlideshow}>▶ Slideshow</button>
		{/if}
	</div>

	{#if mapMode !== 'none' && hasLocated}
		<DumpMap {photos} mode={mapMode} onOpen={show} />
	{/if}

	{#if block.caption}<figcaption>{block.caption}</figcaption>{/if}
</figure>

<audio
	bind:this={audioEl}
	src={audioSrc || undefined}
	preload="auto"
	onplaying={() => { narrating = true; audioActive = true; }}
	onpause={() => (narrating = false)}
	onended={onAudioEnded}
	onerror={onAudioError}
	hidden
></audio>

{#if open && current}
	<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
	<div
		class="lightbox"
		class:playing
		class:captioning={showWords}
		class:with-panel={panel}
		bind:this={lightboxEl}
		role="dialog"
		aria-modal="true"
		aria-label="Photo viewer"
		tabindex="-1"
		onclick={close}
	>
		<button type="button" class="lb-close" onclick={close} aria-label="Close" title="Close (Esc)">×</button>

		{#if photos.length > 1}
			<button type="button" class="lb-nav lb-prev" onclick={(e) => { e.stopPropagation(); prev(); }} aria-label="Previous photo" title="Previous (←)">‹</button>
			<button type="button" class="lb-nav lb-next" onclick={(e) => { e.stopPropagation(); next(); }} aria-label="Next photo" title="Next (→)">›</button>
		{/if}

		<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
		<div
			class="lb-stage"
			onclick={(e) => e.stopPropagation()}
			onpointerdown={onSwipeStart}
			onpointerup={onSwipeEnd}
		>
			{#key index}
				<img class="lb-img" class:fade={playing} src={lbSrc(current)} alt={current.alt ?? ''} draggable="false" />
			{/key}
			{#if playing && audioDone}
				<!-- Countdown to the next photo; restarts with each slide, and on a
				     photo with a spoken caption only begins once it has been said. -->
				{#key index}
					<span class="lb-progress" style:animation-duration="{intervalMs}ms" aria-hidden="true"></span>
				{/key}
			{/if}
		</div>

		<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
		<div class="lb-bar" onclick={(e) => e.stopPropagation()}>
			<span class="lb-caption" class:lb-caption--words={showWords}>
				{#if showWords && words}
					{#each words as w, i (i)}<span
							class="lb-word"
							class:said={audioTime >= w.e}
							class:saying={audioTime >= w.s && audioTime < w.e}
						>{w.t}</span>{' '}{/each}
				{:else}
					{captionText}
				{/if}
				{#if photos.length > 1}<span class="lb-count">{index + 1} / {photos.length}</span>{/if}
			</span>
			{#if current.audio}
				<button type="button" class="lb-toggle lb-audio" class:on={narrating && !muted} onclick={toggleAudio} aria-label={!narrating ? 'Play audio caption' : muted ? 'Unmute audio caption' : 'Mute audio caption'}>
					{muted ? '🔇' : '🔊'}
				</button>
			{/if}
			{#if commenting?.enabled && !playing && (commenting.as || countFor(current.imageId))}
				<button type="button" class="lb-toggle" class:on={panel} onclick={() => (panel = !panel)} aria-expanded={panel}>
					{countFor(current.imageId) ? `Comments · ${countFor(current.imageId)}` : 'Comment'}
				</button>
			{/if}
			{#if photos.length > 1}
				<button type="button" class="lb-toggle" class:on={playing} onclick={togglePlay} aria-label={playing ? 'Pause (space)' : 'Play slideshow (space)'}>
					{playing ? '❚❚ Pause' : '▶ Play'}
				</button>
			{/if}
			<button type="button" class="lb-toggle" class:on={fullscreen} onclick={toggleFullscreen} aria-label="Toggle fullscreen (f)">
				⛶ {fullscreen ? 'Exit' : 'Fullscreen'}
			</button>
		</div>

		{#if panel && commenting?.enabled}
			{@const thread = byPhoto.get(current.imageId) ?? []}
			<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
			<aside class="lb-panel" aria-label="Comments on this photo" onclick={(e) => e.stopPropagation()}>
				<header class="lb-panel-head">
					<h3>On this photo</h3>
					<button type="button" class="lb-panel-close" onclick={() => (panel = false)} aria-label="Hide comments">×</button>
				</header>

				{#if thread.length === 0}
					<p class="lb-panel-empty">Nothing yet. {commenting.as ? 'Say something about it.' : ''}</p>
				{/if}
				{#each thread as c (c.id)}
					{#each [c, ...c.replies] as one, n (one.id)}
						<article class="lb-comment" class:reply={n > 0}>
							{#if one.deleted}
								<p class="lb-gone">[deleted]</p>
							{:else}
								<p class="lb-who">
									<strong>{one.name}</strong>
									{#if one.byOwner}<span class="lb-tag">author</span>{/if}
									<time datetime={one.createdAt}>{commentDate(one.createdAt)}</time>
								</p>
								<div class="lb-body">{@html commentHtml(one.body)}</div>
							{/if}
						</article>
					{/each}
				{/each}

				{#if commenting.as}
					<form
						method="POST"
						action="?/comment"
						class="lb-form"
						use:enhance={() => {
							posting = true;
							return async ({ update }) => {
								// Reload the story's comments in place; the viewer stays open.
								await update();
								posting = false;
							};
						}}
					>
						<input type="hidden" name="photoId" value={current.imageId} />
						<textarea name="body" rows="3" maxlength="4000" required aria-label={`Comment on this photo as ${commenting.as}`} placeholder={`Comment as ${commenting.as}`}></textarea>
						{#if postError}<p class="lb-error" role="alert">{postError}</p>{/if}
						<button type="submit" disabled={posting}>{posting ? 'Posting…' : 'Post'}</button>
					</form>
					{#if thread.length}<p class="lb-fine">Reply to, edit or delete a comment in the list under the story.</p>{/if}
				{:else}
					<p class="lb-fine"><a href="/signin">Sign in</a> to comment.</p>
				{/if}
			</aside>
		{/if}
	</div>
{/if}

<style>
	.dump {
		--row-h: 12rem;
		margin: 2.5rem auto;
		width: 100%;
		max-width: 36rem;
	}
	.dump.rows-s { --row-h: 9rem; }
	.dump.rows-l { --row-h: 16rem; }
	@media (max-width: 640px) {
		.dump { --row-h: 7.5rem; }
		.dump.rows-s { --row-h: 5.5rem; }
		.dump.rows-l { --row-h: 10rem; }
	}
	.dump--wide { max-width: min(64rem, 94vw); }
	.dump--full {
		max-width: none;
		width: 100vw;
		margin-left: calc(50% - 50vw);
		margin-right: calc(50% - 50vw);
	}

	.grid {
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
	}
	/* Spacer that soaks up the slack on the last row so a short final row
	   doesn't balloon to fill the width. */
	.grid::after {
		content: '';
		flex-grow: 1000000;
	}
	.tile {
		flex-grow: calc(var(--r) * 1000);
		flex-basis: calc(var(--r) * var(--row-h));
		aspect-ratio: var(--r);
		min-width: 0;
		padding: 0;
		border: none;
		border-radius: 1px;
		overflow: hidden;
		background: var(--color-paper-deep);
		cursor: zoom-in;
		display: block;
	}
	.tile { position: relative; }
	/* How many comments this photo has. */
	.tile-count {
		position: absolute; right: 4px; bottom: 4px;
		min-width: 1.35rem; height: 1.35rem; padding: 0 0.35rem; box-sizing: border-box;
		display: grid; place-items: center;
		border-radius: 999px 999px 999px 2px;
		background: rgba(12, 11, 9, 0.78); color: #f5efe1;
		font-family: var(--font-mono); font-size: 0.68rem; line-height: 1;
	}
	.tile img {
		display: block;
		width: 100%;
		height: 100%;
		object-fit: cover;
		user-select: none;
		transition: filter 0.2s ease;
	}
	.tile:hover img { filter: brightness(1.04); }

	.rail {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 1rem;
		font-family: var(--font-mono);
		font-size: 0.62rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--color-pencil);
		margin-top: 0.5rem;
		padding: 0 0.25rem;
	}
	.play {
		font: inherit;
		letter-spacing: inherit;
		text-transform: inherit;
		color: var(--color-ink);
		background: transparent;
		border: 1px solid var(--color-pencil-soft);
		border-radius: 2px;
		padding: 0.3rem 0.6rem;
		cursor: pointer;
	}
	.play:hover { border-color: var(--color-ink); }
	.dump--full .rail { padding: 0 1.5rem; }

	figcaption {
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.04em;
		color: var(--color-pencil);
		margin-top: 0.7rem;
		padding: 0 0.25rem;
		max-width: 36rem;
	}
	.dump--full figcaption { padding: 0 1.5rem; }

	/* ── Lightbox ─────────────────────────────────────────────────────────── */
	.lightbox {
		/* The viewer is dark in either theme; its light chips need dark text. */
		--color-ink: #1b1a16;
		position: fixed;
		inset: 0;
		z-index: 100;
		height: 100vh;
		height: 100dvh;
		overscroll-behavior: contain;
		background: rgba(12, 11, 9, 0.94);
		display: grid;
		grid-template-rows: 1fr auto;
		place-items: center;
		padding: 2.5rem 1rem 1rem;
		box-sizing: border-box;
		animation: fade 0.18s ease;
	}
	@keyframes fade { from { opacity: 0; } to { opacity: 1; } }

	.lb-stage {
		position: relative;
		max-width: 100%;
		max-height: 100%;
		min-width: 0;
		min-height: 0;
		display: grid;
		place-items: center;
		touch-action: pan-y;
	}
	.lb-img.fade { animation: lbfade 0.6s ease; }
	@keyframes lbfade { from { opacity: 0; } to { opacity: 1; } }
	.lb-progress {
		position: absolute;
		left: 0;
		bottom: 0;
		height: 2px;
		width: 100%;
		background: rgba(245, 239, 225, 0.7);
		transform-origin: left;
		animation: lbprogress linear forwards;
	}
	@keyframes lbprogress { from { transform: scaleX(0); } to { transform: scaleX(1); } }
	/* While playing, let the chrome recede until the pointer moves. */
	.lightbox.playing .lb-bar,
	.lightbox.playing .lb-nav,
	.lightbox.playing .lb-close { opacity: 0.25; transition: opacity 0.3s ease; }
	.lightbox.playing:hover .lb-bar,
	.lightbox.playing:hover .lb-nav,
	.lightbox.playing:hover .lb-close { opacity: 1; }
	.lightbox:fullscreen { padding: 1.5rem 1rem 1rem; }
	.lightbox:fullscreen .lb-img { max-height: calc(100vh - 5rem); max-width: 100vw; }
	.lb-img {
		display: block;
		max-width: min(96vw, 1800px);
		max-height: calc(100vh - 6rem);
		max-height: calc(100dvh - 6rem);
		object-fit: contain;
		border-radius: 2px;
		user-select: none;
	}

	.lb-close {
		position: absolute;
		top: 0.6rem;
		right: 0.9rem;
		width: 2.4rem;
		height: 2.4rem;
		font-size: 1.7rem;
		line-height: 1;
		background: transparent;
		border: none;
		color: rgba(245, 239, 225, 0.8);
		cursor: pointer;
		z-index: 3;
	}
	.lb-close:hover { color: #fff; }

	.lb-nav {
		position: absolute;
		top: 50%;
		transform: translateY(-50%);
		width: 3rem;
		height: 4.5rem;
		font-size: 2.4rem;
		line-height: 1;
		background: transparent;
		border: none;
		color: rgba(245, 239, 225, 0.7);
		cursor: pointer;
		z-index: 2;
	}
	.lb-nav:hover { color: #fff; }
	.lb-prev { left: 0.3rem; }
	.lb-next { right: 0.3rem; }

	.lb-bar {
		position: relative;
		z-index: 3;
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 1rem;
		flex-wrap: wrap;
		padding: 0.8rem 1rem 0.3rem;
		max-width: 96vw;
		text-align: center;
	}
	.lb-toggle {
		font-family: var(--font-mono);
		font-size: 0.68rem;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: rgba(245, 239, 225, 0.9);
		background: transparent;
		border: 1px solid rgba(245, 239, 225, 0.35);
		border-radius: 2px;
		padding: 0.4rem 0.7rem;
		cursor: pointer;
	}
	.lb-toggle:hover { border-color: rgba(245, 239, 225, 0.8); }
	.lb-toggle.on {
		background: rgba(245, 239, 225, 0.92);
		color: var(--color-ink);
		border-color: transparent;
	}
	.lb-caption {
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.04em;
		color: rgba(245, 239, 225, 0.85);
	}
	.lb-count { margin-left: 0.6rem; opacity: 0.6; }

	/* Closed-caption style: words light up as they're spoken. */
	.lb-caption--words {
		font-family: var(--font-body);
		font-size: 1rem;
		letter-spacing: 0;
		line-height: 1.5;
		max-width: 44rem;
	}
	.lb-word {
		color: rgba(245, 239, 225, 0.35);
		transition: color 0.12s ease;
	}
	.lb-word.said { color: rgba(245, 239, 225, 0.85); }
	.lb-word.saying { color: #fff; text-shadow: 0 0 10px rgba(245, 239, 225, 0.5); }
	.lb-audio { padding-inline: 0.55rem; }
	/* …but keep the bar readable while a caption is being spoken. */
	.lightbox.playing.captioning .lb-bar { opacity: 1; }

	/* ── Comments panel in the viewer ─────────────────────────────────────── */
	.lb-panel {
		/* Dark in either theme, like the viewer around it. */
		--color-paper: #171512; --color-paper-deep: #24211c;
		--color-ink: #ece4d0; --color-ink-soft: #d2cbb9;
		--color-pencil: #b0aa99; --color-pencil-soft: #6b665a; --color-wax: #e2655a;
		position: absolute; top: 0; right: 0; bottom: 0; z-index: 4;
		width: min(23rem, 100vw); box-sizing: border-box;
		padding: 1rem 1.1rem 1.2rem;
		overflow-y: auto; overscroll-behavior: contain;
		background: var(--color-paper); color: var(--color-ink);
		border-left: 1px solid var(--color-pencil-soft);
		display: flex; flex-direction: column; gap: 0.9rem;
		text-align: left;
	}
	/* Room for the panel: the photo and its controls shift left of it. */
	@media (min-width: 641px) {
		.lightbox.with-panel { padding-right: calc(min(23rem, 100vw) + 1rem); }
		.lightbox.with-panel .lb-close { right: calc(min(23rem, 100vw) + 0.4rem); }
		.lightbox.with-panel .lb-next { right: calc(min(23rem, 100vw) + 0.3rem); }
	}
	.lb-panel-head { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
	.lb-panel-head h3 { margin: 0; font-family: var(--font-display); font-style: italic; font-weight: 500; font-size: 1.3rem; }
	.lb-panel-close {
		width: 2.4rem; height: 2.4rem; border: 0; background: transparent; cursor: pointer;
		color: var(--color-pencil); font-size: 1.6rem; line-height: 1;
	}
	.lb-panel-close:hover { color: var(--color-ink); }
	.lb-comment { display: grid; gap: 0.25rem; }
	.lb-comment.reply { margin-left: 0.3rem; padding-left: 0.9rem; border-left: 1px solid var(--color-pencil-soft); }
	.lb-who { margin: 0; display: flex; flex-wrap: wrap; gap: 0.2rem 0.5rem; align-items: baseline; font-size: 0.9rem; }
	.lb-who time { font-family: var(--font-mono); font-size: 0.64rem; letter-spacing: 0.1em; text-transform: uppercase; color: var(--color-pencil); }
	.lb-tag {
		font-family: var(--font-mono); font-size: 0.58rem; letter-spacing: 0.12em; text-transform: uppercase;
		padding: 0.05rem 0.35rem; border-radius: 2px; color: var(--color-wax); border: 1px solid var(--color-wax);
	}
	.lb-body { font-size: 0.95rem; }
	.lb-body :global(p) { margin: 0 0 0.5rem; line-height: 1.55; color: var(--color-ink-soft); overflow-wrap: anywhere; }
	.lb-body :global(p:last-child) { margin-bottom: 0; }
	.lb-body :global(a) { color: var(--color-ink); }
	.lb-gone, .lb-panel-empty, .lb-fine { margin: 0; color: var(--color-pencil); font-size: 0.85rem; }
	.lb-gone { font-style: italic; }
	.lb-fine a { color: var(--color-ink); }
	.lb-form { display: grid; gap: 0.5rem; margin-top: auto; padding-top: 0.9rem; border-top: 1px solid var(--color-paper-deep); }
	.lb-form textarea {
		font: inherit; font-size: 0.95rem; line-height: 1.5; color: var(--color-ink); background: var(--color-paper-deep);
		border: 1px solid var(--color-pencil-soft); border-radius: 3px; padding: 0.55rem 0.65rem;
		resize: vertical; width: 100%; box-sizing: border-box;
	}
	.lb-form textarea:focus { border-color: var(--color-ink); }
	.lb-form button {
		justify-self: start; cursor: pointer;
		font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.12em; text-transform: uppercase;
		background: var(--color-ink); color: var(--color-paper); border: 1px solid var(--color-ink);
		border-radius: 3px; padding: 0.5rem 0.9rem;
	}
	.lb-form button:disabled { opacity: 0.6; cursor: default; }
	.lb-error { margin: 0; color: var(--color-wax); font-size: 0.85rem; }

	/* ── Sections ─────────────────────────────────────────────────────────── */
	.jump {
		display: flex; flex-wrap: wrap; align-items: center; gap: 0.4rem;
		margin-bottom: 0.9rem;
		font-family: var(--font-mono); font-size: 0.72rem; letter-spacing: 0.06em;
	}
	.jump-label { text-transform: uppercase; letter-spacing: 0.12em; color: var(--color-pencil); margin-right: 0.2rem; }
	.jump a {
		display: inline-flex; align-items: center; min-height: 2rem; padding: 0 0.8rem;
		border: 1px solid var(--color-pencil); border-radius: 999px;
		color: var(--color-ink); text-decoration: none;
	}
	.jump a:hover { border-color: var(--color-wax); color: var(--color-wax); }
	.section {
		display: flex; justify-content: space-between; align-items: baseline; gap: 1rem;
		margin: 1.8rem 0 0.5rem; padding: 0 0.25rem;
		font-family: var(--font-display); font-style: italic; font-weight: 500; font-size: 1.3rem;
		color: var(--color-ink);
		scroll-margin-top: 1rem;
	}
	.section:first-of-type { margin-top: 0; }
	.section span { font-family: var(--font-mono); font-style: normal; font-size: 0.72rem; color: var(--color-pencil); }
	.dump--full .jump, .dump--full .section { padding: 0 1.5rem; }

	/* ── Phones ───────────────────────────────────────────────────────────── */
	.show-all { display: none; }
	@media (max-width: 640px) {
		.grid.folded {
			max-height: calc(var(--row-h) * 5.2);
			overflow: hidden;
			-webkit-mask-image: linear-gradient(#000 78%, transparent);
			mask-image: linear-gradient(#000 78%, transparent);
		}
		.show-all {
			display: block;
			width: 100%;
			min-height: 48px;
			margin-top: 0.6rem;
			font-family: var(--font-mono);
			font-size: 0.72rem;
			letter-spacing: 0.12em;
			text-transform: uppercase;
			color: var(--color-ink);
			background: transparent;
			border: 1px solid var(--color-ink);
			border-radius: 2px;
			cursor: pointer;
		}
		.jump a { min-height: 44px; }
		.rail { font-size: 0.68rem; }
		.play { min-height: 44px; padding: 0 0.8rem; }

		/* Viewer: opaque, photo to the screen edges, caption on its own line
		   under it, counter top-left, controls a thumb can hit. */
		.lightbox {
			background: #0c0b09;
			padding: calc(env(safe-area-inset-top) + 3.25rem) 0 calc(env(safe-area-inset-bottom) + 0.75rem);
		}
		.lb-img {
			max-width: 100vw;
			max-height: calc(100dvh - 13.5rem);
			border-radius: 0;
		}
		.lb-close {
			width: 44px; height: 44px;
			top: calc(env(safe-area-inset-top) + 0.25rem);
			right: 0.25rem;
		}
		.lb-bar { gap: 0.6rem; }
		.lb-caption {
			flex-basis: 100%;
			font-family: var(--font-body);
			font-size: 1rem;
			letter-spacing: 0;
			line-height: 1.45;
		}
		.lb-count {
			position: fixed;
			top: calc(env(safe-area-inset-top) + 1rem);
			left: 1rem;
			margin: 0;
			font-family: var(--font-mono);
			font-size: 0.78rem;
			letter-spacing: 0.1em;
		}
		.lb-toggle { min-height: 44px; padding: 0 0.9rem; font-size: 0.72rem; }

		/* Comments come up as a sheet over the lower part of the photo. */
		.lb-panel {
			top: auto; left: 0; width: auto;
			max-height: 72dvh;
			padding-bottom: calc(env(safe-area-inset-bottom) + 1rem);
			border-left: 0; border-top: 1px solid var(--color-pencil-soft);
			border-radius: 14px 14px 0 0;
		}
		.lb-panel-close { width: 44px; height: 44px; }
		.lb-form textarea { font-size: 1rem; }
		.lb-form button { min-height: 44px; justify-self: stretch; }
	}
</style>
