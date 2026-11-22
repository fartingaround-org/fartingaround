<script>
	import { page } from '$app/state';
	import { formatDateShort } from '#lib/format.js';
	/** @type {{ data: { feeds: { title: string, xmlUrl: string, htmlUrl: string | null }[], updatedAt: string | null } }} */
	let { data } = $props();

	/** "example.org" from a feed's site (or feed) address. Never the path or query: a feed address can carry a private token. */
	function host(/** @type {{ xmlUrl: string, htmlUrl: string | null }} */ f) {
		try { return new URL(f.htmlUrl ?? f.xmlUrl).hostname.replace(/^www\./, ''); } catch { return ''; }
	}
</script>

<svelte:head>
	<title>Following — {page.data.site.name}</title>
	<meta name="description" content="The sites I follow. A blogroll, the way it used to be." />
</svelte:head>

<article class="following">
	<header class="header">
		<p class="eyebrow">Following</p>
		<h1 class="display title">Other people farting around.</h1>
		<p class="lede">
			The feeds I keep up with, straight out of my reader. Take the lot as
			<a href="/following.opml">OPML</a> and drop it into yours.
		</p>
	</header>

	{#if data.feeds.length === 0}
		<p class="empty">Nothing here yet.</p>
	{:else}
		<ul class="list">
			{#each data.feeds as f (f.xmlUrl)}
				<li>
					<a class="site" href={f.htmlUrl ?? f.xmlUrl} rel="noopener">
						<span class="name">{f.title}</span>
						{#if host(f)}<span class="host">{host(f)}</span>{/if}
					</a>
					<a class="feed" href={f.xmlUrl} rel="noopener" title="Feed">rss</a>
				</li>
			{/each}
		</ul>
		{#if data.updatedAt}
			<p class="updated">Updated {formatDateShort(data.updatedAt.slice(0, 10))}</p>
		{/if}
	{/if}
</article>

<style>
	.following { max-width: 42rem; margin: 0 auto; padding: 3rem 1.25rem 5rem; }
	.header { margin-bottom: 2rem; }
	.title { margin: 0.25rem 0 0.75rem; font-size: clamp(2rem, 7vw, 3rem); line-height: 1.05; }
	.lede { color: var(--color-ink-soft); margin: 0; }
	.lede a { color: var(--color-wax); }
	.list { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--color-paper-deep); }
	.list li { display: flex; justify-content: space-between; align-items: center; gap: 1rem; min-height: 44px; padding: 0.4rem 0; border-bottom: 1px solid var(--color-paper-deep); }
	.list li { padding: 0.7rem 0; }
	.site { display: grid; gap: 0.15rem; color: var(--color-ink); text-decoration: none; min-width: 0; }
	.name { font-family: var(--font-display); font-style: italic; font-size: 1.15rem; line-height: 1.25; }
	.host { font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.06em; color: var(--color-pencil); overflow-wrap: anywhere; }
	.site:hover .name { color: var(--color-wax); }
	.feed { padding: 0.6rem 0 0.6rem 0.6rem; font-size: 0.75rem; letter-spacing: 0.1em; text-transform: uppercase; color: var(--color-pencil); text-decoration: none; }
	.feed:hover { color: var(--color-wax); }
	.updated, .empty { color: var(--color-pencil); font-size: 0.9rem; margin-top: 1.5rem; }
</style>
