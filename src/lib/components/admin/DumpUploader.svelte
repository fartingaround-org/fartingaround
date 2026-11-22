<script>
	/**
	 * Bulk photo uploader for the photo-dump block.
	 *
	 * Drop (or pick) any number of files. They're sorted by filename — which
	 * for phone cameras is shooting order — then pushed through a small
	 * concurrent queue: decode for dimensions → downscale only if over the
	 * the server's 50 MB single-upload cap → POST to /admin/api/upload. Finished photos
	 * are handed back via `onAdd` in the original order, in chunks, so the
	 * block fills in as the batch runs rather than all at the end.
	 */
	import { tick } from 'svelte';
	import { readPhotoExif } from '#lib/client/photo-exif.js';

	/** @type {{ slug: string, onAdd: (photos: import('#lib/types.js').DumpPhoto[]) => void }} */
	let { slug, onAdd } = $props();

	const CONCURRENCY = 3;
	const MAX_BYTES = 45 * 1024 * 1024; // stay under the server's 50 MB single-upload cap
	const MAX_SIDE = 4096; // longest edge after a forced downscale

	/**
	 * @typedef {{
	 *   id: number,
	 *   file: File,
	 *   status: 'queued'|'working'|'done'|'failed',
	 *   error?: string,
	 *   photo?: import('#lib/types.js').DumpPhoto
	 * }} Job
	 */

	/** @type {Job[]} */
	let jobs = $state([]);
	let dragOver = $state(false);
	let nextId = 0;
	let flushed = 0; // jobs[0..flushed) have been handed to onAdd (or failed)
	let running = 0;

	const total = $derived(jobs.length);
	const done = $derived(jobs.filter((j) => j.status === 'done').length);
	const failed = $derived(jobs.filter((j) => j.status === 'failed'));
	const settled = $derived(done + failed.length);
	const busy = $derived(jobs.some((j) => j.status === 'queued' || j.status === 'working'));
	const pct = $derived(total ? Math.round((settled / total) * 100) : 0);

	const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

	/** @param {FileList | File[] | null | undefined} list */
	function enqueue(list) {
		if (!list) return;
		const files = Array.from(list)
			.filter((f) => f.type.startsWith('image/') || /\.(jpe?g|png|webp|gif|avif|heic|heif)$/i.test(f.name))
			.sort((a, b) => collator.compare(a.name, b.name));
		if (!files.length) return;
		// A fresh batch after a finished one starts the list over so the
		// progress numbers mean something.
		if (!busy) {
			jobs = [];
			flushed = 0;
		}
		jobs = [...jobs, ...files.map((file) => ({ id: nextId++, file, status: /** @type {const} */ ('queued') }))];
		pump();
	}

	function pump() {
		while (running < CONCURRENCY) {
			const job = jobs.find((j) => j.status === 'queued');
			if (!job) break;
			running++;
			job.status = 'working';
			run(job).finally(() => {
				running--;
				flush();
				pump();
			});
		}
	}

	/** Hand completed photos to the parent, strictly in queue order. */
	function flush() {
		/** @type {import('#lib/types.js').DumpPhoto[]} */
		const batch = [];
		while (flushed < jobs.length) {
			const j = jobs[flushed];
			if (j.status === 'queued' || j.status === 'working') break;
			if (j.status === 'done' && j.photo) batch.push(j.photo);
			flushed++;
		}
		if (batch.length) onAdd(batch);
	}

	/** @param {Job} job */
	async function run(job) {
		try {
			// Read GPS + time from the untouched original first; prepare() may
			// re-encode (losing EXIF) and Cloudflare strips GPS on delivery.
			const exif = await readPhotoExif(job.file);
			const { blob, w, h } = await prepare(job.file);
			const fd = new FormData();
			fd.append('file', blob, blob instanceof File ? blob.name : job.file.name);
			fd.append('slug', slug);
			const res = await fetch('/admin/api/upload', { method: 'POST', body: fd });
			if (!res.ok) throw new Error((await res.text()).slice(0, 200) || `HTTP ${res.status}`);
			const data = await res.json();
			/** @type {import('#lib/types.js').DumpPhoto} */
			const photo = { imageId: data.imageId, w, h, caption: '', alt: '' };
			if (exif?.takenAt) photo.takenAt = exif.takenAt;
			// Location stays private in `exif` until the author applies it.
			if (exif) photo.exif = exif;
			job.photo = photo;
			job.status = 'done';
		} catch (e) {
			job.error = e instanceof Error ? e.message : 'Upload failed';
			job.status = 'failed';
		}
	}

	/**
	 * Decode once for dimensions (orientation applied, so portrait phone shots
	 * report portrait). Only re-encode when the file would be rejected by
	 * Cloudflare Images for size — originals are otherwise sent untouched.
	 * @param {File} file
	 * @returns {Promise<{ blob: Blob, w?: number, h?: number }>}
	 */
	async function prepare(file) {
		/** @type {ImageBitmap | null} */
		let bitmap = null;
		try {
			bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
		} catch {
			// Undecodable here (e.g. HEIC in Chrome). Let the server try as-is.
			if (file.size > MAX_BYTES) throw new Error('Too large and this browser can’t resize it. Convert to JPEG first.');
			return { blob: file };
		}
		try {
			const w = bitmap.width;
			const h = bitmap.height;
			if (file.size <= MAX_BYTES) return { blob: file, w, h };

			const scale = Math.min(1, MAX_SIDE / Math.max(w, h));
			const tw = Math.max(1, Math.round(w * scale));
			const th = Math.max(1, Math.round(h * scale));
			const canvas = document.createElement('canvas');
			canvas.width = tw;
			canvas.height = th;
			const ctx = canvas.getContext('2d');
			if (!ctx) throw new Error('Canvas unavailable');
			ctx.imageSmoothingQuality = 'high';
			ctx.drawImage(bitmap, 0, 0, tw, th);
			for (const q of [0.88, 0.8, 0.7]) {
				const out = await new Promise(/** @param {(b: Blob | null) => void} r */ (r) => canvas.toBlob(r, 'image/jpeg', q));
				if (out && out.size <= MAX_BYTES) {
					const name = file.name.replace(/\.[^.]+$/, '') + '.jpg';
					return { blob: new File([out], name, { type: 'image/jpeg' }), w: tw, h: th };
				}
			}
			throw new Error('Still over the upload cap after downscaling.');
		} finally {
			bitmap.close?.();
		}
	}

	function retryFailed() {
		// Only offered once the queue is idle, so enqueue() starts a fresh batch.
		enqueue(failed.map((j) => j.file));
	}

	/** @param {DragEvent} e */
	function onDrop(e) {
		e.preventDefault();
		dragOver = false;
		enqueue(e.dataTransfer?.files);
	}

	/** @param {Event} e */
	async function onPick(e) {
		const input = /** @type {HTMLInputElement} */ (e.currentTarget);
		enqueue(input.files);
		await tick();
		input.value = '';
	}
