<script>
	import { page } from '$app/state';
	import { linkify } from '#lib/linkify.js';

	const site = $derived(page.data.site);

	/** @param {string} s */
	const escape = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

	// The owner's own About text, in the same plain format as a story's text
	// block: blank line between paragraphs, [text](url) for links.
	const paragraphs = $derived(
		site.about
			.split(/\n{2,}/)
			.map((/** @type {string} */ p) => p.trim())
			.filter(Boolean)
			.map((/** @type {string} */ p) => linkify(escape(p)).replace(/\n/g, '<br>'))
	);
</script>

<svelte:head><title>About — {site.name}</title></svelte:head>

<article class="about">
	<header class="header">
		<img class="spot" src="/FartingAround.png" alt="" width="120" />
		<p class="eyebrow">About</p>
		<h1 class="display title">{site.aboutHeadline}</h1>
	</header>

	<div class="body">
		{#if paragraphs.length}
			{#each paragraphs as p}
				<p>{@html p}</p>
			{/each}
		{:else}
			<!-- The page a site shows until its owner writes their own. -->
		<p>
			This site exists because of a passage from Kurt Vonnegut's
			<em>A Man Without a Country</em>, where he describes a small expedition to
			mail a single sheet of paper. The whole thing takes the better part of an
			afternoon and involves chitchat with strangers, a woman with a jewel
			between her eyes, and an unrequited crush at the post office.
		</p>

		<blockquote>
			<p>I tell you, we are here on Earth to fart around, and don't let anybody tell you any different.</p>
		</blockquote>

		<p>
			So this is the logbook for my own farting around. Trips taken on purpose,
			detours taken by accident, projects that didn't fit anywhere else, and the
			occasional stray thought that wanted a permanent address.
		</p>

		<p>It's deliberately small and slow. Photos and a little prose, mostly.</p>
		{/if}

		{#if site.ownerName || site.contactEmail}
			<hr />
		{/if}

		{#if site.ownerName}
			<p class="meta">
				I'm {#if site.ownerUrl}<a href={site.ownerUrl}>{site.ownerName}</a>{:else}{site.ownerName}{/if}.
			</p>
		{/if}
		{#if site.contactEmail}
			<p class="meta">
				Want to buy an envelope together? Write to me at
				<a href={`mailto:${site.contactEmail}`}>{site.contactEmail}</a>.
			</p>
		{/if}

		{#if site.supportUrl}
			<p class="support">
				<a class="bmc" href={site.supportUrl} target="_blank" rel="noopener noreferrer">
				<svg class="bmc-cup" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
					<path d="M4 8h12v5.5A3.5 3.5 0 0 1 12.5 17h-5A3.5 3.5 0 0 1 4 13.5V8Z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
					<path d="M16 9h2.2a2 2 0 0 1 0 4H16" fill="none" stroke="currentColor" stroke-width="1.4" />
					<path d="M6.5 4.2c.6.6.6 1.2 0 1.8M9.5 4.2c.6.6.6 1.2 0 1.8M12.5 4.2c.6.6.6 1.2 0 1.8" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" opacity="0.55" />
				</svg>
					<span class="bmc-text">{site.supportLabel}</span>
				</a>
			</p>
		{/if}
	</div>
</article>

<style>
	.about { max-width: 38rem; margin: 4rem auto 5rem; padding: 0 1.5rem; }
	.header { text-align: center; margin-bottom: 3rem; }
	.spot {
		width: 110px; height: auto;
		mix-blend-mode: var(--logo-blend, multiply);
		transform: rotate(-3deg);
		filter: var(--logo-filter, contrast(1.05));
		margin: 0 auto 1rem;
		display: block;
	}
	.title { font-size: clamp(2.2rem, 6vw, 4rem); margin: 0.5rem 0 0; }
	.body { color: var(--color-ink-soft); line-height: 1.75; }
	.body > * + * { margin-top: 1.2rem; }
	.body blockquote {
		font-family: var(--font-display);
		font-style: italic;
		font-size: 1.2rem;
		border-left: 2px solid var(--color-wax);
		padding-left: 1.2rem;
		margin-left: 0;
		color: var(--color-ink);
	}
	.body blockquote p { margin: 0; }
	hr { border: none; text-align: center; margin: 2.5rem 0; }
	hr::before {
		content: '✦ ✦ ✦'; letter-spacing: 0.8em; font-size: 0.7rem;
		color: var(--color-pencil-soft);
	}
	.meta { font-size: 0.92rem; color: var(--color-pencil); }

	/* "Buy me a coffee" — styled to sit inside the site's postal palette
	   rather than the loud official yellow. A small stamp-like button. */
	.support { margin-top: 1.8rem; }
	.bmc {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--color-paper-deep);
		color: var(--color-ink);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		padding: 0.5rem 0.95rem;
		text-decoration: none;
		font-family: var(--font-mono);
		font-size: 0.74rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		line-height: 1;
		transition: border-color 0.15s ease, color 0.15s ease;
	}
	.bmc:hover { border-color: var(--color-wax); color: var(--color-wax); }
	.bmc-cup { display: block; flex: 0 0 auto; }
</style>
