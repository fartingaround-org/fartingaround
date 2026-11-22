<script>
	import { page } from '$app/state';
	import { browserSupportsWebAuthn, startAuthentication } from '@simplewebauthn/browser';
	import { onMount } from 'svelte';

	let supported = $state(true);
	let busy = $state(false);
	let problem = $state('');

	onMount(() => {
		supported = browserSupportsWebAuthn();
	});

	async function signIn() {
		busy = true;
		problem = '';
		try {
			const optionsJSON = await (await fetch('/signin/options', { method: 'POST' })).json();
			const response = await startAuthentication({ optionsJSON });
			const res = await fetch('/signin/verify', {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({ response })
			});
			const out = await res.json();
			if (out.ok) {
				window.location.href = '/account';
				return;
			}
			problem = out.error || 'Sign-in failed.';
		} catch (e) {
			// The person closing the browser's passkey sheet lands here too.
			const name = e instanceof Error ? e.name : '';
			problem = name === 'NotAllowedError' || name === 'AbortError' ? '' : "Couldn't sign in with a passkey on this device.";
		}
		busy = false;
	}
</script>

<svelte:head>
	<title>Sign in — {page.data.site.name}</title>
	<meta name="robots" content="noindex" />
</svelte:head>

<article class="door">
	<p class="eyebrow">Sign in</p>
	<h1 class="display title">Welcome back.</h1>
	{#if supported}
		<p class="lede">Sign in with the passkey you set up on this site. There is no password.</p>
		<button type="button" class="go" onclick={signIn} disabled={busy}>{busy ? 'Waiting for your passkey…' : 'Sign in with a passkey'}</button>
		{#if problem}<p class="error" role="alert">{problem}</p>{/if}
	{:else}
		<p class="lede">
			This browser can't use passkeys. If you opened this page from inside another app, open it in
			your phone's or computer's main browser instead.
		</p>
	{/if}
	<p class="fine">
		Accounts here are by invitation. No passkey yet, or lost your device? Ask the person who runs
		this site for a new link.
	</p>
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
	.go:disabled { opacity: 0.7; cursor: default; }
	.error { color: var(--color-wax); margin: 1rem 0 0; }
	.fine { color: var(--color-pencil); font-size: 0.85rem; margin-top: 1.6rem; line-height: 1.5; }

	@media (max-width: 640px) {
		.door { padding: 2rem 1.25rem 4rem; }
		.go { width: 100%; min-height: 48px; }
	}
</style>
