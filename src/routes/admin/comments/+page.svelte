<script>
	import { page } from '$app/state';

	let { data, form } = $props();
	// ?hidden narrows the list to comments you've hidden.
	let hiddenOnly = $derived(page.url.searchParams.has('hidden'));
	let hiddenCount = $derived(data.comments.filter((/** @type {any} */ c) => c.hidden).length);
	let comments = $derived(hiddenOnly ? data.comments.filter((/** @type {any} */ c) => c.hidden) : data.comments);

	function when(/** @type {string} */ iso) {
		try { return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }); } catch { return iso; }
	}
	/** @param {SubmitEvent} e */
	function sure(e) {
		if (!confirm('Delete this comment and any replies to it? This cannot be undone.')) e.preventDefault();
	}
</script>

<svelte:head><title>Comments — admin</title></svelte:head>

<header class="header">
	<h1 class="display">Comments</h1>
	<p class="eyebrow">{data.comments.length} most recent</p>
</header>

{#if form?.error}<p class="error" role="alert">{form.error}</p>{/if}

<nav class="a-chips" aria-label="Filter comments">
	<a class="a-chip" href="/admin/comments" aria-current={!hiddenOnly}>All <span class="n">{data.comments.length}</span></a>
	<a class="a-chip" href="/admin/comments?hidden" aria-current={hiddenOnly}>Hidden <span class="n">{hiddenCount}</span></a>
	<a class="settings-link" href="/admin/settings">Who sees comments →</a>
</nav>

{#if !comments.length}
	<p class="empty">{hiddenOnly ? 'No hidden comments.' : 'No comments yet.'}</p>
{:else}
	<ul class="list">
		{#each comments as c (c.id)}
			<li class="card" class:hidden={c.hidden}>
				<p class="meta">
					<strong>{c.name}</strong>
					<span>{c.isReply ? 'replied on' : 'on'} <a href="/stories/{c.storySlug}#comment-{c.id}">{c.storyTitle}</a>{c.photoId ? ' (about a photo)' : ''}</span>
					<span>· {when(c.createdAt)}</span>
					{#if c.hidden}<span class="badge">hidden</span>{/if}
				</p>
				<p class="body">{c.body}</p>
				<div class="actions">
					<form method="POST" action={c.hidden ? '?/show' : '?/hide'}>
						<input type="hidden" name="id" value={c.id} />
						<button type="submit">{c.hidden ? 'Show again' : 'Hide'}</button>
					</form>
					<form method="POST" action="?/delete" onsubmit={sure}>
						<input type="hidden" name="id" value={c.id} />
						<button type="submit" class="danger">Delete</button>
					</form>
				</div>
			</li>
		{/each}
	</ul>
{/if}

<style>
	.header { margin-bottom: 1.2rem; }
	.settings-link { margin-left: auto; align-self: center; font-size: 0.85rem; color: var(--color-pencil); }
	.list { list-style: none; padding: 0; margin: 0; display: grid; gap: 0.8rem; }
	.card { border: 1px solid var(--color-pencil-soft); background: var(--color-paper-deep); border-radius: 4px; padding: 0.8rem 1rem; display: grid; gap: 0.5rem; }
	.card.hidden { opacity: 0.75; border-style: dashed; }
	.meta { margin: 0; display: flex; flex-wrap: wrap; gap: 0.5rem; font-size: 0.85rem; color: var(--color-pencil); }
	.meta strong { color: var(--color-ink); }
	.body { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--color-ink-soft); }
	.actions { display: flex; gap: 0.5rem; }
	.badge { font-family: var(--font-mono); font-size: 0.62rem; letter-spacing: 0.12em; text-transform: uppercase; padding: 0.1rem 0.4rem; border-radius: 2px; background: var(--color-wax); color: var(--color-paper); }
	button {
		font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.12em; text-transform: uppercase;
		background: var(--color-paper); color: var(--color-ink); border: 1px solid var(--color-pencil-soft);
		border-radius: 3px; padding: 0.45rem 0.75rem; cursor: pointer;
	}
	button.danger { color: var(--color-wax); }
	.error { color: var(--color-wax); }
	.empty { padding: 3rem 1rem; text-align: center; color: var(--color-pencil); border: 1px dashed var(--color-pencil-soft); border-radius: 4px; }
</style>
