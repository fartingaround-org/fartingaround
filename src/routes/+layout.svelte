<script>
	import '../app.css';
	import { page } from '$app/state';
	import { onMount } from 'svelte';

	/** @type {{ children: any, data: { site: Awaited<ReturnType<typeof import('#lib/server/settings.js').siteIdentity>>, isAdmin: boolean, reader: { displayName: string } | null, showFollowing: boolean } }} */
	let { children, data } = $props();

	// The header draws a name like "example.org" with its ending set apart.
	const mark = $derived.by(() => {
		const name = data.site.name;
		const dot = name.lastIndexOf('.');
		return dot > 0 ? { main: name.slice(0, dot), tld: name.slice(dot) } : { main: name, tld: '' };
	});

	const nav = $derived(
		[
			{ href: '/stories', label: 'Stories' },
			{ href: '/map', label: 'Map' },
			{ href: '/following', label: 'Following' }
		].filter((item) => item.href !== '/following' || data.showFollowing)
	);

	// Theme: follow the system unless the reader picks one. app.html applies a
	// saved choice before paint; this only keeps the footer button in step.
	/** @type {'auto' | 'ink' | 'paper'} */
	let theme = $state('auto');
	onMount(() => {
		const t = document.documentElement.dataset.theme;
		if (t === 'ink' || t === 'paper') theme = t;
	});
	function cycleTheme() {
		theme = theme === 'auto' ? 'ink' : theme === 'ink' ? 'paper' : 'auto';
		try {
			if (theme === 'auto') {
				delete document.documentElement.dataset.theme;
				localStorage.removeItem('theme');
			} else {
				document.documentElement.dataset.theme = theme;
				localStorage.setItem('theme', theme);
			}
		} catch { /* private mode: the choice just won't stick */ }
	}
</script>

<svelte:head>
	<link
		rel="alternate"
		type="application/rss+xml"
		title={data.site.name}
		href="/rss.xml"
	/>
	<link rel="alternate" type="application/feed+json" title={`${data.site.name} (JSON Feed)`} href="/feed.json" />
</svelte:head>

