<script>
	import { page } from '$app/state';
	import StoryBlocks from '#lib/components/StoryBlocks.svelte';
	import Comments from '#lib/components/Comments.svelte';
	import { imageUrl } from '#lib/image.js';
	import { formatDate } from '#lib/format.js';
	import { isNote, noteThumbId, storyLabel } from '#lib/story-label.js';

	/**
	 * @type {{
	 *   data: {
	 *     story: import('#lib/types.js').Story, private: boolean,
	 *     newer: { slug: string, title: string } | null, older: { slug: string, title: string } | null,
	 *     comments: import('#lib/server/comments.js').PublicComment[],
	 *     commenting: { enabled: boolean, as: string | null }
	 *   },
	 *   form?: any
	 * }}
	 */
	let { data, form } = $props();
	const story = $derived(data.story);
	// No title: a note. No headline and no cover backdrop, just the date line and the content.
	const note = $derived(isNote(story));
	const label = $derived(storyLabel(story));
	const shareImage = $derived(noteThumbId(story));

	// Captions of the photo-dump photos, for the comment list to show beside a photo's comments.
	const dumpPhotos = $derived.by(() => {
		/** @type {Map<string, { caption?: string, alt?: string }>} */
		const m = new Map();
		for (const b of story.blocks || []) {
			if (b.type !== 'dump') continue;
			for (const p of b.photos || []) if (p?.imageId) m.set(p.imageId, { caption: p.caption, alt: p.alt });
		}
		return m;
	});

	// Cover photo doubles as the header backdrop. We request it without a fixed
	// crop and let CSS background-size:cover + the saved focus point frame it.
	const focus = $derived(story.coverFocus ?? { x: 0.5, y: 0.5 });
	const coverBg = $derived(story.coverImageId ? imageUrl(story.coverImageId, { w: 1920, q: 82 }) : '');
	const coverPos = $derived(`${Math.round((focus.x ?? 0.5) * 100)}% ${Math.round((focus.y ?? 0.5) * 100)}%`);
</script>

