<script>
	import { page } from '$app/state';
	import { imageUrl } from '#lib/image.js';
	import { formatDateShort } from '#lib/format.js';

	/** @type {{ data: { stories: any[], oldDrafts: number }, form?: { error?: string } }} */
	let { data, form } = $props();

	const today = new Date().toISOString().slice(0, 10);

	// The filter lives in the address (?show=drafts) so it survives a reload
	// and the trip back from a story's editor.
	const filters = [
		{ key: 'all', label: 'All', test: () => true },
		{ key: 'drafts', label: 'Drafts', test: (/** @type {any} */ s) => s.draft },
		{ key: 'published', label: 'Published', test: (/** @type {any} */ s) => !s.draft },
		{ key: 'private', label: 'Private', test: (/** @type {any} */ s) => s.private }
	];
	let active = $derived(filters.find((f) => f.key === page.url.searchParams.get('show')) ?? filters[0]);
	let shown = $derived(data.stories.filter(active.test));
</script>

<svelte:head><title>Admin — {page.data.site.name}</title></svelte:head>

<div class="grid">
	<section class="left">
		<h1 class="display title">Logbook</h1>
		<p class="lede">Every story, newest first. Anyone can read a published one unless it is marked private.</p>

		{#if data.oldDrafts}
			<p class="old-drafts">
				<a href="/admin/drafts">{data.oldDrafts} old phone {data.oldDrafts === 1 ? 'draft' : 'drafts'} still waiting →</a>
			</p>
		{/if}

		{#if data.stories.length === 0}
			<p class="empty hand">No stories yet. Write one →</p>
		{:else}
			<nav class="a-chips" aria-label="Filter stories">
				{#each filters as f}
					<a class="a-chip" href={f.key === 'all' ? '/admin' : `/admin?show=${f.key}`} aria-current={active.key === f.key}>
						{f.label} <span class="n">{data.stories.filter(f.test).length}</span>
					</a>
				{/each}
			</nav>
			{#if shown.length === 0}
				<p class="empty">Nothing here. <a href="/admin">Show all stories</a></p>
			{/if}
		{/if}

		<ul class="list">
			{#each shown as s (s.slug)}
				<li class="item">
					<a href={`/admin/${s.slug}`} class="row">
						{#if s.coverImageId}
							<div class="thumb">
								<img src={imageUrl(s.coverImageId, { w: 120, h: 120, fit: 'cover' })} alt="" />
							</div>
						{:else}
							<div class="thumb thumb--blank"></div>
						{/if}
						<div class="meta">
							<div class="title-row">
								<span class="entry-title">{s.title}</span>
								<!-- Published and public is the normal case, so only the exceptions are marked. -->
								{#if s.draft}<span class="a-badge draft">draft</span>{/if}
								{#if s.private}<span class="a-badge private">private</span>{/if}
							</div>
							<div class="sub">
								<span>{formatDateShort(s.date)}</span>
								{#if s.location}<span>· {s.location}</span>{/if}
								<span>· {s.blockCount} block{s.blockCount === 1 ? '' : 's'}</span>
							</div>
						</div>
					</a>
					<a class="view" href={`/stories/${s.slug}`} target="_blank" rel="noopener" aria-label={`View ${s.title} on the site`}>
						{s.draft ? 'Preview' : 'View'} ↗
					</a>
				</li>
			{/each}
		</ul>
	</section>

	<section class="right">
		<div class="panel">
			<h2 class="panel-title">New story</h2>
			<form method="POST" action="?/create" class="new-form">
				<label>Title <span class="muted">(optional)</span><input name="title" placeholder="leave blank for a note" /></label>
				<label>Date<input name="date" type="date" value={today} required /></label>
				<label>Slug <span class="muted">(optional)</span><input name="slug" placeholder="auto from title or date" /></label>
				{#if form?.error}<p class="err">{form.error}</p>{/if}
				<button type="submit" class="primary">Create draft</button>
			</form>
		</div>
	</section>
</div>

<style>
	.grid {
		display: grid;
		grid-template-columns: 1fr;
		gap: 2rem;
	}
	@media (min-width: 900px) { .grid { grid-template-columns: 1fr 22rem; } }

	.lede { color: var(--color-ink-soft); margin: 0 0 1.2rem; }
	.old-drafts { margin: 0 0 1.2rem; font-family: var(--font-mono); font-size: 0.78rem; }
	.empty { text-align: center; padding: 3rem 0; color: var(--color-pencil); font-size: 1.3rem; }

	.list { list-style: none; padding: 0; margin: 0; }
	.item {
		display: grid;
		grid-template-columns: 1fr auto;
		align-items: center;
		border-bottom: 1px dashed var(--color-pencil-soft);
	}
	.row {
		display: grid;
		grid-template-columns: 56px 1fr;
		gap: 1rem;
		align-items: center;
		padding: 0.9rem 0.6rem;
		text-decoration: none;
		color: inherit;
		min-width: 0;
		transition: background 0.2s ease;
	}
	.row:hover { background: rgba(179, 50, 42, 0.05); }
	.row:hover .entry-title { color: var(--color-wax); }
	.view {
		display: inline-flex; align-items: center; min-height: 2.75rem; padding: 0 0.7rem;
		font-family: var(--font-mono); font-size: 0.68rem; letter-spacing: 0.12em; text-transform: uppercase;
		color: var(--color-pencil); text-decoration: none; white-space: nowrap;
	}
	.view:hover { color: var(--color-wax); }

	.thumb { width: 56px; height: 56px; overflow: hidden; border-radius: 2px; background: var(--color-paper-deep); }
	.thumb img { width: 100%; height: 100%; object-fit: cover; }
	.thumb--blank { background: repeating-linear-gradient(45deg, var(--color-paper-deep) 0 6px, var(--color-paper) 6px 12px); }

	.title-row { display: flex; align-items: baseline; gap: 0.6rem; flex-wrap: wrap; }
	.entry-title {
		font-family: var(--font-display);
		font-style: italic;
		font-size: 1.1rem;
		font-weight: 500;
		color: var(--color-ink);
	}
	.sub {
		font-family: var(--font-mono);
		font-size: 0.7rem;
		color: var(--color-pencil);
		letter-spacing: 0.04em;
		display: flex; gap: 0.3em; flex-wrap: wrap;
	}
	.panel {
		background: var(--color-paper-deep);
		border-radius: 4px;
		padding: 1.4rem 1.4rem;
		margin-bottom: 1.5rem;
	}
	.panel-title {
		font-family: var(--font-display);
		font-style: italic;
		font-size: 1.4rem;
		font-weight: 500;
		margin: 0 0 1rem;
	}
	.new-form { display: grid; gap: 0.8rem; }
	.new-form label {
		display: grid; gap: 0.3rem;
		font-family: var(--font-mono);
		font-size: 0.7rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--color-pencil);
	}
	.new-form input {
		padding: 0.55rem 0.7rem;
		background: var(--color-paper);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-body);
		font-size: 0.95rem;
		letter-spacing: normal;
		text-transform: none;
		color: var(--color-ink);
	}
	.muted { color: var(--color-pencil-soft); text-transform: none; letter-spacing: normal; }

	.primary {
		padding: 0.6rem 1rem;
		background: var(--color-ink);
		color: var(--color-paper);
		border: none;
		border-radius: 3px;
		font-family: var(--font-mono);
		font-size: 0.74rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		cursor: pointer;
	}
	.primary:hover { background: var(--color-wax); }
	.err { font-family: var(--font-hand); color: var(--color-wax); font-size: 1.2rem; margin: 0; }

	/* Phone: the row gets the full width; View sits under it, in line with the text. */
	@media (max-width: 640px) {
		.item { grid-template-columns: 1fr; padding-bottom: 0.3rem; }
		.row { grid-template-columns: 44px 1fr; gap: 0.8rem; padding: 0.8rem 0 0.2rem; }
		.thumb { width: 44px; height: 44px; }
		.view { justify-self: start; padding: 0; margin-left: calc(44px + 0.8rem); }
	}
</style>
