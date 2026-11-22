<script>
	import { page } from '$app/state';
	/** @type {{ form?: { error?: string } }} */
	let { form } = $props();
</script>

<svelte:head><title>Login — {page.data.site.name}</title></svelte:head>

<div class="wrap">
	<p class="eyebrow">Restricted</p>
	<h1 class="display title">Show me your stamp.</h1>
	<form method="POST" class="form">
		<label>
			Password
			<!-- svelte-ignore a11y_autofocus -->
			<input type="password" name="password" autocomplete="current-password" required autofocus />
		</label>
		{#if form?.error}<p class="err">{form.error}</p>{/if}
		<button type="submit" class="primary">Enter</button>
	</form>
	<p class="note">Single-author site — one password, set as ADMIN_PASSWORD.</p>
</div>

<style>
	.wrap { max-width: 28rem; margin: 6rem auto; padding: 0 1.5rem; text-align: center; }
	.title { font-size: clamp(2rem, 6vw, 3rem); margin: 0.4rem 0 2rem; }
	.form { display: grid; gap: 1rem; }
	label {
		display: grid; gap: 0.4rem; text-align: left;
		font-family: var(--font-mono); font-size: 0.72rem;
		letter-spacing: 0.14em; text-transform: uppercase;
		color: var(--color-pencil);
	}
	input[type="password"] {
		padding: 0.7rem 0.9rem;
		background: var(--color-paper-deep);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-body);
		font-size: 1rem;
		letter-spacing: normal;
		text-transform: none;
	}
	input[type="password"]:focus { outline: 2px solid var(--color-wax); outline-offset: 2px; }
	.primary {
		padding: 0.7rem 1rem;
		background: var(--color-ink);
		color: var(--color-paper);
		border: none;
		border-radius: 3px;
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		cursor: pointer;
	}
	.primary:hover { background: var(--color-wax); }
	.err {
		font-family: var(--font-hand);
		color: var(--color-wax);
		font-size: 1.2rem;
	}
	.note {
		margin-top: 2rem;
		font-family: var(--font-mono);
		font-size: 0.72rem;
		color: var(--color-pencil);
		letter-spacing: 0.08em;
	}
</style>