<svelte:head>
	<title>{label} — {page.data.site.name}</title>
	{#if data.private}<meta name="robots" content="noindex, noarchive" />{/if}
	{#if story.summary && !data.private}<meta name="description" content={story.summary} />{/if}
	{#if shareImage && !data.private}
		<meta property="og:image" content={imageUrl(shareImage, { w: 1200, h: 630, fit: 'cover' })} />
	{/if}
</svelte:head>

<article class="story">
	<header
		class="story-header"
		class:has-cover={!!story.coverImageId && !note}
		class:is-note={note}
		style:--cover-image={coverBg && !note ? `url("${coverBg}")` : null}
		style:--cover-pos={story.coverImageId && !note ? coverPos : null}
	>
		<div class="header-inner">
			<p class="eyebrow">
				<time datetime={story.date}>{formatDate(story.date)}</time>
				{#if story.location}<span class="dot">·</span><span>{story.location}</span>{/if}
				{#if story.draft}<span class="draft-tag">draft</span>{/if}
				{#if data.private}<span class="draft-tag private">private</span>{/if}
			</p>
			{#if note}
				<h1 class="visually-hidden">{label}</h1>
			{:else}
				<h1 class="display story-title">{story.title}</h1>
			{/if}
			{#if story.summary}<p class="story-summary">{story.summary}</p>{/if}
			{#if story.coords}
				<p class="story-where">
					<a
						class="where-link"
						href={`https://www.openstreetmap.org/?mlat=${story.coords.lat}&mlon=${story.coords.lon}#map=14/${story.coords.lat}/${story.coords.lon}`}
						target="_blank"
						rel="noopener noreferrer"
						title="Open in OpenStreetMap"
					>
						<svg class="pin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 21s-6.500-5.800-6.500-10.500a6.500 6.500 0 0 1 13 0C18.500 15.200 12 21 12 21z" /><circle cx="12" cy="10.500" r="2.300" /></svg>
						{story.coords.lat.toFixed(4)}, {story.coords.lon.toFixed(4)}
					</a>
				</p>
			{/if}
			{#if story.weather || story.astro}
				<p class="story-context">
					{#if story.weather}
						<span>{story.weather.tempF}°F · {story.weather.conditions}</span>
					{/if}
					{#if story.weather && story.astro}<span class="sep">·</span>{/if}
					{#if story.astro}
						<span>{story.astro.moonPhaseLabel}, {Math.round(story.astro.moonIllumination * 100)}% lit</span>
					{/if}
				</p>
			{/if}
		</div>
	</header>

	<StoryBlocks blocks={story.blocks} {note} />

	{#if story.tags?.length}
		<ul class="story-tags" aria-label="Tags">
			{#each story.tags as tag}
				<li><a href={`/stories?tag=${encodeURIComponent(tag)}`}>{tag}</a></li>
			{/each}
		</ul>
	{/if}

	<Comments comments={data.comments} commenting={data.commenting} {form} photos={dumpPhotos} />

	<footer class="story-footer">
		{#if data.newer || data.older}
			<nav class="neighbours" aria-label="More stories">
				{#if data.older}
					<a class="neighbour older" href={`/stories/${data.older.slug}`}>
						<span class="dir">← Earlier</span>
						<span class="display neighbour-title">{data.older.title}</span>
					</a>
				{/if}
				{#if data.newer}
					<a class="neighbour newer" href={`/stories/${data.newer.slug}`}>
						<span class="dir">Later →</span>
						<span class="display neighbour-title">{data.newer.title}</span>
					</a>
				{/if}
			</nav>
		{/if}
		<a href="/stories" class="back-link">← back to the logbook</a>
	</footer>
</article>

<style>
	.story { padding: 2rem 1.5rem 4rem; }
	.story-header { max-width: 38rem; margin: 1.5rem auto 2.5rem; text-align: center; }
	/* A note: only the date line stands above the content. */
	.story-header.is-note { margin: 0.5rem auto 1.5rem; }
	.story-header.is-note .story-summary { font-size: 1.05rem; margin-top: 0.8rem; }
	.visually-hidden { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
	.eyebrow .dot { margin: 0 0.4em; color: var(--color-pencil-soft); }
	.draft-tag {
		margin-left: 0.6em;
		font-family: var(--font-mono);
		background: var(--color-wax);
		color: var(--color-paper);
		font-size: 0.6rem;
		padding: 0.1em 0.5em;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		border-radius: 2px;
	}
	.draft-tag.private { background: var(--color-postmark); }
	/* On a cover photo the tags keep their paper-theme colours: ink's lighter
	   wax and postmark don't carry cream text. */
	.story-header.has-cover .draft-tag { background: #b3322a; color: #f5efe1; }
	.story-header.has-cover .draft-tag.private { background: #2a3a52; }
	.story-title { font-size: clamp(2.4rem, 7vw, 5rem); margin: 0.6rem 0 1.2rem; color: var(--color-ink); }
	.story-summary {
		font-family: var(--font-display);
		font-style: italic;
		font-size: 1.15rem;
		color: var(--color-ink-soft);
		max-width: 30rem;
		margin: 0 auto;
		line-height: 1.5;
	}
	.story-where {
		margin: 1rem 0 0;
		font-family: var(--font-mono);
		font-size: 0.72rem;
		letter-spacing: 0.1em;
		color: var(--color-pencil);
	}
	.story-where .where-link {
		color: inherit;
		text-decoration: none;
		border-bottom: 1px dotted var(--color-pencil-soft);
		padding-bottom: 1px;
	}
	.story-where .where-link:hover { border-bottom-style: solid; }
	/* inline-block: the CSS reset makes every svg a block. */
	.pin { display: inline-block; width: 1.1em; height: 1.1em; vertical-align: -0.2em; margin-right: 0.2em; }

	.story-tags {
		list-style: none; padding: 0; margin: 3rem auto 0; max-width: 38rem;
		display: flex; flex-wrap: wrap; gap: 0.5rem;
	}
	.story-tags a {
		display: inline-flex; align-items: center; min-height: 2rem; padding: 0 0.7rem;
		font-family: var(--font-mono); font-size: 0.68rem; letter-spacing: 0.1em; text-transform: uppercase;
		color: var(--color-pencil); text-decoration: none;
		border: 1px solid var(--color-pencil-soft); border-radius: 999px;
	}
	.story-tags a:hover { color: var(--color-ink); border-color: var(--color-ink); }
	.story-context {
		margin: 0.4rem 0 0;
		font-family: var(--font-mono);
		font-size: 0.7rem;
		letter-spacing: 0.08em;
		color: var(--color-pencil);
		display: inline-flex;
		flex-wrap: wrap;
		gap: 0.4rem;
		justify-content: center;
	}
	.story-context .sep { color: var(--color-pencil-soft); }
	/* When a story has a cover photo, the photo becomes the header backdrop.
	   A translucent gradient scrim sits between the image and the text so the
	   title + summary stay legible over any photo. */
	.story-header.has-cover {
		/* Text here sits on a darkened photo in either theme. */
		--color-paper: #f5efe1;
		max-width: 64rem;
		margin: 1.5rem auto 3.5rem;
		padding: clamp(3.5rem, 10vw, 7rem) clamp(1.5rem, 5vw, 3rem);
		position: relative;
		overflow: hidden;
		border-radius: 2px;
		background-image: var(--cover-image);
		background-size: cover;
		background-position: var(--cover-pos, 50% 50%);
		background-repeat: no-repeat;
		box-shadow: 0 1px 0 rgba(0, 0, 0, 0.04), 0 24px 50px -32px rgba(0, 0, 0, 0.45);
	}
	.story-header.has-cover::before {
		content: '';
		position: absolute;
		inset: 0;
		background: linear-gradient(180deg, rgba(18, 16, 12, 0.42) 0%, rgba(18, 16, 12, 0.7) 100%);
	}
	.story-header.has-cover .header-inner {
		position: relative;
		z-index: 1;
		max-width: 40rem;
		margin: 0 auto;
	}
	/* Light text for contrast against the darkened photo. */
	.story-header.has-cover .story-title { color: var(--color-paper); }
	.story-header.has-cover .story-summary { color: rgba(245, 239, 225, 0.92); }
	.story-header.has-cover .eyebrow { color: rgba(245, 239, 225, 0.85); }
	.story-header.has-cover .eyebrow .dot { color: rgba(245, 239, 225, 0.5); }
	.story-header.has-cover .story-where,
	.story-header.has-cover .story-context { color: rgba(245, 239, 225, 0.82); }
	.story-header.has-cover .story-where .where-link {
		color: inherit;
		border-bottom-color: rgba(245, 239, 225, 0.5);
	}
	.story-header.has-cover .story-context .sep { color: rgba(245, 239, 225, 0.5); }

	.story-footer {
		max-width: 38rem;
		margin: 5rem auto 0;
		padding-top: 2rem;
		border-top: 1px dashed var(--color-pencil-soft);
		text-align: center;
	}
	.back-link {
		font-family: var(--font-mono);
		font-size: 0.8rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		text-decoration: none;
		color: var(--color-pencil);
	}
	.back-link:hover { color: var(--color-wax); }

	/* Earlier on the left, later on the right, wherever only one exists. */
	.neighbours { display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem; margin-bottom: 2.5rem; text-align: left; }
	.neighbour { display: grid; gap: 0.35rem; text-decoration: none; color: inherit; }
	.neighbour.older { grid-column: 1; }
	.neighbour.newer { grid-column: 2; text-align: right; }
	.dir {
		font-family: var(--font-mono); font-size: 0.68rem; letter-spacing: 0.14em; text-transform: uppercase;
		color: var(--color-pencil);
	}
	.neighbour-title { font-size: 1.25rem; line-height: 1.2; color: var(--color-ink); }
	.neighbour:hover .dir, .neighbour:hover .neighbour-title { color: var(--color-wax); }

	/* ── Phones: the cover runs edge to edge ─────────────────────────────── */
	@media (max-width: 640px) {
		.story { padding: 0 1.25rem 3rem; }
		.story-header.has-cover {
			margin: 0 -1.25rem 2rem;
			padding: 3.5rem 1.25rem;
			border-radius: 0;
			box-shadow: none;
		}
		.story-title { font-size: 2.3rem; }
		.back-link { display: inline-block; padding: 0.7rem 0.2rem; }
		.neighbours { gap: 1rem; }
		.neighbour-title { font-size: 1.05rem; }
		.story-tags a { min-height: 2.75rem; }
	}
</style>
