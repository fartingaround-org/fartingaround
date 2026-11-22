<script>
	import { page } from '$app/state';
	import './admin.css';

	let { children } = $props();

	const sections = [
		{ href: '/admin', label: 'Stories' },
		{ href: '/admin/readers', label: 'Readers' },
		{ href: '/admin/comments', label: 'Comments' },
		{ href: '/admin/settings', label: 'Settings' }
	];

	// Everything that is not one of the other sections (a story's editor, the
	// old phone drafts) belongs to Stories.
	let current = $derived(
		sections.slice(1).find((s) => page.url.pathname === s.href || page.url.pathname.startsWith(s.href + '/'))?.href ?? '/admin'
	);
</script>

<div class="admin-shell">
	<div class="ribbon">
		<span class="eyebrow">Admin</span>
		<nav class="nav" aria-label="Admin sections">
			{#each sections as s}
				<a href={s.href} aria-current={current === s.href ? 'page' : undefined}>{s.label}</a>
			{/each}
		</nav>
		<form method="POST" action="/admin/logout" class="logout">
			<button type="submit">Log out</button>
		</form>
	</div>
	{@render children?.()}
</div>

<style>
	.admin-shell { max-width: 80rem; margin: 0 auto; padding: 0 1.5rem 4rem; }
	.ribbon {
		display: grid;
		grid-template-columns: auto 1fr auto;
		grid-template-areas: 'label nav logout';
		align-items: center;
		gap: 0.4rem 1.2rem;
		padding: 0.35rem 0.9rem;
		background: var(--color-wax);
		color: var(--color-paper);
		font-family: var(--font-mono);
		font-size: 0.72rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		border-radius: 0 0 4px 4px;
		margin-bottom: 1.5rem;
	}
	.ribbon .eyebrow { grid-area: label; color: inherit; opacity: 0.8; }
	.nav { grid-area: nav; display: flex; gap: 0.2rem; }
	.nav a {
		display: inline-flex;
		align-items: center;
		min-height: 2.2rem;
		padding: 0 0.7rem;
		border-radius: 3px;
		color: inherit;
		text-decoration: none;
		opacity: 0.85;
	}
	.nav a:hover { opacity: 1; background: rgba(255, 255, 255, 0.12); }
	.nav a[aria-current='page'] { opacity: 1; background: var(--color-paper); color: var(--color-wax); font-weight: 600; }
	.logout { grid-area: logout; }
	.logout button {
		background: transparent;
		border: 1px solid rgba(255, 255, 255, 0.5);
		color: inherit;
		padding: 0.25rem 0.6rem;
		border-radius: 2px;
		font-family: inherit;
		font-size: 0.7rem;
		letter-spacing: inherit;
		text-transform: inherit;
		cursor: pointer;
	}
	.logout button:hover { background: rgba(255, 255, 255, 0.15); }

	/* Phone: the sections get their own full-width row of thumb-sized targets. */
	@media (max-width: 640px) {
		.admin-shell { padding: 0 0.9rem 4rem; }
		.ribbon {
			grid-template-columns: 1fr auto;
			grid-template-areas: 'label logout' 'nav nav';
			padding: 0.5rem 0.6rem 0.4rem;
		}
		.nav { justify-content: space-between; gap: 0; }
		.nav a { min-height: 2.75rem; padding: 0 0.5rem; letter-spacing: 0.08em; }
		.logout button { min-height: 2.2rem; }
	}
</style>
