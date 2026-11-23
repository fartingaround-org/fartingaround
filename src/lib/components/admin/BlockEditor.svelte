<script>
	import PhotoSlot from './PhotoSlot.svelte';
	import LocationPicker from './LocationPicker.svelte';
	import DumpUploader from './DumpUploader.svelte';
	import { imageUrl, r2DerivativeUrl } from '#lib/image.js';

	/** Human label for the block-head chip. */
	const KIND_LABELS = /** @type {Record<string, string>} */ ({
		gallery: 'astro gallery (retired)',
		dump: 'photo dump',
		pull: 'pull-quote'
	});

	/**
	 * @type {{
	 *   block: import('#lib/types.js').Block,
	 *   slug: string,
	 *   index: number,
	 *   total: number,
	 *   onUpdate: (b: any) => void,
	 *   onRemove: () => void,
	 *   onMove: (dir: -1 | 1) => void
	 * }}
	 */
	let { block, slug, index, total, onUpdate, onRemove, onMove } = $props();

	function patch(/** @type {Record<string, any>} */ changes) {
		onUpdate({ ...block, ...changes });
	}

	// A photo block's frame used to default to 4/5 whatever the photo, which cropped
	// the sides off landscape shots. When no aspect has been chosen yet, pick the
	// offered frame closest to the photo's own shape.
	const PHOTO_ASPECTS = ['2/3', '4/5', '1/1', '4/3', '3/2', '16/9', '21/9'];
	/** @param {{ w: number, h: number } | undefined} dims */
	function nearestAspect(dims) {
		if (!dims || !dims.w || !dims.h) return undefined;
		const r = Math.log(dims.w / dims.h);
		const dist = (/** @type {string} */ a) => { const [w, h] = a.split('/').map(Number); return Math.abs(Math.log(w / h) - r); };
		return PHOTO_ASPECTS.reduce((best, a) => (dist(a) < dist(best) ? a : best));
	}

	// ── Retired astro gallery ────────────────────────────────────────────────
	// Galleries can no longer be added or edited (specs/drop-astro-and-web-
	// audio-video.md); existing ones still display on the site. The editor
	// shows a read-only card so the block can be seen, moved or removed.
	/** @param {any} item */
	function galleryThumb(item) {
		if (item?.imageId) return imageUrl(item.imageId, { w: 240, h: 240, fit: 'cover', q: 75 });
		if (item?.r2?.base && item.r2.widths?.length) return r2DerivativeUrl(item.r2, Math.min(...item.r2.widths));
		return '';
	}

	// ── Photo dump helpers ───────────────────────────────────────────────────
	/** @returns {import('#lib/types.js').DumpPhoto[]} */
	function dumpPhotos() {
		return /** @type {any} */ (block).photos ?? [];
	}
	/** @param {import('#lib/types.js').DumpPhoto[]} batch */
	function addPhotos(batch) {
		patch({ photos: [...dumpPhotos(), ...batch] });
	}
	/** @param {number} i @param {Record<string, any>} changes */
	function patchPhoto(i, changes) {
		patch({ photos: dumpPhotos().map((p, idx) => (idx === i ? { ...p, ...changes } : p)) });
	}
	/** @param {number} i */
	function removePhoto(i) {
		patch({ photos: dumpPhotos().filter((_, idx) => idx !== i) });
		if (selected === i) selected = -1;
		else if (selected > i) selected--;
	}
	function clearPhotos() {
		if (!confirm(`Remove all ${dumpPhotos().length} photos from this dump? (The files stay on the server.)`)) return;
		patch({ photos: [] });
		selected = -1;
	}
	/** @param {number} from @param {number} to */
	function movePhoto(from, to) {
		if (from === to) return;
		const arr = [...dumpPhotos()];
		const [p] = arr.splice(from, 1);
		arr.splice(to, 0, p);
		patch({ photos: arr });
		selected = to;
	}

	// ── Photo locations (from EXIF) ──────────────────────────────────────────
	// `exif` holds what the file said; `coords` is what the site shows. Applying
	// copies one to the other — deliberately a separate step so a photo dump
	// never publishes where you were unless you chose to.
	/** @param {import('#lib/types.js').DumpPhoto} p */
	const hasExifLocation = (p) => p.exif?.lat !== undefined && p.exif?.lon !== undefined;
	/** @param {number} i */
	function applyLocation(i) {
		const p = dumpPhotos()[i];
		if (!p || !hasExifLocation(p)) return;
		patchPhoto(i, { coords: { lat: /** @type {number} */ (p.exif?.lat), lon: /** @type {number} */ (p.exif?.lon) } });
	}
	/** @param {number} i */
	function clearLocation(i) {
		patchPhoto(i, { coords: undefined });
	}
	function applyAllLocations() {
		patch({
			photos: dumpPhotos().map((p) =>
				hasExifLocation(p) && !p.coords
					? { ...p, coords: { lat: /** @type {number} */ (p.exif?.lat), lon: /** @type {number} */ (p.exif?.lon) } }
					: p
			)
		});
	}
	function clearAllLocations() {
		if (!confirm('Remove the location from every photo in this dump?')) return;
		patch({ photos: dumpPhotos().map((p) => ({ ...p, coords: undefined })) });
	}
	// Manual locations — for the photos EXIF didn't help with. Picking one
	// writes `coords` straight away (that's the published field) and leaves
	// `exif` alone, so what the file said and what you chose stay separate.
	let locating = $state(false);
	/** @param {number} i */
	function setLocation(i, /** @type {{ lat: number, lon: number } | undefined} */ coords) {
		patchPhoto(i, { coords });
	}
	/** The nearest earlier photo that has a location — the usual "same spot" case. */
	function previousLocation(/** @type {number} */ i) {
		const ps = dumpPhotos();
		for (let k = i - 1; k >= 0; k--) if (ps[k].coords) return ps[k].coords;
		return undefined;
	}
	/** Copy the selected photo's location onto every photo that has none. */
	function fillUnlocated(/** @type {number} */ i) {
		const c = dumpPhotos()[i]?.coords;
		if (!c) return;
		const n = dumpPhotos().filter((p) => !p.coords).length;
		if (!n || !confirm(`Give this location to the ${n} photo${n === 1 ? '' : 's'} without one?`)) return;
		patch({ photos: dumpPhotos().map((p) => (p.coords ? p : { ...p, coords: { ...c } })) });
	}
	/**
	 * Somewhere sensible to open the picker: a located photo, else any photo's
	 * (private) EXIF position.
	 * @returns {{ lat: number, lon: number } | undefined}
	 */
	function locationHint(/** @type {number} */ i) {
		const prev = previousLocation(i) ?? dumpPhotos().find((p) => p.coords)?.coords;
		if (prev) return prev;
		const ex = dumpPhotos().find((p) => hasExifLocation(p))?.exif;
		return ex && ex.lat !== undefined && ex.lon !== undefined ? { lat: ex.lat, lon: ex.lon } : undefined;
	}
	/** The previous photo's location, or nothing — for the "same as previous photo" button. */
	function copyPreviousLocation(/** @type {number} */ i) {
		const c = previousLocation(i);
		if (c) setLocation(i, { lat: c.lat, lon: c.lon });
	}
	/** Chronological order by EXIF time; photos without one keep their relative order at the end. */
	function sortByTaken() {
		const withIdx = dumpPhotos().map((p, i) => ({ p, i }));
		withIdx.sort((a, b) => {
			if (a.p.takenAt && b.p.takenAt) return a.p.takenAt < b.p.takenAt ? -1 : a.p.takenAt > b.p.takenAt ? 1 : a.i - b.i;
			if (a.p.takenAt) return -1;
			if (b.p.takenAt) return 1;
			return a.i - b.i;
		});
		patch({ photos: withIdx.map((x) => x.p) });
		selected = -1;
	}
	/** @param {string | undefined} iso */
	function fmtTaken(iso) {
		if (!iso) return '';
		try {
			return new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' });
		} catch {
			return iso;
		}
	}

	// ── Audio captions ───────────────────────────────────────────────────────
	// A short spoken caption per photo. Uploaded here as a file (recorded on
	// the phone or wherever); transcription is optional and only offered when
	// the server has an ElevenLabs key.
	/** @type {boolean | null} null = not asked yet */
	let transcribeEnabled = $state(null);
	let audioBusy = $state('');
	let audioError = $state('');
	$effect(() => {
		if (block.type !== 'dump' || transcribeEnabled !== null) return;
		fetch('/admin/api/transcribe')
			.then((r) => (r.ok ? r.json() : { enabled: false }))
			.then((d) => (transcribeEnabled = !!d.enabled))
			.catch(() => (transcribeEnabled = false));
	});
	/** Read a clip's duration in the browser before uploading (best-effort). */
	function probeDuration(/** @type {File} */ file) {
		return new Promise((/** @type {(ms: number | undefined) => void} */ resolve) => {
			if (typeof Audio === 'undefined') return resolve(undefined);
			const url = URL.createObjectURL(file);
			const a = new Audio();
			const done = (/** @type {number | undefined} */ ms) => { URL.revokeObjectURL(url); resolve(ms); };
			a.preload = 'metadata';
			a.onloadedmetadata = () => done(Number.isFinite(a.duration) ? Math.round(a.duration * 1000) : undefined);
			a.onerror = () => done(undefined);
			a.src = url;
		});
	}
	/** @param {number} i @param {Event} e */
	async function uploadAudio(i, e) {
		const input = /** @type {HTMLInputElement} */ (e.currentTarget);
		const file = input.files?.[0];
		input.value = '';
		if (!file) return;
		audioError = '';
		audioBusy = 'Uploading…';
		try {
			const fd = new FormData();
			fd.append('file', file, file.name);
			const ms = await probeDuration(file);
			if (ms) fd.append('durationMs', String(ms));
			const res = await fetch('/admin/api/upload-audio', { method: 'POST', body: fd });
			const data = await res.json().catch(() => ({}));
			if (!res.ok) throw new Error(data?.message || `Upload failed (${res.status})`);
			patchPhoto(i, { audio: { id: data.id, key: data.key, mimeType: data.mimeType, durationMs: data.durationMs } });
		} catch (err) {
			audioError = err instanceof Error ? err.message : String(err);
		} finally {
			audioBusy = '';
		}
	}
	/** @param {number} i */
	async function transcribeAudio(i) {
		const a = dumpPhotos()[i]?.audio;
		if (!a) return;
		audioError = '';
		audioBusy = 'Transcribing…';
		try {
			const res = await fetch('/admin/api/transcribe', {
				method: 'POST',
				headers: { 'content-type': 'application/json' },
				body: JSON.stringify({ key: a.key })
			});
			const data = await res.json().catch(() => ({}));
			if (!res.ok) throw new Error(data?.message || `Transcription failed (${res.status})`);
			patchPhoto(i, { audio: { ...a, transcript: data.transcript, words: data.words, transcriptModel: data.model } });
		} catch (err) {
			audioError = err instanceof Error ? err.message : String(err);
		} finally {
			audioBusy = '';
		}
	}
	/** @param {number} i */
	function removeAudio(i) {
		if (!confirm('Remove the audio caption from this photo? (The file stays on the server.)')) return;
		patchPhoto(i, { audio: undefined });
	}
	/** @param {number | undefined} ms */
	function fmtMs(ms) {
		if (!ms) return '';
		const s = Math.round(ms / 1000);
		return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
	}

	let selected = $state(-1);
	let dragFrom = $state(-1);
	let dragOver = $state(-1);

	/** @param {DragEvent} e @param {number} i */
	function onThumbDragStart(e, i) {
		dragFrom = i;
		if (e.dataTransfer) {
			e.dataTransfer.effectAllowed = 'move';
			e.dataTransfer.setData('text/plain', String(i));
		}
	}
	/** @param {DragEvent} e @param {number} i */
	function onThumbDragOver(e, i) {
		if (dragFrom < 0) return;
		e.preventDefault();
		dragOver = i;
	}
	/** @param {number} i */
	function onThumbDrop(i) {
		if (dragFrom >= 0) movePhoto(dragFrom, i);
		dragFrom = -1;
		dragOver = -1;
	}
	function onThumbDragEnd() {
		dragFrom = -1;
		dragOver = -1;
	}
