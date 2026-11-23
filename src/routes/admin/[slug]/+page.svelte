<script>
	import CoverPicker from '#lib/components/admin/CoverPicker.svelte';
	import BlockEditor from '#lib/components/admin/BlockEditor.svelte';
	import StoryBlocks from '#lib/components/StoryBlocks.svelte';
	import { blockId, photoLocation } from '#lib/blocks.js';
	import { formatDateShort } from '#lib/format.js';
	import { parseCoordsInput } from '#lib/coords.js';

	/** @type {{ data: { story: import('#lib/types.js').Story, defaultVisibility: 'public' | 'private' }, form?: any }} */
	let { data, form } = $props();

	// Pending coords input — bound to the textbox in the frontmatter. We parse
	// on every keystroke for live feedback but only commit on the Apply
	// button so an in-progress paste doesn't mark the story dirty.
	let coordsInput = $state('');
	let coordsParsed = $derived(coordsInput.trim() ? parseCoordsInput(coordsInput) : null);
	let coordsInputError = $derived(coordsInput.trim() && !coordsParsed);

	function applyCoords() {
		if (!coordsParsed) return;
		story.coords = { lat: coordsParsed.lat, lon: coordsParsed.lon };
		coordsInput = '';
	}

	function clearCoords() {
		story.coords = undefined;
	}

	// Local mutable copy. We intentionally snapshot data.story once — saving
	// stays within this page, so we don't need to react to data updates.
	// svelte-ignore state_referenced_locally
	let story = $state(structuredClone(data.story));

	// A photo in the story that knows where it was taken: offered as the story's
	// location while the story has none. The place name is filled in only when
	// the Location field is empty.
	const photoPlace = $derived(story.coords ? null : photoLocation(story));
	let namingPlace = $state(false);
	async function usePhotoLocation() {
		const at = photoPlace;
		if (!at) return;
		story.coords = { lat: at.lat, lon: at.lon };
		if ((story.location || '').trim()) return;
		namingPlace = true;
		try {
			const res = await fetch(`/admin/api/reverse-geocode?lat=${at.lat}&lon=${at.lon}`);
			const name = res.ok ? (await res.json()).name : null;
			if (name && !(story.location || '').trim()) story.location = name;
		} catch {
			// The coordinates are set; the name can be typed.
		} finally {
			namingPlace = false;
		}
	}
	let saving = $state(false);
	let saved = $state('');
	let preview = $state(false);
	let error = $state('');

	// Track dirty by comparing serialized snapshots.
	// svelte-ignore state_referenced_locally
	let initial = $state(JSON.stringify(data.story));
	// The server `updatedAt` this editor's copy is based on. Sent with every
	// save so a story edited from the phone in the meantime isn't silently
	// overwritten; the server answers 409 and we ask.
	// svelte-ignore state_referenced_locally
	let baseUpdatedAt = $state(data.story.updatedAt);
	let dirty = $derived(JSON.stringify(story) !== initial);

	// Tags are typed as one comma-separated line; the story keeps them as a list.
	// svelte-ignore state_referenced_locally
	let tagsInput = $state((data.story.tags || []).join(', '));
	function applyTags() {
		const seen = new Set();
		story.tags = tagsInput
			.split(',')
			.map((t) => t.trim())
			.filter((t) => t && !seen.has(t.toLowerCase()) && seen.add(t.toLowerCase()));
	}

	// One control for who can see the story. Draft leaves the visibility it
	// already has alone; Private and Public both publish.
	let status = $derived(story.draft ? 'draft' : story.visibility === 'private' ? 'private' : 'public');
	/** @param {'draft' | 'private' | 'public'} next */
	function setStatus(next) {
		if (next === 'draft') {
			story.draft = true;
		} else {
			story.draft = false;
			story.visibility = next;
		}
	}
	// True while an unsaved change would put the story in public for the first time.
	let goingPublic = $derived.by(() => {
		const was = JSON.parse(initial);
		return status === 'public' && (was.draft || was.visibility === 'private');
	});

	/** @param {import('#lib/types.js').Block['type']} type */
	function addBlock(type) {
		const id = blockId();
		const newBlock = (() => {
			switch (type) {
				case 'text': return { type, id, body: '' };
				case 'photo': return { type, id, imageId: '', caption: '', alt: '', size: 'inline', aspect: '4/5' };
				case 'diptych': return { type, id, left: { imageId: '' }, right: { imageId: '' }, caption: '', aspect: '4/5' };
				case 'dump': return { type, id, photos: [], caption: '', size: 'wide', rowSize: 'm', slideshowSeconds: 5 };
				case 'pull': return { type, id, body: '' };
				case 'aside': return { type, id, body: '', label: '' };
				case 'divider': return { type, id, place: '', date: '' };
				case 'heading': return { type, id, body: '' };
				case 'code': return { type, id, body: '', caption: '' };
			}
		})();
		story.blocks = [...story.blocks, /** @type {any} */ (newBlock)];
	}

	function updateBlock(/** @type {number} */ i, /** @type {any} */ block) {
		story.blocks = story.blocks.map((b, idx) => (idx === i ? block : b));
	}

	function removeBlock(/** @type {number} */ i) {
		if (!confirm('Remove this block?')) return;
		story.blocks = story.blocks.filter((_, idx) => idx !== i);
	}

	function moveBlock(/** @type {number} */ i, /** @type {-1 | 1} */ dir) {
		const j = i + dir;
		if (j < 0 || j >= story.blocks.length) return;
		const arr = [...story.blocks];
		[arr[i], arr[j]] = [arr[j], arr[i]];
		story.blocks = arr;
	}

	/**
	 * Going public can't be fully undone: once photos have been fetched or
	 * cached, making the story private later doesn't recall those copies.
	 * Ask before a private story becomes public, and before any public
	 * publish on a site whose new stories default to private.
	 */
	function okToGoPublic() {
		const was = JSON.parse(initial);
		const wasPublic = !was.draft && was.visibility !== 'private';
		const willBePublic = !story.draft && story.visibility !== 'private';
		if (wasPublic || !willBePublic) return true;
		if (was.visibility !== 'private' && data.defaultVisibility !== 'private') return true;
		return confirm(
			'Publish this story publicly?\n\n' +
				'Anyone will be able to read it and see its photos. If you make it private again later, ' +
				'copies that were already fetched or cached can stay reachable for a while to anyone who has their exact address.'
		);
	}

	/** @param {boolean} [force] */
	async function save(force = false) {
		if (!force && !okToGoPublic()) return;
		saving = true;
		error = '';
		try {
			const res = await fetch(`/admin/${data.story.slug}/save`, {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({
					baseUpdatedAt,
					force,
					title: story.title,
					date: story.date,
					location: story.location || '',
					// null tells the server to clear; an object updates; undefined
					// would mean "don't touch" but the spread above always sets it,
					// so we send null explicitly when the user clicked Clear.
					coords: story.coords || null,
					summary: story.summary || '',
					coverImageId: story.coverImageId || '',
					coverFocus: story.coverFocus || { x: 0.5, y: 0.5 },
					tags: story.tags || [],
					draft: !!story.draft,
					visibility: story.visibility === 'private' ? 'private' : 'public',
					blocks: story.blocks
				})
			});
			if (res.status === 409) {
				const body = await res.json().catch(() => ({}));
				const when = body?.current?.updatedAt ? formatDateShort(body.current.updatedAt) : 'since you opened it';
				saving = false;
				if (confirm(`This story was saved elsewhere (${when}) — from the phone, probably. Overwrite that version with what you have here?\n\nCancel to keep theirs: reload the page to see it.`)) {
					return save(true);
				}
				error = 'Not saved — the server has a newer version. Reload to see it.';
				return;
			}
			if (!res.ok) {
				const body = await res.text();
				throw new Error(body || `Save failed: ${res.status}`);
			}
			const result = await res.json().catch(() => ({}));
			if (result?.updatedAt) baseUpdatedAt = result.updatedAt;
			initial = JSON.stringify(story);
			saved = formatDateShort(new Date().toISOString());
			setTimeout(() => { saved = ''; }, 3000);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Save failed';
		} finally {
			saving = false;
		}
	}

	// A bulk photo dump can be a couple hundred uploads — losing the block
	// references by closing the tab before saving would orphan them all in
	// Cloudflare Images. Ask before leaving with unsaved changes.
	function handleBeforeUnload(/** @type {BeforeUnloadEvent} */ e) {
		if (!dirty) return;
		e.preventDefault();
	}

	function handleKeydown(/** @type {KeyboardEvent} */ e) {
		if ((e.metaKey || e.ctrlKey) && e.key === 's') {
			e.preventDefault();
			if (dirty && !saving) save();
		}
	}
