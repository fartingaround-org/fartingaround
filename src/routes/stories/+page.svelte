<script>
	import { page } from '$app/state';
	import { imageUrl } from '#lib/image.js';
	import { formatDate } from '#lib/format.js';

	/** @type {{ data: { tag: string | null, q: string, found: number, tags: Array<{tag: string, count: number}>, years: Array<{year: number, stories: any[]}> } }} */
	let { data } = $props();

	/** The list narrowed to a tag, keeping whatever is being searched for. @param {string | null} tag */
	function listHref(tag) {
		const p = new URLSearchParams();
		if (data.q) p.set('q', data.q);
		if (tag) p.set('tag', tag);
		const qs = p.toString();
		return qs ? `/stories?${qs}` : '/stories';
	}
	/** @param {string} tag */
	const isCurrent = (tag) => !!data.tag && tag.toLowerCase() === data.tag.toLowerCase();

	// What stands in the thumbnail of a story with no picture.
	const glyph = /** @type {Record<string, string>} */ ({ audio: '♪', video: '▶', text: '¶', pull: '¶', aside: '¶' });
</script>

<svelte:head><title>Stories — {page.data.site.name}</title></svelte:head>

<div class="wrap">
	<header class="page-header">
		<p class="eyebrow">The full logbook</p>
		<h1 class="display title">Every envelope, mailed.</h1>
		<p class="lede">A running list of trips taken, projects worked on, and afternoons properly wasted.</p>
	</header>

	<search class="find">
		<form method="get" action="/stories" class="find-form">
			<label class="eyebrow" for="story-search">Search every story</label>
			<div class="find-row">
				<input id="story-search" type="search" name="q" value={data.q} placeholder="backups, phone app, a place…" maxlength="200" autocomplete="off" />
				<!-- A new search always covers every story: it does not carry the chosen
				     tag along. A tag picked afterwards narrows the results. -->
				<button type="submit">Search</button>
			</div>
		</form>

		{#if data.tags.length}
			<nav class="tags" aria-label="Tags">
				<span class="eyebrow">Tags</span>
				<ul>
					{#each data.tags as t}
						<li>
							<a href={listHref(isCurrent(t.tag) ? null : t.tag)} class:current={isCurrent(t.tag)} aria-current={isCurrent(t.tag) ? 'true' : undefined}>
								{t.tag} <span class="count">{t.count}</span>
							</a>
						</li>
					{/each}
				</ul>
			</nav>
		{/if}
	</search>

	{#if data.tag || data.q}
		<p class="tagged" aria-live="polite">
			<span>
				{data.found} {data.found === 1 ? 'story' : 'stories'}
				{#if data.q}with <strong>{data.q}</strong>{/if}
				{#if data.tag}tagged <strong>{data.tag}</strong>{/if}
			</span>
			{#if data.q && data.tag}<a href={`/stories?q=${encodeURIComponent(data.q)}`}>Search every tag</a>{/if}
			<a href="/stories">Show every story</a>
		</p>
	{/if}

	{#if data.years.length === 0}
		<p class="empty">
			{#if data.q}Nothing here has those words.{:else if data.tag}Nothing carries that tag.{:else}No stories yet. The logbook is wide open.{/if}
		</p>
	{/if}

	{#each data.years as { year, stories }}
		<section class="year-block">
			<h2 class="year">{year}</h2>
			<ul class="entries">
				{#each stories as s}
					<li class="entry">
						<a href={`/stories/${s.slug}`} class="entry-link">
							{#if s.coverImageId}
								<div class="thumb">
									<img src={imageUrl(s.coverImageId, { w: 200, h: 200, fit: 'cover', q: 80 })} alt="" loading="lazy" />
								</div>
							{:else}
								<div class="thumb thumb--blank" aria-hidden="true">{glyph[s.kind] ?? ''}</div>
							{/if}
							<div class="entry-body">
								<div class="entry-meta">
									<time>{formatDate(s.date)}</time>
									{#if s.location}<span>· {s.location}</span>{/if}
									{#if s.draft}<span class="draft-tag">draft</span>{/if}
									{#if s.private}<span class="draft-tag private">private</span>{/if}
								</div>
								<h3 class="entry-title" class:entry-title--note={s.note}>{s.title}</h3>
								{#if s.summary}<p class="entry-summary">{s.summary}</p>{/if}
							</div>
							<span class="arrow" aria-hidden="true">→</span>
						</a>
					</li>
				{/each}
			</ul>
		</section>
	{/each}
</div>

<style>
	/* A note has no title: its label (a caption, a first line) is set quieter. */
	.entry-title.entry-title--note { font-style: normal; font-size: 1.05rem; font-weight: 400; line-height: 1.4; letter-spacing: 0; color: var(--color-ink-soft); }
	.find { display: block; margin: 0 0 2.5rem; }
	.find-form label { display: block; margin-bottom: 0.5rem; }
	.find-row { display: flex; gap: 0.6rem; }
	.find-row input[type='search'] {
		flex: 1; min-width: 0; min-height: 2.75rem; padding: 0.5rem 0.8rem;
		font: inherit; color: var(--color-ink); background: var(--color-paper);
		border: 1px solid var(--color-pencil-soft); border-radius: 2px;
	}
	.find-row input[type='search']:focus-visible { outline: 2px solid var(--color-wax); outline-offset: 1px; }
	.find-row button {
		min-height: 2.75rem; padding: 0 1.1rem; cursor: pointer;
		font-family: var(--font-mono); font-size: 0.75rem; letter-spacing: 0.1em; text-transform: uppercase;
		color: var(--color-paper); background: var(--color-ink); border: 0; border-radius: 2px;
	}
	.tags { margin-top: 1.2rem; display: flex; flex-wrap: wrap; gap: 0.5rem 0.9rem; align-items: baseline; }
	.tags ul { list-style: none; display: flex; flex-wrap: wrap; gap: 0.5rem; margin: 0; padding: 0; }
	.tags a {
		display: inline-flex; align-items: center; gap: 0.4em; min-height: 2rem; padding: 0.1rem 0.7rem;
		font-family: var(--font-mono); font-size: 0.75rem; letter-spacing: 0.04em;
		color: var(--color-ink-soft); text-decoration: none;
		border: 1px solid var(--color-pencil-soft); border-radius: 999px;
	}
	.tags a:hover { color: var(--color-ink); border-color: var(--color-ink); }
	.tags a.current { color: var(--color-paper); background: var(--color-ink); border-color: var(--color-ink); }
	.tags .count { opacity: 0.65; }
	@media (max-width: 540px) { .tags a { min-height: 2.75rem; } }
	.tagged { display: flex; flex-wrap: wrap; gap: 0.4rem 0.8rem; align-items: baseline; margin: 0 0 2rem; }
	.tagged a { font-size: 0.9rem; color: var(--color-pencil); }
	.wrap { max-width: 56rem; margin: 3rem auto 5rem; padding: 0 1.5rem; }
	.page-header { text-align: center; margin-bottom: 4rem; }
	.title { font-size: clamp(2.5rem, 7vw, 4.5rem); margin: 0.4rem 0 1rem; }
	.lede { max-width: 28rem; margin: 0 auto; color: var(--color-ink-soft); }
	.empty {
		text-align: center;
		font-family: var(--font-hand);
		font-size: 1.3rem;
		color: var(--color-pencil);
		padding: 3rem 0;
	}

	.year-block { margin-bottom: 3.5rem; }
	.year {
		font-family: var(--font-mono);
		font-size: 0.8rem;
		letter-spacing: 0.2em;
		color: var(--color-pencil);
		margin: 0 0 1.2rem;
		padding-bottom: 0.6rem;
		border-bottom: 1px solid var(--color-pencil-soft);
	}
	.entries { list-style: none; padding: 0; margin: 0; }
	.entry { border-bottom: 1px dashed var(--color-pencil-soft); }
	.entry-link {
		display: grid;
		grid-template-columns: 80px 1fr auto;
		gap: 1.2rem; align-items: center;
		padding: 1.2rem 0.4rem;
		text-decoration: none; color: inherit;
		transition: background 0.2s ease;
	}
	.entry-link:hover { background: rgba(179, 50, 42, 0.04); }
	.entry-link:hover .arrow { transform: translateX(4px); color: var(--color-wax); }

	.thumb {
		width: 80px; height: 80px; overflow: hidden;
		border-radius: 2px; background: var(--color-paper-deep);
		box-shadow: 0 1px 0 rgba(0,0,0,0.04), 0 6px 14px -10px rgba(0,0,0,0.3);
	}
	.thumb img { width: 100%; height: 100%; object-fit: cover; }
	.thumb--blank {
		background: var(--color-paper-deep);
		display: grid; place-items: center;
		font-family: var(--font-display); font-size: 1.6rem; color: var(--color-pencil);
	}

	.entry-meta {
		font-family: var(--font-mono);
		font-size: 0.7rem;
		letter-spacing: 0.06em;
		color: var(--color-pencil);
		margin-bottom: 0.25rem;
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
	.entry-title {
		font-family: var(--font-display);
		font-style: italic;
		font-weight: 500;
		font-size: 1.35rem;
		line-height: 1.2;
		margin: 0;
		letter-spacing: -0.01em;
	}
	.entry-summary { margin: 0.35rem 0 0; color: var(--color-ink-soft); font-size: 0.92rem; }
	.arrow {
		font-family: var(--font-mono); color: var(--color-pencil);
		transition: transform 0.2s ease, color 0.2s ease;
		padding-right: 0.4rem;
	}
	@media (max-width: 540px) {
		.entry-link { grid-template-columns: 60px 1fr; }
		.thumb { width: 60px; height: 60px; }
		.arrow { display: none; }
	}
</style>
