<script>
	import { page } from '$app/state';

	let { data } = $props();
	let all = $derived(data.drafts || []);
	// Archived drafts are out of the way unless asked for. The choice lives in
	// the address (?archived) so it survives a reload and the back button.
	let showArchived = $derived(page.url.searchParams.has('archived'));
	let archivedCount = $derived(all.filter((/** @type {any} */ d) => d.status === 'archived').length);
	let waiting = $derived(all.filter((/** @type {any} */ d) => d.status === 'open').length);
	let drafts = $derived(showArchived ? all : all.filter((/** @type {any} */ d) => d.status !== 'archived'));

	function fmtDate(/** @type {string} */ iso) {
		try { return new Date(iso).toLocaleString(); } catch { return iso; }
	}
	function counts(/** @type {any} */ d) {
		const c = { photo: 0, audio: 0, video: 0 };
		for (const a of d.attachments || []) {
			if (a.kind in c) c[/** @type {'photo'|'audio'|'video'} */ (a.kind)]++;
		}
		return c;
	}
</script>

<header class="header">
	<a href="/admin" class="back">← all stories</a>
	<h1 class="display">Old phone drafts</h1>
	<p class="eyebrow">{waiting} {waiting === 1 ? 'draft' : 'drafts'} waiting</p>
	{#if archivedCount}
		<a class="filter" href={showArchived ? '/admin/drafts' : '/admin/drafts?archived'} aria-pressed={showArchived} role="button">
			{showArchived ? 'Hide archived' : `Show archived (${archivedCount})`}
		</a>
	{/if}
</header>

{#if !drafts.length}
	<p class="empty">
		{archivedCount ? 'Nothing waiting. Everything here has been archived.' : "Nothing yet. Capture something on the phone and it'll show up here."}
	</p>
{:else}
	<ul class="list">
		{#each drafts as d (d.id)}
			{@const c = counts(d)}
			<li class="card" class:archived={d.status === 'archived'}>
				<a href="/admin/drafts/{d.id}" class="card-link">
					<div class="card-head">
						<h2>{d.title || 'Untitled'}</h2>
						<span class="badge badge-{d.status}">{d.status}</span>
					</div>
					{#if d.body}
						<p class="card-body">{d.body.slice(0, 220)}{d.body.length > 220 ? '…' : ''}</p>
					{/if}
					<div class="card-meta">
						<span>captured {fmtDate(d.capturedAt)}</span>
						{#if d.location}<span>· {d.location}</span>{/if}
						{#if c.photo}<span>· {c.photo} 📷</span>{/if}
						{#if c.audio}<span>· {c.audio} 🎙</span>{/if}
						{#if c.video}<span>· {c.video} 🎬</span>{/if}
						{#if d.tags?.length}<span>· {d.tags.join(', ')}</span>{/if}
					</div>
				</a>
			</li>
		{/each}
	</ul>
{/if}

<style>
	.header { margin-bottom: 1.6rem; }
	.back {
		display: inline-block; margin-bottom: 0.5rem; font-family: var(--font-mono); font-size: 0.72rem;
		letter-spacing: 0.14em; text-transform: uppercase; color: var(--color-pencil); text-decoration: none;
	}
	.back:hover { color: var(--color-wax); }
	.filter {
		display: inline-block; margin-top: 0.7rem; padding: 0.4rem 0.7rem;
		font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.12em; text-transform: uppercase;
		color: var(--color-ink); text-decoration: none;
		border: 1px solid var(--color-pencil-soft); border-radius: 3px;
	}
	.filter:hover { border-color: var(--color-ink); }
	.filter[aria-pressed='true'] { background: var(--color-ink); color: var(--color-paper); border-color: var(--color-ink); }
	.card.archived { opacity: 0.75; border-style: dashed; }
	.empty {
		padding: 3rem 1rem; text-align: center; color: var(--color-pencil);
		border: 1px dashed var(--color-pencil-soft); border-radius: 4px;
	}
	.list { list-style: none; padding: 0; margin: 0; display: grid; gap: 0.9rem; }
	.card {
		border: 1px solid var(--color-pencil-soft);
		background: var(--color-paper-deep);
		border-radius: 4px;
	}
	.card-link {
		display: block; padding: 1rem 1.1rem; text-decoration: none; color: inherit;
	}
	.card-link:hover { background: rgba(0,0,0,0.02); }
	.card-head {
		display: flex; align-items: baseline; justify-content: space-between; gap: 1rem;
		margin-bottom: 0.4rem;
	}
	.card-head h2 {
		font-family: var(--font-display); font-style: italic; font-weight: 500;
		font-size: 1.35rem; margin: 0; line-height: 1.2;
	}
	.badge {
		font-family: var(--font-mono); font-size: 0.65rem; letter-spacing: 0.14em;
		text-transform: uppercase; padding: 0.15rem 0.5rem; border-radius: 2px;
		background: var(--color-paper); color: var(--color-pencil);
	}
	.badge-promoted { background: var(--color-postmark); color: var(--color-paper); }
	.badge-archived { background: var(--color-pencil-soft); color: var(--color-paper); }
	.card-body { color: var(--color-ink-soft); margin: 0.2rem 0 0.6rem; line-height: 1.5; }
	.card-meta {
		display: flex; flex-wrap: wrap; gap: 0.5rem; color: var(--color-pencil);
		font-family: var(--font-mono); font-size: 0.72rem;
	}
</style>
