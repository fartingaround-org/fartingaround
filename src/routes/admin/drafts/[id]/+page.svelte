<script>
	import { imageUrl } from '#lib/image.js';
	import { browser } from '$app/env';

	let { data } = $props();
	let d = $derived(data.draft);

	let promoteOpen = $state(false);
	let slug = $state('');
	let date = $state('');
	let location = $state('');
	let summary = $state('');

	// Autosave for the promote form. These fields aren't part of the synced
	// draft model — they only exist in this tab while you fill them in — so a
	// closed tab would lose them. We mirror them to localStorage (keyed by
	// draft id), restore on return, and clear once the draft is promoted.
	let restored = $state(false);
	let savedAt = $state('');
	let loadedId = '';
	/** @type {ReturnType<typeof setTimeout> | undefined} */
	let saveTimer;

	const storageKey = (/** @type {string} */ id) => `promote-draft:${id}`;

	function defaultSlug(/** @type {any} */ draft) {
		return draft.title?.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80)
			|| `draft-${draft.id.slice(0, 8)}`;
	}

	// Initialise (or restore) whenever we land on a different draft.
	$effect(() => {
		if (!d || d.id === loadedId) return;
		loadedId = d.id;

		const defaults = {
			slug: defaultSlug(d),
			date: d.capturedAt?.slice(0, 10) || new Date().toISOString().slice(0, 10),
			location: d.location || '',
			summary: ''
		};

		/** @type {any} */
		let saved = null;
		if (browser && d.status !== 'promoted') {
			try { saved = JSON.parse(localStorage.getItem(storageKey(d.id)) || 'null'); } catch { /* ignore */ }
		}

		slug = saved?.slug ?? defaults.slug;
		date = saved?.date ?? defaults.date;
		location = saved?.location ?? defaults.location;
		summary = saved?.summary ?? defaults.summary;

		// If we restored anything the user actually typed, surface the form so
		// they see their work waiting for them.
		const hasContent = saved && (
			(saved.summary ?? '').trim() ||
			saved.location !== defaults.location ||
			saved.slug !== defaults.slug ||
			saved.date !== defaults.date
		);
		if (hasContent) {
			promoteOpen = true;
			restored = true;
		}
	});

	// Debounced mirror to localStorage on every edit.
	$effect(() => {
		if (!d) return;
		// Track the fields so the effect re-runs when they change.
		const snapshot = { slug, date, location, summary };
		if (!browser || d.status === 'promoted' || d.id !== loadedId) return;

		clearTimeout(saveTimer);
		saveTimer = setTimeout(() => {
			try {
				localStorage.setItem(storageKey(d.id), JSON.stringify(snapshot));
				savedAt = new Date().toLocaleTimeString();
			} catch { /* ignore */ }
		}, 400);
	});

	// Belt-and-braces: write immediately if the tab is closing or hidden so we
	// never lose the last few keystrokes between debounce ticks.
	function flush() {
		if (!browser || !d || d.status === 'promoted' || d.id !== loadedId) return;
		try {
			localStorage.setItem(storageKey(d.id), JSON.stringify({ slug, date, location, summary }));
		} catch { /* ignore */ }
	}

	function clearStored() {
		if (!browser || !d) return;
		try { localStorage.removeItem(storageKey(d.id)); } catch { /* ignore */ }
	}

	// Discard the locally-saved draft and reset the form to defaults.
	function resetForm() {
		clearStored();
		slug = defaultSlug(d);
		date = d.capturedAt?.slice(0, 10) || new Date().toISOString().slice(0, 10);
		location = d.location || '';
		summary = '';
		restored = false;
		savedAt = '';
	}

	function fmt(/** @type {string} */ iso) {
		try { return new Date(iso).toLocaleString(); } catch { return iso; }
	}
</script>

<svelte:window onbeforeunload={flush} onpagehide={flush} />
<svelte:document onvisibilitychange={() => { if (document.visibilityState === 'hidden') flush(); }} />

