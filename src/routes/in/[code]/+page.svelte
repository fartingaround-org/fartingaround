<script>
	import { page } from '$app/state';
	let { data, form } = $props();
	let link = $derived(form?.gone ? null : data.link);
</script>

<svelte:head>
	<title>Sign in — {page.data.site.name}</title>
	<meta name="robots" content="noindex" />
	<meta name="referrer" content="same-origin" />
</svelte:head>

<article class="door">
	{#if link}
		<p class="eyebrow">{link.purpose === 'add_device' ? 'Add this device' : 'You have been invited'}</p>
		<h1 class="display title">Hello, {link.displayName}.</h1>
		{#if link.purpose === 'add_device'}
			<p class="lede">This signs this device in to your account here. Your other devices stay signed in.</p>
		{:else}
			<p class="lede">
				This link signs you in on this device. It works once. If you were signed in anywhere
				before, those devices will be signed out.
			</p>
		{/if}
		<form method="POST">
			<button type="submit" class="go">Continue as {link.displayName}</button>
		</form>
		<p class="fine">Not {link.displayName}? This link was meant for someone else. Please close this page.</p>
	{:else}
		<p class="eyebrow">Sign-in link</p>
		<h1 class="display title">This link can't be used.</h1>
		<p class="lede">
			It has already been used, has been replaced by a newer one, or has expired. Ask the person
			who sent it for a new link.
		</p>
	{/if}
</article>

<style>
	.door { max-width: 34rem; margin: 0 auto; padding: 4rem 1.25rem 5rem; }
	.title { margin: 0.25rem 0 0.9rem; }
	.lede { color: var(--color-ink-soft); line-height: 1.6; margin: 0 0 1.6rem; }
	.go {
		font-family: var(--font-mono); font-size: 0.8rem; letter-spacing: 0.14em; text-transform: uppercase;
		background: var(--color-wax); color: var(--color-paper); border: 0; border-radius: 3px;
		padding: 0.85rem 1.3rem; cursor: pointer;
	}
	.go:hover { filter: brightness(1.08); }
	.fine { color: var(--color-pencil); font-size: 0.85rem; margin-top: 1.6rem; }

	@media (max-width: 640px) {
		.door { padding: 2rem 1.25rem 4rem; }
		.go { width: 100%; min-height: 48px; }
	}
</style>