</script>

<svelte:window onkeydown={handleKeydown} onbeforeunload={handleBeforeUnload} />

<svelte:head><title>Editing {story.title} — Admin</title></svelte:head>

{#snippet addButtons()}
	<button type="button" onclick={() => addBlock('text')}>¶ Text</button>
	<button type="button" onclick={() => addBlock('photo')}>📷 Photo</button>
	<button type="button" onclick={() => addBlock('diptych')}>▢▢ Diptych</button>
	<button type="button" onclick={() => addBlock('dump')}>▦▦ Photo dump</button>
	<button type="button" onclick={() => addBlock('pull')}>" Pull-quote</button>
	<button type="button" onclick={() => addBlock('aside')}>✎ Aside</button>
	<button type="button" onclick={() => addBlock('heading')}>H Heading</button>
	<button type="button" onclick={() => addBlock('code')}>&lt;/&gt; Code</button>
	<button type="button" onclick={() => addBlock('divider')}>◯ Divider</button>
{/snippet}

<div class="editor">
	<!-- Stays in view however long the story is, so Save is never a scroll away. -->
	<div class="bar">
		<a href="/admin" class="back">← all stories</a>
		<span class="state" class:unsaved={dirty && !saving} role="status">
			{saving ? 'Saving…' : dirty ? 'Unsaved changes' : saved ? `Saved at ${saved}` : 'All changes saved'}
		</span>
		<div class="bar-actions">
			<a class="btn ghost" href={`/stories/${data.story.slug}`} target="_blank" rel="noopener">{status === 'draft' ? 'View draft' : 'View'} ↗</a>
			<button type="button" class="btn ghost" onclick={() => (preview = !preview)}>{preview ? 'Edit' : 'Preview'}</button>
			<button type="button" class="btn primary" disabled={!dirty || saving} onclick={() => save()}>
				{saving ? 'Saving…' : 'Save'}
			</button>
		</div>
	</div>

	{#if error}<p class="err-banner" role="alert">{error}</p>{/if}

	<header class="editor-head">
		<div class="title-area">
			<input
				class="title-input display"
				placeholder="Untitled"
				bind:value={story.title}
			/>
			<div class="frontmatter">
				<label class="inline">
					<span>Date</span>
					<input type="date" bind:value={story.date} />
				</label>
				<label class="inline">
					<span>Location</span>
					<input bind:value={story.location} placeholder="Lisbon, PT" />
				</label>
			</div>

			<fieldset class="status">
				<legend>Who can see it</legend>
				<div class="segments">
					{#each [['draft', 'Draft'], ['private', 'Private'], ['public', 'Public']] as [value, label]}
						<label class:on={status === value}>
							<input type="radio" name="status" {value} checked={status === value} onchange={() => setStatus(/** @type {any} */ (value))} />
							{label}
						</label>
					{/each}
				</div>
				<p class="status-note">
					{#if status === 'draft'}
						Only you. Choose Private or Public to publish it.
					{:else if status === 'private'}
						Published to the members you've invited.
					{:else if goingPublic}
						Anyone, once you save. Making it private again later can't recall copies of it or its
						photos that were already fetched or cached.
					{:else}
						Anyone.
					{/if}
				</p>
			</fieldset>

			<div class="coords">
				<span class="coords-label">Coords</span>
				<div class="coords-row">
					{#if story.coords}
						<a
							class="coords-current"
							href={`https://www.openstreetmap.org/?mlat=${story.coords.lat}&mlon=${story.coords.lon}#map=16/${story.coords.lat}/${story.coords.lon}`}
							target="_blank"
							rel="noopener noreferrer"
							title="Open in OpenStreetMap"
						>
							📍 {story.coords.lat.toFixed(6)}, {story.coords.lon.toFixed(6)}
						</a>
						<button type="button" class="btn-mini ghost" onclick={clearCoords}>Clear</button>
					{:else}
						<span class="coords-empty">No coords. Story won't appear on /map.</span>
					{/if}
				</div>
				{#if photoPlace}
					<div class="coords-row">
						<button type="button" class="btn-mini" onclick={usePhotoLocation}>📍 Use the photo's location</button>
						<span class="coords-empty">{photoPlace.lat.toFixed(4)}, {photoPlace.lon.toFixed(4)}, from a photo in this story</span>
					</div>
				{:else if namingPlace}
					<p class="coords-feedback hint">Looking up the place name…</p>
				{/if}
				<div class="coords-row">
					<input
						class="coords-input"
						bind:value={coordsInput}
						placeholder="39.618852, -104.9039125 or 85FQJ39W+GC"
						class:invalid={coordsInputError}
					/>
					<button
						type="button"
						class="btn-mini primary"
						disabled={!coordsParsed}
						onclick={applyCoords}
					>
						{story.coords ? 'Replace' : 'Set'}
					</button>
				</div>
				{#if coordsParsed}
					<p class="coords-feedback ok">
						✓ {coordsParsed.source === 'pluscode' ? 'Plus Code →' : 'lat/lon →'}
						{coordsParsed.lat.toFixed(6)}, {coordsParsed.lon.toFixed(6)}
					</p>
				{:else if coordsInputError}
					<p class="coords-feedback err">
						Couldn't parse. Use "lat, lon" (39.62, -104.90) or a full Plus Code like 85FPJ39W+GC.
					</p>
				{:else}
					<p class="coords-feedback hint">
						Right-click a spot on Google Maps to copy either format.
					</p>
				{/if}
			</div>
			<label class="block-label">
				<span>Summary</span>
				<textarea rows="2" bind:value={story.summary} placeholder="A sentence or two." ></textarea>
			</label>
			<label class="block-label tags-label">
				<span>Tags <span class="optional">separated by commas</span></span>
				<input bind:value={tagsInput} oninput={applyTags} placeholder="taiwan, trains, food" autocomplete="off" />
			</label>
		</div>
	</header>

	{#if preview}
		<div class="preview-area">
			<StoryBlocks blocks={story.blocks} />
		</div>
	{:else}
		<div class="grid">
			<aside class="side">
				<section class="panel">
					<h3 class="panel-title">Cover photo</h3>
					<CoverPicker
						imageId={story.coverImageId || ''}
						focus={story.coverFocus ?? { x: 0.5, y: 0.5 }}
						slug={story.slug}
						onChange={(id) => (story.coverImageId = id)}
						onFocusChange={(f) => (story.coverFocus = f)}
					/>
				</section>
			</aside>

			<main class="blocks">
				{#if story.blocks.length === 0}
					<div class="blocks-empty hand">No blocks yet. Add the first one below.</div>
				{/if}
				{#each story.blocks as block, i (block.id)}
					<BlockEditor
						{block}
						slug={story.slug}
						index={i}
						total={story.blocks.length}
						onUpdate={(b) => updateBlock(i, b)}
						onRemove={() => removeBlock(i)}
						onMove={(d) => moveBlock(i, d)}
					/>
				{/each}

				<!-- New blocks land at the end, so the buttons that add them live there too. -->
				<section class="add-row" aria-label="Add a block">
					<h3 class="panel-title">Add a block</h3>
					<div class="add-grid">{@render addButtons()}</div>
				</section>
			</main>
		</div>

		<section class="delete-zone">
			<form method="POST" action="?/delete" onsubmit={(e) => { if (!confirm('Delete this story and its photos? This cannot be undone.')) e.preventDefault(); }}>
				<button type="submit" class="btn danger">Delete story</button>
			</form>
			<p>Removes the story and every photo, recording and clip in it. This can't be undone.</p>
		</section>
	{/if}
</div>

<style>
	.editor { padding-bottom: 4rem; }
	.bar {
		position: sticky;
		top: 0;
		z-index: 20;
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 0.5rem 1rem;
		padding: 0.6rem 0;
		margin-bottom: 1rem;
		background: var(--color-paper);
		border-bottom: 1px solid var(--color-pencil-soft);
	}
	.state {
		font-family: var(--font-mono);
		font-size: 0.72rem;
		letter-spacing: 0.06em;
		color: var(--color-pencil);
	}
	.state.unsaved { color: var(--color-wax); }
	.bar-actions { display: flex; gap: 0.5rem; margin-left: auto; }
	.bar-actions a { text-decoration: none; }

	.editor-head {
		padding-bottom: 1.5rem;
		border-bottom: 1px dashed var(--color-pencil-soft);
		margin-bottom: 1.5rem;
	}
	.back {
		font-family: var(--font-mono);
		font-size: 0.72rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		color: var(--color-pencil);
		text-decoration: none;
	}
	.back:hover { color: var(--color-wax); }

	.title-input {
		display: block;
		width: 100%;
		background: transparent;
		border: none;
		font-size: clamp(2rem, 5vw, 3rem);
		font-family: var(--font-display);
		font-style: italic;
		font-weight: 500;
		color: var(--color-ink);
		line-height: 1.1;
		padding: 0.2rem 0;
		letter-spacing: -0.01em;
	}
	.title-input:focus { outline: none; border-bottom: 2px solid var(--color-wax); }

	.frontmatter {
		display: flex; flex-wrap: wrap; gap: 1rem;
		margin: 0.8rem 0 0.6rem;
	}
	.inline {
		display: inline-flex; align-items: center; gap: 0.5rem;
		font-family: var(--font-mono);
		font-size: 0.7rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--color-pencil);
	}
	.inline input {
		padding: 0.3rem 0.5rem;
		background: var(--color-paper-deep);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-body);
		font-size: 0.9rem;
		color: var(--color-ink);
		letter-spacing: normal;
		text-transform: none;
	}

	.status { border: 0; padding: 0; margin: 0.2rem 0 0.8rem; min-width: 0; }
	.status legend {
		padding: 0; margin-bottom: 0.35rem;
		font-family: var(--font-mono); font-size: 0.7rem;
		letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil);
	}
	.segments {
		display: inline-flex;
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		overflow: hidden;
	}
	.segments label {
		position: relative;
		display: inline-flex; align-items: center;
		min-height: 2.2rem; padding: 0 0.9rem;
		font-family: var(--font-mono); font-size: 0.72rem;
		letter-spacing: 0.12em; text-transform: uppercase;
		color: var(--color-ink); cursor: pointer;
	}
	.segments label + label { border-left: 1px solid var(--color-pencil-soft); }
	.segments label.on { background: var(--color-ink); color: var(--color-paper); }
	.segments label:focus-within { outline: 2px solid var(--color-wax); outline-offset: -2px; }
	/* Still a real radio for the keyboard and screen readers, just not drawn. */
	.segments input { position: absolute; opacity: 0; width: 1px; height: 1px; }
	.status-note { margin: 0.4rem 0 0; font-size: 0.85rem; color: var(--color-pencil); max-width: 40rem; }

	.coords { display: grid; gap: 0.4rem; margin: 0.4rem 0 0.6rem; }
	.coords-label {
		font-family: var(--font-mono); font-size: 0.7rem;
		letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil);
	}
	.coords-row { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
	.coords-current {
		font-family: var(--font-mono); font-size: 0.82rem;
		color: var(--color-ink); text-decoration: none;
		border-bottom: 1px dotted var(--color-pencil-soft);
		padding: 0.1rem 0;
	}
	.coords-current:hover { border-bottom-style: solid; }
	.coords-empty { font-family: var(--font-mono); font-size: 0.78rem; color: var(--color-pencil); font-style: italic; }
	.coords-input {
		flex: 1; min-width: 14rem; padding: 0.35rem 0.55rem;
		background: var(--color-paper-deep);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-mono); font-size: 0.85rem;
		color: var(--color-ink);
	}
	.coords-input.invalid { border-color: var(--color-wax); }
	.coords-feedback {
		margin: 0; font-family: var(--font-mono); font-size: 0.72rem;
		line-height: 1.4;
	}
	.coords-feedback.ok { color: var(--color-postmark); }
	.coords-feedback.err { color: var(--color-wax); }
	.coords-feedback.hint { color: var(--color-pencil-soft); }
	.btn-mini {
		font-family: var(--font-mono); font-size: 0.68rem;
		letter-spacing: 0.12em; text-transform: uppercase;
		padding: 0.35rem 0.65rem; border-radius: 2px;
		border: 1px solid var(--color-ink-soft); background: var(--color-paper);
		color: var(--color-ink); cursor: pointer;
	}
	.btn-mini.primary { background: var(--color-wax); color: var(--color-paper); border-color: var(--color-wax); }
	.btn-mini.ghost { background: transparent; color: var(--color-pencil); }
	.btn-mini[disabled] { opacity: 0.4; cursor: not-allowed; }

	.block-label {
		display: grid; gap: 0.3rem;
		font-family: var(--font-mono);
		font-size: 0.7rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--color-pencil);
	}
	.tags-label { margin-top: 0.8rem; }
	.optional { text-transform: none; letter-spacing: 0; color: var(--color-pencil-soft); }
	.block-label input,
	.block-label textarea {
		padding: 0.55rem 0.7rem;
		background: var(--color-paper-deep);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-body);
		font-size: 0.95rem;
		color: var(--color-ink);
		letter-spacing: normal;
		text-transform: none;
		resize: vertical;
	}

	.btn {
		padding: 0.5rem 0.9rem;
		border-radius: 3px;
		font-family: var(--font-mono);
		font-size: 0.72rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		cursor: pointer;
		border: 1px solid transparent;
	}
	.btn.primary { background: var(--color-ink); color: var(--color-paper); border-color: var(--color-ink); }
	.btn.primary:hover:not(:disabled) { background: var(--color-wax); border-color: var(--color-wax); }
	.btn.primary:disabled { opacity: 0.5; cursor: not-allowed; }
	.btn.ghost { background: transparent; color: var(--color-ink); border-color: var(--color-pencil-soft); }
	.btn.ghost:hover { border-color: var(--color-ink); }
	.btn.danger {
		background: transparent;
		color: var(--color-wax);
		border-color: var(--color-wax);
	}
	.btn.danger:hover { background: var(--color-wax); color: var(--color-paper); }

	.grid {
		display: grid;
		grid-template-columns: 1fr;
		gap: 1.5rem;
	}
	@media (min-width: 900px) { .grid { grid-template-columns: 18rem 1fr; } }

	.side { display: grid; gap: 1rem; align-content: start; align-self: start; }
	/* Beside a long story the cover stays in reach, just under the save bar. */
	@media (min-width: 900px) { .side { position: sticky; top: 4.2rem; } }
	.panel {
		background: var(--color-paper-deep);
		border-radius: 4px;
		padding: 1rem;
	}
	.panel-title {
		font-family: var(--font-mono);
		font-size: 0.7rem;
		letter-spacing: 0.16em;
		text-transform: uppercase;
		color: var(--color-pencil);
		margin: 0 0 0.7rem;
	}
	.add-row {
		margin-top: 1rem;
		padding: 1rem;
		border: 1px dashed var(--color-pencil-soft);
		border-radius: 4px;
	}
	.add-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(8.5rem, 1fr));
		gap: 0.5rem;
	}
	.delete-zone {
		display: flex; flex-wrap: wrap; align-items: center; gap: 0.6rem 1rem;
		margin-top: 3rem; padding-top: 1.2rem;
		border-top: 1px dashed var(--color-wax);
	}
	.delete-zone p { margin: 0; font-size: 0.85rem; color: var(--color-pencil); }
	.add-grid button {
		padding: 0.6rem 0.4rem;
		background: var(--color-paper);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-body);
		font-size: 0.85rem;
		color: var(--color-ink);
		cursor: pointer;
		text-align: left;
	}
	.add-grid button:hover { border-color: var(--color-ink); }

	.blocks-empty {
		text-align: center;
		padding: 3rem 1rem;
		color: var(--color-pencil);
		font-size: 1.4rem;
		background: var(--color-paper-deep);
		border-radius: 4px;
	}

	.preview-area {
		background: var(--color-paper);
		padding: 2rem 1rem 3rem;
		border: 1px dashed var(--color-pencil-soft);
		border-radius: 4px;
	}

	.err-banner {
		padding: 0.6rem 1rem;
		border-radius: 3px;
		margin-bottom: 1rem;
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.06em;
	}
	.err-banner { background: var(--color-wax); color: var(--color-paper); }
</style>