</script>

<label
	class="drop"
	class:over={dragOver}
	ondragover={(e) => { e.preventDefault(); dragOver = true; }}
	ondragleave={() => (dragOver = false)}
	ondrop={onDrop}
>
	<span class="drop-main">{busy ? `Uploading ${settled} / ${total}…` : 'Drop the whole trip here'}</span>
	<span class="drop-hint">or click to pick · any number of photos · sorted by filename · GPS + time read from EXIF</span>
	<input type="file" accept="image/*" multiple onchange={onPick} />
	{#if total}
		<span class="bar" aria-hidden="true"><span class="bar-fill" style:width="{pct}%"></span></span>
	{/if}
</label>

{#if total && !busy}
	<p class="summary">
		{done} uploaded{#if failed.length} · <b>{failed.length} failed</b>{/if}
	</p>
{/if}

{#if failed.length}
	<div class="failed">
		<ul>
			{#each failed as j (j.id)}
				<li><span class="fname">{j.file.name}</span> — {j.error}</li>
			{/each}
		</ul>
		{#if !busy}
			<button type="button" class="retry" onclick={retryFailed}>Retry failed ({failed.length})</button>
		{/if}
	</div>
{/if}

<style>
	.drop {
		position: relative;
		display: grid;
		place-items: center;
		gap: 0.3rem;
		padding: 1.6rem 1rem 1.4rem;
		text-align: center;
		background: repeating-linear-gradient(45deg, var(--color-paper-deep) 0 6px, var(--color-paper) 6px 12px);
		border: 1px dashed var(--color-pencil-soft);
		border-radius: 3px;
		cursor: pointer;
		overflow: hidden;
	}
	.drop:hover, .drop.over { border-color: var(--color-ink); border-style: solid; }
	.drop-main {
		font-family: var(--font-mono);
		font-size: 0.8rem;
		letter-spacing: 0.08em;
		color: var(--color-ink);
	}
	.drop-hint {
		font-family: var(--font-mono);
		font-size: 0.62rem;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: var(--color-pencil);
	}
	.drop input { display: none; }
	.bar {
		position: absolute;
		left: 0; right: 0; bottom: 0;
		height: 3px;
		background: rgba(0, 0, 0, 0.08);
	}
	.bar-fill {
		display: block;
		height: 100%;
		background: var(--color-wax);
		transition: width 0.2s ease;
	}
	.summary, .failed {
		margin: 0.5rem 0 0;
		font-family: var(--font-mono);
		font-size: 0.68rem;
		letter-spacing: 0.06em;
		color: var(--color-pencil);
	}
	.failed ul { margin: 0 0 0.4rem; padding-left: 1.1rem; }
	.failed li { color: var(--color-wax); margin-bottom: 0.15rem; }
	.fname { color: var(--color-ink); }
	.retry {
		padding: 0.35rem 0.7rem;
		background: var(--color-paper);
		border: 1px solid var(--color-pencil-soft);
		border-radius: 3px;
		font-family: var(--font-mono);
		font-size: 0.62rem;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		cursor: pointer;
	}
	.retry:hover { border-color: var(--color-ink); }
</style>
