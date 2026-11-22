<script>
	import { page } from '$app/state';
	let { data } = $props();
</script>

<svelte:head><title>Follow — {page.data.site.name}</title></svelte:head>

<article class="follow">
	<p class="eyebrow">Follow</p>
	<h1 class="display title">New stories, as they're posted.</h1>
	<p class="lede">
		There is no newsletter and nothing to sign up for. The site publishes a feed, and any feed
		reader can follow it.
	</p>

	<section class="panel">
		<h2>Scan it</h2>
		<div class="qr" role="img" aria-label="QR code of this site's feed address">{@html data.qr}</div>
		<p>
			In the fartingaround app: Feeds, then <strong>Scan QR</strong>. Other feed readers that scan
			codes will take it too.
		</p>
	</section>

	<section class="panel">
		<h2>Or copy the address</h2>
		<label>JSON Feed <input readonly value={data.json} onfocus={(e) => e.currentTarget.select()} /></label>
		<label>RSS <input readonly value={data.rss} onfocus={(e) => e.currentTarget.select()} /></label>
		<p class="fine">Most readers also find the feed if you give them just the site's address.</p>
	</section>

	{#if data.member}
		<p class="fine">
			These carry the public stories only. Your personal feed link, which includes the private
			ones, is on <a href="/account">your account page</a>.
		</p>
	{/if}
</article>

<style>
	.follow { max-width: 36rem; margin: 0 auto; padding: 4rem 1.25rem 5rem; display: grid; gap: 1.2rem; }
	.title { margin: 0.25rem 0 0; }
	.lede { color: var(--color-ink-soft); line-height: 1.6; margin: 0; }
	.panel {
		border: 1px solid var(--color-pencil-soft); background: var(--color-paper-deep);
		border-radius: 4px; padding: 1rem 1.1rem; display: grid; gap: 0.8rem;
	}
	.panel h2 { font-family: var(--font-display); font-style: italic; font-weight: 500; font-size: 1.25rem; margin: 0; }
	.panel p { margin: 0; line-height: 1.55; color: var(--color-ink-soft); }
	.qr { width: 13rem; max-width: 100%; border: 1px solid var(--color-pencil-soft); border-radius: 4px; overflow: hidden; }
	.qr :global(svg) { display: block; width: 100%; height: auto; }
	label {
		display: grid; gap: 0.3rem; font-family: var(--font-mono); font-size: 0.7rem;
		letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil);
	}
	input {
		font-family: var(--font-mono); font-size: 0.8rem; letter-spacing: 0; text-transform: none;
		color: var(--color-ink); background: var(--color-paper); border: 1px solid var(--color-pencil-soft);
		border-radius: 3px; padding: 0.45rem 0.55rem; min-width: 0;
	}
	.fine { color: var(--color-pencil); font-size: 0.82rem; margin: 0; line-height: 1.5; }
	.fine a { color: var(--color-wax); }
</style>
