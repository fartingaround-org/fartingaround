<script>
	import { page } from '$app/state';
	import { invalidateAll } from '$app/navigation';
	import { browserSupportsWebAuthn, startRegistration } from '@simplewebauthn/browser';
	import { onMount } from 'svelte';

	let { data, form } = $props();

	let supported = $state(true);
	let busy = $state(false);
	let problem = $state('');
	let label = $state('');
	let copied = $state(false);

	/** A starting name for this device's passkey, e.g. "Chrome on Mac". */
	function guessLabel() {
		const ua = navigator.userAgent;
		const os = /Android/.test(ua) ? 'Android' : /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad'
			: /Mac/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : '';
		const browser = /Firefox\//.test(ua) ? 'Firefox' : /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome'
			: /Safari\//.test(ua) ? 'Safari' : '';
		return [browser, os].filter(Boolean).join(' on ');
	}

	onMount(() => {
		supported = browserSupportsWebAuthn();
		label = guessLabel();
	});

	async function addPasskey() {
		busy = true;
		problem = '';
		try {
			const optionsRes = await fetch('/account/passkey/options', { method: 'POST' });
			if (!optionsRes.ok) throw new Error('options');
			const response = await startRegistration({ optionsJSON: await optionsRes.json() });
			const res = await fetch('/account/passkey/verify', {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({ response, label })
			});
			const out = await res.json();
			if (out.ok) await invalidateAll();
			else problem = out.error || "That passkey couldn't be saved.";
		} catch (e) {
			const name = e instanceof Error ? e.name : '';
			if (name === 'InvalidStateError') problem = 'This device already has a passkey for this site.';
			else if (name !== 'NotAllowedError' && name !== 'AbortError') problem = "Couldn't create a passkey on this device.";
		}
		busy = false;
	}

	function when(/** @type {string | null} */ iso) {
		if (!iso) return 'never';
		try { return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }); } catch { return iso; }
	}
	async function copy(/** @type {string} */ text) {
		try {
			await navigator.clipboard.writeText(text);
			copied = true;
			setTimeout(() => (copied = false), 2000);
		} catch { /* the field is selectable */ }
	}
</script>

<svelte:head>
	<title>Account — {page.data.site.name}</title>
	<meta name="robots" content="noindex" />
</svelte:head>

