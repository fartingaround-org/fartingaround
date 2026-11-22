<script>
	import { imageUrl, imageSrcset } from '#lib/image.js';
	import { formatDateShort } from '#lib/format.js';
	import NoteLead from '#lib/components/NoteLead.svelte';

	/** @type {{ data: { site: Awaited<ReturnType<typeof import('#lib/server/settings.js').siteIdentity>>, featured: any | null, stories: any[] } }} */
	let { data } = $props();
	const site = $derived(data.site);
</script>

<svelte:head>
	<title>{site.tagline ? `${site.name} — ${site.tagline}` : site.name}</title>
	<meta name="description" content="A logbook of farting around — travels, projects, and whatever doesn't fit anywhere else." />
</svelte:head>

{#snippet quote()}
	<div class="quote-frame">
		<blockquote class="quote">
			<p>
				And I've had a hell of a good time. I tell you, we are here on Earth to fart around,
				and don't let anybody tell you any different.
			</p>
			<footer class="attribution">— Kurt Vonnegut, <cite>A Man Without a Country</cite></footer>
		</blockquote>
	</div>
{/snippet}

{#snippet storyCard(/** @type {any} */ story, /** @type {boolean} */ featured)}
	<a class="card" class:card--featured={featured} class:card--text={!story.coverImageId} href={`/stories/${story.slug}`}>
		{#if story.coverImageId}
			{@const focus = story.coverFocus ?? { x: 0.5, y: 0.5 }}
			{@const aspect = featured ? '21/9' : '4/5'}
			<div class="cover">
				<img
					src={imageUrl(story.coverImageId, { w: 1200, q: 85, fit: 'cover', gravity: focus })}
					srcset={imageSrcset(story.coverImageId, [400, 600, 800, 1200, 1600], { aspect, gravity: focus })}
					sizes={featured ? '92vw' : '(min-width: 960px) 30vw, (min-width: 640px) 46vw, 92vw'}
					alt=""
					loading={featured ? 'eager' : 'lazy'}
					decoding="async"
				/>
			</div>
		{/if}
		<div class="card-meta">
			<span>{formatDateShort(story.date)}</span>
			{#if story.location}<span>· {story.location}</span>{/if}
			{#if story.draft}<span class="draft-tag">draft</span>{/if}
			{#if story.private}<span class="draft-tag private">private</span>{/if}
		</div>
		<h2 class="card-title">{story.title}</h2>
		{#if story.summary}<p class="card-summary">{story.summary}</p>{/if}
	</a>
{/snippet}

<section class="hero">
	<div class="logo-stage">
		<img class="hero-logo" src="/FartingAround.png" alt={`An ink illustration of a figure with stars and rings shooting out — the ${site.name} logo`} />
	</div>

	{#if site.homeEyebrow}<p class="eyebrow">{site.homeEyebrow}</p>{/if}
	<h1 class="display title">
		{#each site.homeHeadline as line, i}
			<span class:italic={i === 1} class:hand-soft={i === 2}>{line}</span>
		{/each}
	</h1>

	<div class="quote-wide">{@render quote()}</div>

	{#if site.homeIntro}<p class="intro">{site.homeIntro}</p>{/if}
</section>

{#if data.featured || data.stories.length > 0}
	<section class="recent">
		<header class="section-header">
			<span class="eyebrow">Most recent dispatches</span>
			<a href="/stories" class="see-all">See all →</a>
		</header>

		{#if data.featured}
			<div class="featured">{@render storyCard(data.featured, true)}</div>
		{/if}

		<!-- Columns, not rows: notes and stories are very different heights, and
		     this packs them without gaps. Reads down each column, newest first. -->
		<div class="stream">
			{#each data.stories as story (story.slug)}
				{#if story.note}
					<!-- No title: the content itself, with the date line leading to its page. -->
					<article class="card card--note">
						{#if story.lead}
							<NoteLead slug={story.slug} lead={story.lead} thumbId={story.thumbId} sizes="(min-width: 960px) 30vw, (min-width: 640px) 46vw, 92vw" />
						{/if}
						<a class="card-meta note-meta" href={`/stories/${story.slug}`}>
							<span>{formatDateShort(story.date)}</span>
							{#if story.location}<span>· {story.location}</span>{/if}
							{#if story.draft}<span class="draft-tag">draft</span>{/if}
							{#if story.private}<span class="draft-tag private">private</span>{/if}
							<span class="note-more">{story.more ? 'More →' : '→'}</span>
						</a>
					</article>
				{:else}
					{@render storyCard(story, false)}
				{/if}
			{/each}
		</div>
	</section>
{:else}
	<section class="empty">
		<p class="empty-line"><span class="hand">No stories yet.</span> Drop into <a href="/admin">/admin</a> to write the first one.</p>
	</section>
{/if}

<!-- On phones the quote sits below the stories, so the newest one is on the first screen. -->
<div class="quote-narrow">{@render quote()}</div>

<style>
	.hero {
		max-width: 56rem;
		margin: 3rem auto 5rem;
		padding: 0 1.5rem;
		text-align: center;
	}

	.logo-stage {
		display: flex;
		justify-content: center;
		margin-bottom: 1rem;
	}
	.hero-logo {
		width: clamp(180px, 32vw, 280px);
		height: auto;
		mix-blend-mode: var(--logo-blend, multiply);
		transform: rotate(-2deg);
		filter: var(--logo-filter, contrast(1.05));
		animation: settle 1.4s ease-out 0.1s both;
	}
	@keyframes settle {
		0%   { opacity: 0; transform: rotate(-6deg) translateY(-12px); }
		60%  { opacity: 1; transform: rotate(1deg) translateY(2px); }
		100% { opacity: 1; transform: rotate(-2deg) translateY(0); }
	}
	@media (prefers-reduced-motion: reduce) {
		.hero-logo { animation: none; }
	}

	.title { font-size: clamp(2.6rem, 8vw, 5.5rem); margin: 0.4rem 0 2rem; }
	.title span { display: inline-block; margin: 0 0.1em; }
	.title .italic { font-style: italic; color: var(--color-ink); }
	.title .hand-soft {
		font-family: var(--font-hand);
		font-style: normal;
		font-weight: 600;
		color: var(--color-wax);
		transform: rotate(-2deg);
		font-size: 0.9em;
	}
	.quote-frame {
		max-width: 38rem;
		margin: 2.5rem auto 2.2rem;
		padding: 1.6rem 1.8rem;
		border-top: 1px solid var(--color-pencil-soft);
		border-bottom: 1px solid var(--color-pencil-soft);
		position: relative;
	}
	.quote-frame::before, .quote-frame::after {
		content: ''; position: absolute; width: 8px; height: 8px; border-radius: 50%;
		background: var(--color-wax); opacity: 0.7;
	}
	.quote-frame::before { top: -5px; left: -5px; }
	.quote-frame::after { bottom: -5px; right: -5px; }
	.quote {
		font-family: var(--font-display);
		font-style: italic;
		font-variation-settings: 'opsz' 144;
		font-size: clamp(1.05rem, 2.2vw, 1.3rem);
		line-height: 1.5;
		color: var(--color-ink);
		margin: 0;
	}
	.quote p { margin: 0; }
	.attribution {
		display: block; margin-top: 0.9rem;
		font-family: var(--font-mono); font-style: normal;
		font-size: 0.75rem; letter-spacing: 0.06em;
		color: var(--color-pencil);
	}
	.attribution cite { font-style: italic; }
	.intro {
		max-width: 32rem; margin: 0 auto;
		font-size: 1rem; color: var(--color-ink-soft); line-height: 1.7;
	}

	.recent { max-width: 72rem; margin: 0 auto; padding: 0 1.5rem; }
	.section-header {
		display: flex; justify-content: space-between; align-items: baseline;
		padding-bottom: 1rem; margin-bottom: 2rem;
		border-bottom: 1px dashed var(--color-pencil-soft);
	}
	.see-all {
		font-family: var(--font-mono); font-size: 0.78rem;
		letter-spacing: 0.12em; text-transform: uppercase;
		color: var(--color-ink); text-decoration: none;
	}
	.see-all:hover { color: var(--color-wax); }

	.featured { margin-bottom: 2.5rem; }
	.stream { columns: 1; column-gap: 2rem; }
	@media (min-width: 640px) { .stream { columns: 2; } }
	@media (min-width: 960px) { .stream { columns: 3; } }
	/* inline-block + full width: a card is never split across two columns. */
	.stream > :global(.card) { display: inline-block; width: 100%; margin: 0 0 2.5rem; break-inside: avoid; vertical-align: top; }

	.card { text-decoration: none; color: inherit; display: block; transition: transform 0.3s ease; }
	.card:hover { transform: translateY(-3px); }
	.card:hover .cover img { transform: scale(1.03); }

	/* A note is content, not a link: it doesn't lift on hover, and its date
	   line (under the content) is what leads to its page. */
	.card--note:hover { transform: none; }
	.note-meta { margin: 0.7rem 0 0; text-decoration: none; align-items: center; min-height: 1.75rem; }
	.note-meta:hover { color: var(--color-wax); }
	.note-more { margin-left: auto; }
	@media (min-width: 960px) {
		.card--featured .cover { aspect-ratio: 21/9; }
		.card--featured .card-title { font-size: 2.4rem; }
	}

	.cover {
		aspect-ratio: 4/5;
		overflow: hidden;
		background: var(--color-paper-deep);
		border-radius: 2px;
		box-shadow: 0 1px 0 rgba(0, 0, 0, 0.04), 0 14px 28px -20px rgba(0, 0, 0, 0.4);
		margin-bottom: 1rem;
	}
	/* A story with no cover is set as words, not as an empty frame: ruled
	   above and below like a telegram, with the title doing the work. */
	.card--text {
		align-self: start;
		padding: 1.4rem 0 1.5rem;
		border-top: 1px solid var(--color-pencil-soft);
		border-bottom: 1px solid var(--color-pencil-soft);
	}
	.card--text .card-title { font-size: 1.9rem; }
	.card--text.card--featured { padding: 2.2rem 0 2.4rem; text-align: center; }
	.card--text.card--featured .card-meta { justify-content: center; }
	.card--text.card--featured .card-summary { max-width: 34rem; margin-left: auto; margin-right: auto; }
	@media (min-width: 960px) { .card--text.card--featured .card-title { font-size: 3rem; } }
	.cover img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.4s ease; }

	.card-meta {
		font-family: var(--font-mono);
		font-size: 0.72rem;
		letter-spacing: 0.06em;
		color: var(--color-pencil);
		margin-bottom: 0.3rem;
		display: flex; gap: 0.4em; flex-wrap: wrap;
	}
	.draft-tag {
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

	.card-title {
		font-family: var(--font-display);
		font-weight: 500;
		font-style: italic;
		font-size: 1.5rem;
		line-height: 1.2;
		margin: 0;
		letter-spacing: -0.01em;
	}
	.card-summary {
		margin-top: 0.5rem;
		color: var(--color-ink-soft);
		font-size: 0.95rem;
		line-height: 1.55;
	}

	.empty { max-width: 36rem; margin: 4rem auto; text-align: center; padding: 0 1.5rem; }
	.empty-line { color: var(--color-pencil); }

	/* ── Phones: get to the newest story on the first screen ─────────────── */
	.quote-narrow { display: none; }
	@media (max-width: 640px) {
		.hero { margin: 0.75rem auto 2rem; padding: 0 1.25rem; }
		.logo-stage { margin-bottom: 0.25rem; }
		.hero-logo { width: 104px; }
		.title { font-size: 2.2rem; margin: 0.3rem 0 0.8rem; }
		.intro { font-size: 0.95rem; line-height: 1.55; }
		.quote-wide { display: none; }
		.quote-narrow { display: block; padding: 0 1.25rem; margin-top: 3.5rem; }
		.quote-narrow .quote-frame { margin: 0 auto; }
		.recent { padding: 0 1rem; }
		.section-header { margin-bottom: 1.25rem; align-items: center; }
		.see-all { display: inline-flex; align-items: center; min-height: 44px; }
		.card--featured .cover { aspect-ratio: 3/2; }
	}
</style>