{#if !d}
	<p>Draft not found.</p>
{:else}
	<header class="header">
		<a class="back" href="/admin/drafts">← all drafts</a>
		<h1 class="display">{d.title || 'Untitled'}</h1>
		<p class="meta">
			captured {fmt(d.capturedAt)}
			{#if d.location}· {d.location}{/if}
			{#if d.tags?.length}· {d.tags.join(', ')}{/if}
			· <span class="badge badge-{d.status}">{d.status}</span>
		</p>
		{#if d.coords}
			<p class="meta coords">
				<a
					href={`https://www.openstreetmap.org/?mlat=${d.coords.lat}&mlon=${d.coords.lon}#map=15/${d.coords.lat}/${d.coords.lon}`}
					target="_blank"
					rel="noopener noreferrer"
				>
					{d.coords.lat.toFixed(5)}, {d.coords.lon.toFixed(5)}
				</a>
				{#if d.coords.accuracy}<span class="acc">± {Math.round(d.coords.accuracy)} m</span>{/if}
			</p>
		{/if}
	</header>

	{#if d.body}
		<section class="block">
			<h3 class="eyebrow">Notes</h3>
			<p class="body-text">{d.body}</p>
		</section>
	{/if}

	{#if d.attachments?.length}
		<section class="attachments">
			<h3 class="eyebrow">Captured ({d.attachments.length})</h3>
			<ul class="att-list">
				{#each d.attachments as a (a.id)}
					<li class="att att-{a.kind}">
						<div class="att-head">
							<span class="kind">{a.kind}</span>
							<span class="when">{fmt(a.createdAt)}</span>
						</div>

						{#if a.kind === 'photo'}
							<img src={imageUrl(a.imageId, { w: 800, q: 85 })} alt={a.caption || ''} loading="lazy" />
						{:else if a.kind === 'audio'}
							<audio controls preload="none" src="/api/drafts/{d.id}/media/{a.id}"></audio>
							{#if a.transcript}
								<details class="transcript">
									<summary>Transcript</summary>
									<p>{a.transcript}</p>
								</details>
							{:else}
								<p class="hint">No transcript yet. The phone app can request one from ElevenLabs.</p>
							{/if}
						{:else if a.kind === 'video'}
							<!-- svelte-ignore a11y_media_has_caption -->
							<video controls preload="metadata" src="/api/drafts/{d.id}/media/{a.id}">
								<track kind="captions" />
							</video>
						{/if}

						{#if a.caption}
							<p class="caption">{a.caption}</p>
						{/if}
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	<section class="actions">
		{#if d.status === 'promoted'}
			<p class="hint">
				Already promoted to <a href="/admin/{d.promotedToSlug}">{d.promotedToSlug}</a>.
			</p>
		{:else}
			<button class="primary" type="button" onclick={() => promoteOpen = !promoteOpen}>
				{promoteOpen ? 'Cancel' : 'Promote to story →'}
			</button>
		{/if}

		<form method="POST" action="?/archive" class="inline">
			<button type="submit" class="ghost">Archive</button>
		</form>

		<form method="POST" action="?/destroy" class="inline"
			onsubmit={(e) => { if (!confirm('Delete this draft and its R2 media?')) e.preventDefault(); }}>
			<button type="submit" class="ghost danger">Delete</button>
		</form>
	</section>

	{#if promoteOpen && d.status !== 'promoted'}
		<form method="POST" action="?/promote" class="promote-form" onsubmit={clearStored}>
			{#if restored}
				<p class="restored">
					Restored your unsaved draft from this browser.
					<button type="button" class="link" onclick={resetForm}>Start fresh</button>
				</p>
			{/if}
			<label>Slug
				<input name="slug" bind:value={slug} required />
			</label>
			<label>Date
				<input name="date" type="date" bind:value={date} required />
			</label>
			<label>Location
				<input name="location" bind:value={location} placeholder="optional" />
			</label>
			<label>Summary
				<textarea name="summary" bind:value={summary} rows="2" placeholder="optional"></textarea>
			</label>
			<button class="primary" type="submit">Create story</button>
			<p class="hint">
				This creates a draft story you can polish in the existing editor. The
				draft stays here until you delete it.
				{#if savedAt}<span class="saved-note">· autosaved {savedAt}</span>{/if}
			</p>
		</form>
	{/if}
{/if}

<style>
	.header { margin-bottom: 1.5rem; }
	.back {
		font-family: var(--font-mono); font-size: 0.72rem; letter-spacing: 0.14em;
		text-transform: uppercase; color: var(--color-pencil); text-decoration: none;
	}
	.header h1 { font-size: 2.2rem; margin: 0.4rem 0 0.3rem; }
	.meta { font-family: var(--font-mono); font-size: 0.75rem; color: var(--color-pencil); margin: 0; }
	.meta.coords { margin-top: 0.25rem; }
	.meta.coords a { color: inherit; }
	.meta .acc { margin-left: 0.4em; }
	.badge {
		font-family: var(--font-mono); font-size: 0.65rem; letter-spacing: 0.14em;
		text-transform: uppercase; padding: 0.1rem 0.45rem; border-radius: 2px;
		background: var(--color-paper-deep); color: var(--color-pencil);
	}
	.badge-promoted { background: var(--color-postmark); color: var(--color-paper); }
	.badge-archived { background: var(--color-pencil-soft); color: var(--color-paper); }

	.block, .attachments { margin: 2rem 0; }
	.eyebrow { margin-bottom: 0.5rem; }
	.body-text { white-space: pre-wrap; line-height: 1.7; color: var(--color-ink-soft); max-width: var(--measure); }

	.att-list { list-style: none; padding: 0; display: grid; gap: 1.2rem; }
	.att {
		border: 1px solid var(--color-pencil-soft); padding: 0.8rem;
		border-radius: 4px; background: var(--color-paper-deep);
	}
	.att-head {
		display: flex; justify-content: space-between; align-items: center;
		font-family: var(--font-mono); font-size: 0.7rem; color: var(--color-pencil);
		text-transform: uppercase; letter-spacing: 0.12em; margin-bottom: 0.5rem;
	}
	.att img, .att video { max-width: 100%; height: auto; border-radius: 2px; display: block; }
	.att audio { width: 100%; }
	.transcript summary {
		font-family: var(--font-mono); font-size: 0.72rem; letter-spacing: 0.14em;
		text-transform: uppercase; color: var(--color-pencil); cursor: pointer;
		margin-top: 0.5rem;
	}
	.transcript p { margin: 0.5rem 0 0; line-height: 1.6; color: var(--color-ink-soft); }
	.hint { font-size: 0.85rem; color: var(--color-pencil); margin: 0.5rem 0 0; }
	.caption { font-style: italic; color: var(--color-pencil); margin: 0.5rem 0 0; }

	.actions { display: flex; gap: 0.5rem; flex-wrap: wrap; margin: 2rem 0 1rem; align-items: center; }
	.inline { display: inline; margin: 0; }
	button {
		font-family: var(--font-mono); font-size: 0.75rem; letter-spacing: 0.1em;
		text-transform: uppercase; padding: 0.5rem 0.9rem; border-radius: 2px;
		cursor: pointer; border: 1px solid var(--color-ink-soft); background: var(--color-paper);
	}
	button.primary { background: var(--color-wax); color: var(--color-paper); border-color: var(--color-wax); }
	button.ghost { background: transparent; }
	button.danger { color: var(--color-wax); border-color: var(--color-wax); }

	.promote-form {
		display: grid; gap: 0.7rem; max-width: 40rem; margin-top: 1rem;
		padding: 1rem; border: 1px dashed var(--color-pencil-soft); border-radius: 4px;
	}
	.promote-form label { display: grid; gap: 0.2rem; font-size: 0.85rem; color: var(--color-pencil); }
	.promote-form input, .promote-form textarea {
		padding: 0.45rem 0.6rem; border: 1px solid var(--color-pencil-soft);
		background: var(--color-paper); border-radius: 2px; font-family: var(--font-body);
	}
	.restored {
		margin: 0; font-size: 0.8rem; color: var(--color-postmark);
		display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap;
	}
	.link {
		font-family: var(--font-mono); font-size: 0.68rem; letter-spacing: 0.1em;
		text-transform: uppercase; padding: 0.2rem 0.5rem;
		border: 1px solid var(--color-pencil-soft); background: transparent;
		color: var(--color-pencil); border-radius: 2px; cursor: pointer;
	}
	.saved-note { color: var(--color-pencil); font-style: italic; }
</style>