</script>

<div class="block">
	<div class="block-head">
		<span class="kind">{KIND_LABELS[block.type] ?? block.type}</span>
		<div class="actions">
			<button type="button" class="iconbtn" disabled={index === 0} onclick={() => onMove(-1)} aria-label="Move up">↑</button>
			<button type="button" class="iconbtn" disabled={index === total - 1} onclick={() => onMove(1)} aria-label="Move down">↓</button>
			<button type="button" class="iconbtn iconbtn--danger" onclick={onRemove} aria-label="Remove">×</button>
		</div>
	</div>

	{#if block.type === 'text'}
		<textarea
			rows="6"
			placeholder="Write…"
			value={block.body}
			oninput={(e) => patch({ body: e.currentTarget.value })}
		></textarea>

	{:else if block.type === 'photo'}
		<div class="photo-edit">
			<PhotoSlot
				imageId={block.imageId}
				{slug}
				aspect={block.aspect || '4/5'}
				onChange={(id, dims, exif) => patch({ imageId: id, exif, ...(block.aspect ? {} : { aspect: nearestAspect(dims) }) })}
			/>
			<div class="fields">
				<label>Caption<input value={block.caption ?? ''} oninput={(e) => patch({ caption: e.currentTarget.value })} /></label>
				<label>Alt text<input value={block.alt ?? ''} oninput={(e) => patch({ alt: e.currentTarget.value })} /></label>
				<label>Size
					<select value={block.size ?? 'inline'} onchange={(e) => patch({ size: e.currentTarget.value })}>
						<option value="inline">inline (text width)</option>
						<option value="wide">wide</option>
						<option value="full">full bleed</option>
					</select>
				</label>
				<label>Aspect
					<select value={block.aspect ?? '4/5'} onchange={(e) => patch({ aspect: e.currentTarget.value })}>
						<option value="2/3">2/3 (tall portrait)</option>
						<option value="4/5">4/5 (portrait)</option>
						<option value="1/1">1/1 (square)</option>
						<option value="4/3">4/3</option>
						<option value="3/2">3/2</option>
						<option value="16/9">16/9</option>
						<option value="21/9">21/9 (cinema)</option>
					</select>
				</label>
			</div>
		</div>

	{:else if block.type === 'diptych'}
		<div class="diptych-edit">
			<div class="pair">
				<PhotoSlot
					imageId={block.left?.imageId || ''}
					{slug}
					label="Left"
					aspect={block.aspect || '4/5'}
					onChange={(id) => patch({ left: { ...block.left, imageId: id } })}
				/>
				<PhotoSlot
					imageId={block.right?.imageId || ''}
					{slug}
					label="Right"
					aspect={block.aspect || '4/5'}
					onChange={(id) => patch({ right: { ...block.right, imageId: id } })}
				/>
			</div>
			<div class="fields">
				<label>Caption<input value={block.caption ?? ''} oninput={(e) => patch({ caption: e.currentTarget.value })} /></label>
				<label>Aspect
					<select value={block.aspect ?? '4/5'} onchange={(e) => patch({ aspect: e.currentTarget.value })}>
						<option value="4/5">4/5</option>
						<option value="1/1">1/1</option>
						<option value="3/2">3/2</option>
					</select>
				</label>
			</div>
		</div>

	{:else if block.type === 'gallery'}
		{@const gItems = block.items ?? []}
		<div class="gallery-retired">
			{#if gItems.length}
				<div class="gr-thumbs">
					{#each gItems.slice(0, 6) as item, gi (gi)}
						{@const thumb = galleryThumb(item)}
						{#if thumb}<img src={thumb} alt={item.alt ?? ''} loading="lazy" decoding="async" />{/if}
					{/each}
					{#if gItems.length > 6}<span class="gr-more">+{gItems.length - 6}</span>{/if}
				</div>
			{/if}
			<p class="g-hint">
				{gItems.length} {gItems.length === 1 ? 'photo' : 'photos'}{#if block.caption} · “{block.caption}”{/if} · view only.
				Astro galleries have been retired: this one still shows on the story, and you can move or remove it, but it can't be edited and new ones can't be added.
			</p>
		</div>

	{:else if block.type === 'dump'}
		<div class="dump-edit">
			<DumpUploader {slug} onAdd={addPhotos} />

			{#if (block.photos ?? []).length}
				{@const geotaggable = block.photos.filter((p) => hasExifLocation(p) && !p.coords).length}
				{@const located = block.photos.filter((p) => p.coords).length}
				{@const timed = block.photos.filter((p) => p.takenAt).length}
				<div class="d-toolbar">
					<span class="d-count">{block.photos.length} {block.photos.length === 1 ? 'photo' : 'photos'}{#if located} · {located} located{/if} · drag to reorder · click to caption</span>
					<div class="d-tools">
						{#if timed > 1}<button type="button" class="d-tool" onclick={sortByTaken}>Sort by time taken</button>{/if}
						{#if geotaggable}<button type="button" class="d-tool" onclick={applyAllLocations}>📍 Use all locations ({geotaggable})</button>{/if}
						{#if located}<button type="button" class="d-tool" onclick={clearAllLocations}>Clear locations</button>{/if}
						<button type="button" class="d-clear" onclick={clearPhotos}>Remove all</button>
					</div>
				</div>
				<div class="d-grid" role="list">
					{#each block.photos as p, i (p.imageId + i)}
						<!-- svelte-ignore a11y_no_static_element_interactions -->
						<div
							class="d-thumb"
							class:selected={selected === i}
							class:drag-over={dragOver === i}
							class:dragging={dragFrom === i}
							role="listitem"
							draggable="true"
							ondragstart={(e) => onThumbDragStart(e, i)}
							ondragover={(e) => onThumbDragOver(e, i)}
							ondrop={(e) => { e.preventDefault(); onThumbDrop(i); }}
							ondragend={onThumbDragEnd}
						>
							<button
								type="button"
								class="d-pick"
								onclick={() => (selected = selected === i ? -1 : i)}
								aria-label={`Photo ${i + 1}${p.caption ? ': ' + p.caption : ''}`}
							>
								<img src={imageUrl(p.imageId, { w: 240, h: 240, fit: 'cover', q: 75 })} alt="" loading="lazy" decoding="async" draggable="false" />
								<span class="d-num">{i + 1}</span>
								{#if p.caption}<span class="d-has-cap" title={p.caption}>¶</span>{/if}
								{#if p.coords}<span class="d-has-loc" title="Location shown on map">📍</span>{/if}
								{#if p.audio}<span class="d-has-audio" title="Has an audio caption">🔊</span>{/if}
							</button>
							<button type="button" class="d-x" onclick={() => removePhoto(i)} aria-label="Remove photo">×</button>
						</div>
					{/each}
				</div>

				{#if selected >= 0 && block.photos[selected]}
					{@const sp = block.photos[selected]}
					<div class="d-selected">
						<img src={imageUrl(sp.imageId, { w: 480, q: 80, fit: 'scale-down' })} alt="" />
						<div class="fields">
							<span class="d-sel-head">Photo {selected + 1} of {block.photos.length}{#if sp.w && sp.h} · {sp.w}×{sp.h}{/if}</span>
							{#if sp.takenAt}<span class="d-meta">Taken {fmtTaken(sp.takenAt)}</span>{/if}
							{#if sp.coords}
								<span class="d-meta d-meta--on">📍 Shown on map: {sp.coords.lat.toFixed(5)}, {sp.coords.lon.toFixed(5)}
									<button type="button" class="d-link" onclick={() => (locating = !locating)}>{locating ? 'done' : 'adjust'}</button>
									<button type="button" class="d-link" onclick={() => { clearLocation(selected); locating = false; }}>remove</button>
									{#if block.photos.some((p) => !p.coords)}<button type="button" class="d-link" onclick={() => fillUnlocated(selected)}>apply to all unlocated</button>{/if}</span>
							{:else if hasExifLocation(sp)}
								<span class="d-meta">Location in EXIF (private): {sp.exif?.lat?.toFixed(5)}, {sp.exif?.lon?.toFixed(5)}
									<button type="button" class="d-link" onclick={() => applyLocation(selected)}>use location</button>
									<button type="button" class="d-link" onclick={() => (locating = !locating)}>{locating ? 'cancel' : 'set another'}</button></span>
							{:else}
								<span class="d-meta">No location in this photo's EXIF.
									<button type="button" class="d-link" onclick={() => (locating = !locating)}>{locating ? 'cancel' : 'set location'}</button>
									{#if previousLocation(selected)}<button type="button" class="d-link" onclick={() => copyPreviousLocation(selected)}>same as previous photo</button>{/if}</span>
							{/if}
							{#if locating}
								{#key selected}
									<LocationPicker value={sp.coords} hint={locationHint(selected)} onChange={(c) => setLocation(selected, c)} />
								{/key}
							{/if}
							<label>Start a section here<input value={sp.section ?? ''} placeholder="Leave empty for none — e.g. Taipei" oninput={(e) => patchPhoto(selected, { section: e.currentTarget.value || undefined })} /></label>
							<label>Caption<input value={sp.caption ?? ''} oninput={(e) => patchPhoto(selected, { caption: e.currentTarget.value })} /></label>
							<label>Alt text<input value={sp.alt ?? ''} oninput={(e) => patchPhoto(selected, { alt: e.currentTarget.value })} /></label>

							<div class="d-audio">
								<span class="d-meta">🔊 Audio caption
									{#if sp.audio}
										{#if sp.audio.durationMs}· {fmtMs(sp.audio.durationMs)}{/if}
										{#if transcribeEnabled && !sp.audio.transcript}
											<button type="button" class="d-link" disabled={!!audioBusy} onclick={() => transcribeAudio(selected)}>transcribe</button>
										{:else if transcribeEnabled}
											<button type="button" class="d-link" disabled={!!audioBusy} onclick={() => transcribeAudio(selected)}>re-transcribe</button>
										{/if}
										<label class="d-link d-file">replace<input type="file" accept="audio/*,.m4a,.mp3,.ogg,.opus,.wav,.webm" onchange={(e) => uploadAudio(selected, e)} /></label>
										<button type="button" class="d-link" onclick={() => removeAudio(selected)}>remove</button>
									{:else}
										— plays before the countdown in slideshow mode.
										<label class="d-link d-file">{audioBusy || 'upload a clip'}<input type="file" accept="audio/*,.m4a,.mp3,.ogg,.opus,.wav,.webm" disabled={!!audioBusy} onchange={(e) => uploadAudio(selected, e)} /></label>
									{/if}
								</span>
								{#if sp.audio}
									{#key sp.audio.id}
										<audio controls preload="metadata" src={`/api/stories/${slug}/audio/${sp.audio.id}`}></audio>
									{/key}
									{#if sp.audio.transcript}
										<p class="d-transcript">
											{sp.audio.transcript}
											{#if sp.audio.words?.length}<span class="d-meta"> · {sp.audio.words.length} timed words</span>{/if}
											{#if sp.audio.transcript !== sp.caption}
												<button type="button" class="d-link" onclick={() => patchPhoto(selected, { caption: sp.audio?.transcript })}>use as caption</button>
											{/if}
										</p>
									{:else if transcribeEnabled === false}
										<span class="d-meta">Set ELEVENLABS_API_KEY on the server to enable transcription (closed-caption style playback).</span>
									{/if}
								{/if}
								{#if audioBusy && sp.audio}<span class="d-meta">{audioBusy}</span>{/if}
								{#if audioError}<span class="d-meta d-error">{audioError}</span>{/if}
							</div>
							<div class="d-sel-actions">
								<button type="button" class="iconbtn" disabled={selected === 0} onclick={() => movePhoto(selected, 0)} title="Move to front">⇤</button>
								<button type="button" class="iconbtn" disabled={selected === 0} onclick={() => movePhoto(selected, selected - 1)} aria-label="Move earlier">←</button>
								<button type="button" class="iconbtn" disabled={selected === block.photos.length - 1} onclick={() => movePhoto(selected, selected + 1)} aria-label="Move later">→</button>
								<button type="button" class="iconbtn" disabled={selected === block.photos.length - 1} onclick={() => movePhoto(selected, block.photos.length - 1)} title="Move to end">⇥</button>
								<button type="button" class="iconbtn" onclick={() => (selected = -1)} aria-label="Done">✓</button>
							</div>
						</div>
					</div>
				{/if}
			{/if}

			<div class="fields fields--row">
				<label>Dump caption<input value={block.caption ?? ''} oninput={(e) => patch({ caption: e.currentTarget.value })} /></label>
				<label>Size
					<select value={block.size ?? 'wide'} onchange={(e) => patch({ size: e.currentTarget.value })}>
						<option value="wide">wide</option>
						<option value="full">full bleed</option>
					</select>
				</label>
				<label>Rows
					<select value={block.rowSize ?? 'm'} onchange={(e) => patch({ rowSize: e.currentTarget.value })}>
						<option value="s">small (more per row)</option>
						<option value="m">medium</option>
						<option value="l">large</option>
					</select>
				</label>
				<label>Map
					<select value={block.map ?? 'none'} onchange={(e) => patch({ map: e.currentTarget.value })}>
						<option value="none">off</option>
						<option value="pins">pins for located photos</option>
						<option value="path">pins + path in time order</option>
					</select>
				</label>
				<label>Slideshow pace
					<select value={String(block.slideshowSeconds ?? 5)} onchange={(e) => patch({ slideshowSeconds: Number(e.currentTarget.value) })}>
						<option value="3">3 s per photo</option>
						<option value="5">5 s per photo</option>
						<option value="8">8 s per photo</option>
						<option value="12">12 s per photo</option>
					</select>
				</label>
			</div>
		</div>

	{:else if block.type === 'pull'}
		<textarea
			rows="3"
			placeholder="A pull-quote…"
			value={block.body}
			oninput={(e) => patch({ body: e.currentTarget.value })}
		></textarea>

	{:else if block.type === 'aside'}
		<input
			placeholder="Optional label (e.g. NOTE)"
			value={block.label ?? ''}
			oninput={(e) => patch({ label: e.currentTarget.value })}
		/>
		<textarea
			rows="3"
			placeholder="A pencil-scribbled aside…"
			value={block.body}
			oninput={(e) => patch({ body: e.currentTarget.value })}
		></textarea>

	{:else if block.type === 'heading'}
		<input
			placeholder="A section title"
			value={block.body ?? ''}
			oninput={(e) => patch({ body: e.currentTarget.value })}
		/>

	{:else if block.type === 'code'}
		<textarea
			class="code-input"
			rows="4"
			spellcheck="false"
			autocapitalize="off"
			autocomplete="off"
			placeholder="A command or snippet, shown exactly as typed…"
			value={block.body ?? ''}
			oninput={(e) => patch({ body: e.currentTarget.value })}
		></textarea>
		<input
			placeholder="Optional note shown under it"
			value={block.caption ?? ''}
			oninput={(e) => patch({ caption: e.currentTarget.value })}
		/>

	{:else if block.type === 'divider'}
		<div class="fields fields--row">
			<label>Place<input value={block.place ?? ''} oninput={(e) => patch({ place: e.currentTarget.value })} placeholder="Lisbon, PT" /></label>
			<label>Date<input value={block.date ?? ''} oninput={(e) => patch({ date: e.currentTarget.value })} placeholder="Apr 2026" /></label>
		</div>
	{/if}
</div>

<style>
	.code-input { font-family: var(--font-mono); font-size: 0.85rem; white-space: pre; overflow-x: auto; }
	.block {
		background: var(--color-paper-deep);
		border-radius: 4px;
		padding: 1rem 1rem 1.2rem;
		margin-bottom: 1rem;
		border: 1px solid transparent;
	}
	.block:focus-within { border-color: var(--color-pencil-soft); }
	.block-head {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-bottom: 0.6rem;
	}
	.kind {
		font-family: var(--font-mono);
		font-size: 0.65rem;
		letter-spacing: 0.18em;
		text-transform: uppercase;
		color: var(--color-pencil);
	}
	.actions { display: flex; gap: 0.3rem; }
	.iconbtn {
		background: transparent;
		border: 1px solid var(--color-pencil-soft);
		color: var(--color-pencil);
		width: 28px;
		height: 28px;
		border-radius: 3px;
		cursor: pointer;
		font-family: var(--font-mono);
	}
	.iconbtn:hover { background: var(--color-paper); color: var(--color-ink); }
	.iconbtn:disabled { opacity: 0.4; cursor: not-allowed; }
	.iconbtn--danger:hover { background: var(--color-wax); color: var(--color-paper); border-color: var(--color-wax); }

	textarea, input, select {
		width: 100%;
		padding: 0.55rem 0.7rem;
		background: var(--color-paper);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-body);
		font-size: 0.98rem;
		color: var(--color-ink);
		box-sizing: border-box;
		resize: vertical;
	}
	textarea { font-size: 1rem; line-height: 1.5; }
	input + textarea { margin-top: 0.5rem; }

	.photo-edit, .diptych-edit { display: grid; gap: 0.8rem; }
	@media (min-width: 720px) {
		.photo-edit { grid-template-columns: 1fr 1fr; align-items: start; }
	}
	.pair { display: grid; grid-template-columns: 1fr 1fr; gap: 0.6rem; }
	.fields { display: grid; gap: 0.5rem; }
	.fields--row { grid-template-columns: 1fr 1fr; }
	.fields label {
		display: grid;
		gap: 0.2rem;
		font-family: var(--font-mono);
		font-size: 0.65rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--color-pencil);
	}

	/* ── Retired astro gallery (read-only) ───────────────────────────────── */
	.gallery-retired { display: grid; gap: 0.6rem; }
	.gr-thumbs { display: flex; flex-wrap: wrap; gap: 0.4rem; align-items: center; }
	.gr-thumbs img { width: 72px; height: 72px; object-fit: cover; border-radius: 3px; display: block; }
	.gr-more { font-family: var(--font-mono); font-size: 0.7rem; color: var(--color-pencil); }

	/* ── Photo dump editor ───────────────────────────────────────────────── */
	.dump-edit { display: grid; gap: 0.8rem; }
	.dump-edit .fields--row { grid-template-columns: repeat(2, 1fr); }
	@media (min-width: 720px) { .dump-edit .fields--row { grid-template-columns: repeat(5, 1fr); } }
	.d-toolbar {
		display: flex;
		justify-content: space-between;
		align-items: center;
		gap: 0.6rem;
		font-family: var(--font-mono);
		font-size: 0.62rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--color-pencil);
	}
	.d-tools { display: flex; gap: 0.4rem; flex-wrap: wrap; justify-content: flex-end; }
	.d-tool, .d-clear {
		background: transparent;
		border: 1px solid var(--color-pencil-soft);
		color: var(--color-pencil);
		border-radius: 3px;
		padding: 0.25rem 0.5rem;
		font: inherit;
		cursor: pointer;
	}
	.d-tool:hover { border-color: var(--color-ink); color: var(--color-ink); }
	.d-clear:hover { background: var(--color-wax); color: var(--color-paper); border-color: var(--color-wax); }
	.d-has-loc {
		position: absolute;
		top: 3px;
		left: 3px;
		font-size: 0.6rem;
		line-height: 1;
		padding: 0.1rem 0.2rem;
		border-radius: 2px;
		background: rgba(27, 26, 22, 0.6);
		pointer-events: none;
	}
	.d-meta {
		font-family: var(--font-mono);
		font-size: 0.66rem;
		letter-spacing: 0.04em;
		color: var(--color-pencil);
		line-height: 1.5;
	}
	.d-meta--on { color: var(--color-ink); }
	.d-link {
		background: none;
		border: none;
		padding: 0 0 0 0.3rem;
		font: inherit;
		color: var(--color-wax);
		text-decoration: underline;
		cursor: pointer;
	}
	.d-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(84px, 1fr));
		gap: 4px;
	}
	.d-thumb {
		position: relative;
		aspect-ratio: 1;
		border-radius: 2px;
		overflow: hidden;
		background: var(--color-paper);
		outline: 2px solid transparent;
		outline-offset: -2px;
		transition: outline-color 0.12s ease, opacity 0.12s ease;
	}
	.d-thumb.selected { outline-color: var(--color-ink); }
	.d-thumb.drag-over { outline-color: var(--color-wax); }
	.d-thumb.dragging { opacity: 0.35; }
	.d-pick {
		display: block;
		width: 100%;
		height: 100%;
		padding: 0;
		border: none;
		background: none;
		cursor: grab;
	}
	.d-pick img { display: block; width: 100%; height: 100%; object-fit: cover; }
	.d-num, .d-has-cap {
		position: absolute;
		bottom: 3px;
		font-family: var(--font-mono);
		font-size: 0.58rem;
		line-height: 1;
		padding: 0.15rem 0.3rem;
		border-radius: 2px;
		color: rgba(245, 239, 225, 0.95);
		background: rgba(27, 26, 22, 0.6);
		pointer-events: none;
	}
	.d-num { left: 3px; }
	.d-has-cap { right: 3px; }
	.d-x {
		position: absolute;
		top: 2px;
		right: 2px;
		width: 20px;
		height: 20px;
		padding: 0;
		border: none;
		border-radius: 2px;
		background: rgba(27, 26, 22, 0.6);
		color: rgba(245, 239, 225, 0.95);
		font-family: var(--font-mono);
		font-size: 0.85rem;
		line-height: 1;
		cursor: pointer;
		opacity: 0;
		transition: opacity 0.12s ease;
	}
	.d-thumb:hover .d-x, .d-thumb.selected .d-x { opacity: 1; }
	.d-x:hover { background: var(--color-wax); }
	.d-selected {
		display: grid;
		grid-template-columns: minmax(0, 14rem) 1fr;
		gap: 0.8rem;
		align-items: start;
		background: var(--color-paper);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 4px;
		padding: 0.7rem;
	}
	@media (max-width: 560px) { .d-selected { grid-template-columns: 1fr; } }
	.d-selected img { display: block; width: 100%; height: auto; border-radius: 2px; }
	.d-sel-head {
		font-family: var(--font-mono);
		font-size: 0.65rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		color: var(--color-pencil);
	}
	.d-sel-actions { display: flex; gap: 0.3rem; margin-top: 0.2rem; }
	.d-has-audio {
		position: absolute;
		top: 3px;
		left: 3px;
		font-size: 0.6rem;
		line-height: 1;
		padding: 0.1rem 0.2rem;
		border-radius: 2px;
		background: rgba(27, 26, 22, 0.6);
		pointer-events: none;
	}
	.d-has-loc + .d-has-audio { left: 1.35rem; }
	.d-audio {
		display: grid;
		gap: 0.4rem;
		padding: 0.5rem 0.6rem;
		border: 1px dashed var(--color-pencil, #999);
		border-radius: 2px;
	}
	.d-audio audio { width: 100%; height: 2rem; }
	.d-file { position: relative; display: inline-block; overflow: hidden; }
	.d-file input { position: absolute; inset: 0; opacity: 0; cursor: pointer; width: 100%; }
	.d-link:disabled { opacity: 0.5; cursor: default; }
	.d-transcript {
		margin: 0;
		font-size: 0.82rem;
		line-height: 1.45;
		color: var(--color-ink);
	}
	.d-error { color: var(--color-wax); }
	.g-hint {
		margin: 0;
		font-family: var(--font-mono);
		font-size: 0.66rem;
		line-height: 1.5;
		color: var(--color-pencil-soft);
	}
</style>