<article class="account">
	{#if data.me}
		<p class="eyebrow">{data.welcome ? 'You are in' : 'Account'}</p>
		<h1 class="display title">{data.welcome ? `Welcome, ${data.me.displayName}.` : data.me.displayName}</h1>
		<p class="lede">
			{#if data.me.role === 'member'}
				You're signed in on this device as a member: you can read private stories here and comment.
			{:else}
				You're signed in on this device: you can comment on public stories here.
			{/if}
		</p>

		<section class="panel" class:nudge={!data.passkeys.length}>
			<h2>Passkeys</h2>
			{#if !data.passkeys.length}
				<p>
					<strong>Set up a passkey so you can get back in.</strong> It uses your fingerprint, face or
					screen lock instead of a password. Without one, you stay signed in on this device only, and
					would need a new link to sign in anywhere else.
				</p>
			{:else}
				<ul class="keys">
					{#each data.passkeys as k (k.id)}
						<li>
							<span>
								<strong>{k.label || 'Passkey'}</strong>
								<span class="fine">added {when(k.createdAt)} · last used {when(k.lastUsedAt)}</span>
							</span>
							<form method="POST" action="?/removePasskey">
								<input type="hidden" name="id" value={k.id} />
								<button type="submit" class="quiet">Remove</button>
							</form>
						</li>
					{/each}
				</ul>
			{/if}

			{#if supported}
				<div class="add">
					<label>Name for this device <input bind:value={label} maxlength="60" autocomplete="off" /></label>
					<button type="button" class="go" onclick={addPasskey} disabled={busy}>
						{busy ? 'Waiting for your device…' : data.passkeys.length ? 'Add a passkey on this device' : 'Set up a passkey'}
					</button>
				</div>
			{:else}
				<p class="fine">
					This browser can't create passkeys. If you opened this page from inside another app, open
					it in your phone's or computer's main browser to set one up.
				</p>
			{/if}
			{#if problem}<p class="error" role="alert">{problem}</p>{/if}
			{#if !data.passkeys.length && data.welcome}<p class="fine"><a href="/">Not now</a></p>{/if}
		</section>

		<section class="panel">
			<h2>Another device</h2>
			<p>
				If your passkey doesn't show up on your other device by itself, make a one-time code here and
				scan it with that device's camera. It signs that device in; then set up a passkey on it.
			</p>
			{#if form?.device}
				<div class="qr" role="img" aria-label="QR code that signs in your other device">
					{@html form.device.qr}
				</div>
				<p class="fine">Scan with your other device's camera, or copy the link instead:</p>
				<div class="link-row">
					<input readonly value={form.device.url} onfocus={(e) => e.currentTarget.select()} aria-label="Link for your other device" />
					<button type="button" onclick={() => copy(form?.device?.url ?? '')}>{copied ? 'Copied' : 'Copy'}</button>
				</div>
				<p class="fine">
					Works once, until {when(form.device.expiresAt)}. Anyone who scans it is signed in as you, so
					only show it to a device that is yours.
				</p>
			{:else}
				<form method="POST" action="?/device">
					<button type="submit">Show a code for another device</button>
				</form>
			{/if}
		</section>

		{#if data.me.role === 'member'}
			<section class="panel">
				<h2>Follow in a feed reader</h2>
				<p>
					A personal feed link that includes the private stories. Treat it like a key: anyone who
					has it can read them as you.
				</p>
				{#if form?.feed}
					<div class="qr" role="img" aria-label="QR code of your personal feed link">{@html form.feed.qr}</div>
					<p class="fine">In the fartingaround app: Feeds, then Scan QR. Or copy a link into any feed reader:</p>
					<label>JSON Feed <input readonly value={form.feed.json} onfocus={(e) => e.currentTarget.select()} /></label>
					<label>RSS <input readonly value={form.feed.rss} onfocus={(e) => e.currentTarget.select()} /></label>
					<p class="fine">
						Scan or copy now: it is shown once. The code is the same key as the link, so don't let
						anyone else scan it. If it gets out, make a new one and the old one stops working.
					</p>
				{:else}
					<form method="POST" action="?/feed"
						onsubmit={(e) => { if (data.feedSince && !confirm('Make a new feed link? The one you have now will stop working.')) e.preventDefault(); }}>
						<button type="submit">{data.feedSince ? 'Replace my feed link' : 'Create my feed link'}</button>
					</form>
					{#if data.feedSince}<p class="fine">You made a feed link on {when(data.feedSince)}.</p>{/if}
				{/if}
			</section>
		{/if}

		{#if form?.error}<p class="error" role="alert">{form.error}</p>{/if}

		<form method="POST" action="?/signout">
			<button type="submit" class="quiet">Sign out of this device</button>
		</form>

		<details class="leave">
			<summary>Delete my account</summary>
			<form method="POST" action="?/deleteAccount"
				onsubmit={(e) => { if (!confirm('Delete your account here? You will be signed out everywhere and will need a new invitation to come back.')) e.preventDefault(); }}>
				<p>
					This removes you from this site: your passkeys, sign-ins and feed link stop working at once,
					and what you wrote in your comments is erased.
				</p>
				<label>My comments
					<select name="comments">
						<option value="placeholder">leave "[deleted]" where they were</option>
						<option value="delete">remove them entirely where possible</option>
					</select>
				</label>
				<button type="submit" class="quiet">Delete my account</button>
			</form>
		</details>
	{:else}
		<p class="eyebrow">Account</p>
		<h1 class="display title">You're not signed in.</h1>
		<p class="lede">
			Accounts here are by invitation. If you were sent a link, open it on this device. If you've
			set up a passkey before, <a href="/signin">sign in with it</a>.
		</p>
	{/if}
</article>

<style>
	.account { max-width: 36rem; margin: 0 auto; padding: 4rem 1.25rem 5rem; display: grid; gap: 1.2rem; }
	.title { margin: 0.25rem 0 0; }
	.lede { color: var(--color-ink-soft); line-height: 1.6; margin: 0; }
	.lede a { color: var(--color-wax); }
	.panel {
		border: 1px solid var(--color-pencil-soft); background: var(--color-paper-deep);
		border-radius: 4px; padding: 1rem 1.1rem; display: grid; gap: 0.8rem;
	}
	.panel.nudge { border-color: var(--color-wax); }
	.panel h2 { font-family: var(--font-display); font-style: italic; font-weight: 500; font-size: 1.25rem; margin: 0; }
	.panel p { margin: 0; line-height: 1.55; color: var(--color-ink-soft); }
	.keys { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.5rem; }
	.keys li { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
	.keys li > span { display: grid; }
	.add { display: flex; flex-wrap: wrap; gap: 0.7rem; align-items: end; }
	label {
		display: grid; gap: 0.25rem; font-family: var(--font-mono); font-size: 0.68rem;
		letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil);
	}
	input {
		font-family: var(--font-body); font-size: 0.95rem; letter-spacing: 0; text-transform: none;
		color: var(--color-ink); background: var(--color-paper); border: 1px solid var(--color-pencil-soft);
		border-radius: 3px; padding: 0.45rem 0.55rem; min-width: 0;
	}
	button {
		font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.12em; text-transform: uppercase;
		background: var(--color-paper); color: var(--color-ink); border: 1px solid var(--color-pencil-soft);
		border-radius: 3px; padding: 0.55rem 0.9rem; cursor: pointer; justify-self: start;
	}
	button:hover { border-color: var(--color-ink); }
	button.go { background: var(--color-wax); border-color: var(--color-wax); color: var(--color-paper); }
	button.go:disabled { opacity: 0.7; cursor: default; }
	button.quiet { background: transparent; }
	button.quiet:hover { border-color: var(--color-wax); color: var(--color-wax); }
	.qr { width: 13rem; max-width: 100%; border: 1px solid var(--color-pencil-soft); border-radius: 4px; overflow: hidden; }
	.qr :global(svg) { display: block; width: 100%; height: auto; }
	.link-row { display: flex; gap: 0.5rem; }
	.link-row input { flex: 1; font-family: var(--font-mono); font-size: 0.8rem; }
	.fine { color: var(--color-pencil); font-size: 0.82rem; margin: 0; }
	.error { color: var(--color-wax); margin: 0; }
	.leave summary { cursor: pointer; color: var(--color-pencil); font-size: 0.85rem; }
	.leave form { display: grid; gap: 0.7rem; margin-top: 0.8rem; }
	.leave p { margin: 0; color: var(--color-ink-soft); line-height: 1.5; font-size: 0.9rem; }
	.leave select { font: inherit; padding: 0.35rem 0.4rem; }

	/* ── Phones: thumb-sized controls, one column ─────────────────────────── */
	@media (max-width: 640px) {
		.account { padding: 2rem 1.25rem 4rem; }
		input { min-height: 44px; font-size: 1rem; box-sizing: border-box; }
		button { min-height: 44px; }
		button.go { width: 100%; justify-self: stretch; }
		.add { flex-direction: column; align-items: stretch; }
		.link-row { flex-wrap: wrap; }
		.link-row input { flex: 1 1 100%; }
		.leave summary { padding: 0.7rem 0; }
		.leave select { min-height: 44px; }
	}
</style>