<div class="site">
	<header class="site-header">
		<a class="mark" href="/" aria-label={`${data.site.name} — home`}>
			<img class="mark-logo" src="/FartingAround.png" alt="" width="56" height="74" />
			<span class="mark-text">
				<span class="mark-domain">{mark.main}</span>{#if mark.tld}<span class="mark-tld">{mark.tld}</span>{/if}
			</span>
		</a>

		<nav class="site-nav" aria-label="Primary">
			{#each nav as item}
				{@const active = page.url.pathname === item.href || page.url.pathname.startsWith(item.href + '/')}
				<a href={item.href} class="nav-link" class:active>{item.label}</a>
			{/each}
			{#if data.reader}
				<a href="/account" class="nav-link nav-link--icon" class:active={page.url.pathname === '/account'}>
					<svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="8.5" r="3.6" /><path d="M4.5 20c1.2-3.6 4-5.4 7.5-5.4s6.3 1.8 7.5 5.4" /></svg>
					<span class="nav-label">Account</span>
				</a>
			{/if}
			{#if data.isAdmin}
				<a href="/admin" class="nav-link nav-link--icon admin">
					<svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20l1-4.5L16.5 4l3.5 3.500L8.5 19z" /><path d="M14 6.500l3.500 3.500" /></svg>
					<span class="nav-label">Admin</span>
				</a>
			{/if}
		</nav>
	</header>

	<main class="site-main">
		{@render children?.()}
	</main>

	<footer class="site-footer">
		<div class="postmark" aria-hidden="true">
			<svg viewBox="0 0 160 160">
				<defs>
					<path id="footer-arc-top" d="M 80 80 m -58 0 a 58 58 0 1 1 116 0" />
					<path id="footer-arc-bot" d="M 80 80 m -58 0 a 58 58 0 1 0 116 0" />
				</defs>
				<!--
					Same trick as BlockDivider: split the dotted ring into two
					arcs so the text doesn't get cut. Centre (80,80), r=62.
					Top text "FARTING AROUND" is long, so we open a wider gap
					at 12 o'clock (~120°, 60° each side); the bottom text
					"HERE ON EARTH" fits cleanly with a smaller gap (~50°).
						right: (133.69, 49.00) → (106.20, 136.19)
						left:  (53.80, 136.19) → (26.31, 49.00)
				-->
				<path d="M 133.69 49.00 A 62 62 0 0 1 106.20 136.19" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="2 4" />
				<path d="M 53.80 136.19 A 62 62 0 0 1 26.31 49.00" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="2 4" />
				<circle cx="80" cy="80" r="47" fill="none" stroke="currentColor" stroke-width="1" />
				<text font-family="JetBrains Mono, monospace" font-size="11" letter-spacing="1.4" fill="currentColor">
					<textPath href="#footer-arc-top" startOffset="50%" text-anchor="middle">· FARTING AROUND ·</textPath>
				</text>
				<text font-family="JetBrains Mono, monospace" font-size="10.5" letter-spacing="1.2" fill="currentColor">
					<textPath href="#footer-arc-bot" startOffset="50%" text-anchor="middle">HERE ON EARTH</textPath>
				</text>
				<g transform="translate(80 80)">
					<line x1="-18" y1="0" x2="18" y2="0" stroke="currentColor" stroke-width="1" />
					<line x1="0" y1="-18" x2="0" y2="18" stroke="currentColor" stroke-width="1" />
				</g>
			</svg>
		</div>
		<div class="signoff">
			<p class="colophon">A logbook of farting around.</p>
			<p class="aside hand">Don't let anybody tell you any different.</p>
		</div>

		<nav class="footer-nav" aria-label="Footer">
			<a href="/about">About</a>
			<a href="/follow">Follow</a>
			{#if !data.reader && !data.isAdmin}<a href="/signin">Sign in</a>{/if}
			{#if data.site.supportUrl}<a href={data.site.supportUrl} target="_blank" rel="noopener noreferrer">{data.site.supportLabel} ↗</a>{/if}
		</nav>

		<button type="button" class="theme-toggle" onclick={cycleTheme} aria-label={`Theme: ${theme}. Change theme`}>
			<span class="theme-dot" aria-hidden="true"></span>Theme · {theme}
		</button>
	</footer>
</div>

<style>
	.site { display: grid; grid-template-rows: auto 1fr auto; min-height: 100dvh; }
	.site-header {
		display: flex; align-items: center; justify-content: space-between;
		padding: 1.4rem clamp(1rem, 4vw, 2.5rem);
		max-width: 80rem; width: 100%; margin: 0 auto;
	}
	.mark {
		display: inline-flex; align-items: center; gap: 0.65rem;
		color: var(--color-ink); text-decoration: none;
		font-family: var(--font-display);
	}
	.mark-logo {
		width: 44px;
		height: auto;
		mix-blend-mode: var(--logo-blend, multiply);
		transform: rotate(-3deg);
		transition: transform 0.3s ease;
		filter: var(--logo-filter, contrast(1.05));
	}
	.mark:hover .mark-logo { transform: rotate(2deg) translateY(-2px); }
	.mark-text { font-size: 1.05rem; font-weight: 500; letter-spacing: -0.01em; }
	.mark-domain { font-style: italic; }
	.mark-tld { color: var(--color-wax); }

	/* Below ~640px the wordmark crowds into the nav links — drop it and
	   let the logo carry the brand. The <a> still has aria-label set to
	   the full name, so screen readers don't lose anything. */
	@media (max-width: 640px) {
		.mark-text { display: none; }
	}

	.site-nav { display: flex; gap: 1.5rem; }
	.nav-link {
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.18em;
		text-transform: uppercase;
		text-decoration: none;
		color: var(--color-pencil);
		position: relative;
	}
	.nav-link:hover { color: var(--color-ink); }
	.nav-link.active { color: var(--color-ink); }
	.nav-link.active::after {
		content: ''; position: absolute; left: 0; right: 0; bottom: -6px;
		height: 2px; background: var(--color-wax);
	}
	.nav-link.admin { color: var(--color-wax); }

	.site-main { width: 100%; }

	/* The footer reads top to bottom as a sign-off: the stamp, one line in the
	   site's voice, then the links, then the one setting. Three levels of
	   weight instead of a stack of identical lines. */
	.site-footer {
		padding: 3.5rem 1.5rem calc(2.5rem + env(safe-area-inset-bottom));
		text-align: center;
		color: var(--color-pencil);
		border-top: 1px dashed var(--color-pencil-soft);
		margin-top: 6rem;
		display: grid; gap: 1.6rem; justify-items: center;
	}
	.postmark { width: 136px; color: var(--color-postmark); opacity: 0.7; transform: rotate(-8deg); }
	.postmark svg { width: 100%; height: auto; display: block; }
	.signoff { display: grid; gap: 0.2rem; }
	.colophon {
		margin: 0;
		font-family: var(--font-display); font-style: italic;
		font-size: 1.3rem; line-height: 1.3; color: var(--color-ink-soft);
	}
	.aside { margin: 0; font-size: 1.25rem; color: var(--color-pencil); }

	.footer-nav {
		display: flex; flex-wrap: wrap; justify-content: center; gap: 0 1.8rem;
		font-family: var(--font-mono); font-size: 0.72rem;
		letter-spacing: 0.12em; text-transform: uppercase;
	}
	.footer-nav a {
		display: inline-flex; align-items: center; min-height: 44px;
		color: var(--color-pencil); text-decoration: none;
		background: linear-gradient(var(--color-wax), var(--color-wax)) no-repeat 0 calc(50% + 0.85em) / 0 1px;
		transition: color 0.15s ease, background-size 0.2s ease;
	}
	.footer-nav a:hover { color: var(--color-ink); background-size: 100% 1px; }

	/* ── Phones ────────────────────────────────────────────────────────────
	   The nav row never shrinks, so without this the logo was squeezed to
	   nothing and the last link ran off a 360px screen. Keep the logo as the
	   home link, show three links (About lives in the footer on every screen), and turn
	   Account/Admin into icons. Every target is at least 44px tall. */
	.mark { flex-shrink: 0; }
	.site-nav { align-items: center; }
	.nav-icon { display: none; }
	@media (max-width: 640px) {
		.site-header { padding: 0.4rem 0.75rem; gap: 0.25rem; }
		.mark { min-height: 44px; }
		.mark-logo { width: 38px; }
		.site-nav { gap: 0; }
		.nav-link {
			display: inline-flex; align-items: center;
			min-height: 44px; padding: 0 0.4rem;
			font-size: 0.72rem; letter-spacing: 0.08em;
		}
		.nav-link.active::after { left: 0.4rem; right: 0.4rem; bottom: 8px; }
		.nav-link--icon { min-width: 40px; padding: 0; justify-content: center; }
		.nav-icon { display: block; width: 22px; height: 22px; }
		.nav-label {
			position: absolute; width: 1px; height: 1px; overflow: hidden;
			clip-path: inset(50%); white-space: nowrap;
		}
		.site-footer { margin-top: 4rem; gap: 1.2rem; }
		.footer-nav { gap: 0 1.4rem; }
		.site-footer .theme-toggle { min-height: 44px; }
	}

	.theme-toggle {
		display: inline-flex; align-items: center; gap: 0.5rem;
		min-height: 36px; padding: 0 0.9rem;
		font-family: var(--font-mono); font-size: 0.66rem;
		letter-spacing: 0.12em; text-transform: uppercase;
		color: var(--color-pencil); background: transparent; cursor: pointer;
		border: 1px solid var(--color-pencil-soft); border-radius: 999px;
	}
	.theme-toggle:hover { color: var(--color-ink); border-color: var(--color-ink); }
	/* Half paper, half ink: says "light / dark" without a word. */
	.theme-dot {
		width: 0.7rem; height: 0.7rem; border-radius: 50%;
		border: 1px solid currentColor;
		background: linear-gradient(90deg, transparent 50%, currentColor 50%);
	}
	@media (prefers-reduced-motion: reduce) { .footer-nav a { transition: none; } }
</style>
