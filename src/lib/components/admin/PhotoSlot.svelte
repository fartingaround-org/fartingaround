<script>
	import { readPhotoExif } from '#lib/client/photo-exif.js';
	import { imageUrl } from '#lib/image.js';

	/**
	 * @type {{
	 *   imageId: string,
	 *   slug: string,
	 *   label?: string,
	 *   aspect?: string,
	 *   onChange: (id: string, dims?: { w: number, h: number }, exif?: import('#lib/types.js').DumpPhotoExif) => void
	 * }}
	 */
	let { imageId = '', slug, label = 'Photo', aspect = '4/5', onChange } = $props();

	let uploading = $state(false);
	let error = $state('');

	async function handleFile(/** @type {Event} */ ev) {
		const input = /** @type {HTMLInputElement} */ (ev.target);
		const file = input.files?.[0];
		if (!file) return;
		uploading = true;
		error = '';
		try {
			const fd = new FormData();
			fd.append('file', file);
			fd.append('slug', slug);
			const res = await fetch('/admin/api/upload', { method: 'POST', body: fd });
			if (!res.ok) throw new Error(await res.text());
			const data = await res.json();
			// Pixel size of the file, so a new photo block can default to the photo's own
			// shape. Best effort: without it the block keeps its usual default.
			const bmp = await createImageBitmap(file).catch(() => null);
			// What the file says about where and when (kept private on the block).
			const exif = await readPhotoExif(file);
			onChange(data.imageId, bmp ? { w: bmp.width, h: bmp.height } : undefined, exif ?? undefined);
			bmp?.close();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Upload failed';
		} finally {
			uploading = false;
			input.value = '';
		}
	}

	async function handleDrop(/** @type {DragEvent} */ ev) {
		ev.preventDefault();
		const file = ev.dataTransfer?.files?.[0];
		if (!file) return;
		const fakeEvent = { target: { files: [file], value: '' } };
		await handleFile(/** @type {any} */ (fakeEvent));
	}
</script>

<label
	class="slot"
	class:slot--empty={!imageId}
	style:aspect-ratio={aspect}
	ondragover={(e) => e.preventDefault()}
	ondrop={handleDrop}
>
	{#if imageId}
		<img src={imageUrl(imageId, { w: 400, q: 80, aspect })} alt="" />
		<span class="overlay">
			<span class="overlay-text">{uploading ? 'Uploading…' : 'Click to replace'}</span>
		</span>
	{:else}
		<span class="empty-label">{uploading ? 'Uploading…' : label}</span>
		<span class="hint">drop or click</span>
	{/if}
	<input type="file" accept="image/*" onchange={handleFile} disabled={uploading} />
</label>
{#if error}<p class="err">{error}</p>{/if}

<style>
	.slot {
		position: relative;
		display: block;
		overflow: hidden;
		background: var(--color-paper-deep);
		border-radius: 3px;
		cursor: pointer;
	}
	.slot--empty {
		background: repeating-linear-gradient(45deg, var(--color-paper-deep) 0 6px, var(--color-paper) 6px 12px);
		display: grid;
		place-items: center;
		align-content: center;
		text-align: center;
		gap: 0.4rem;
		color: var(--color-pencil);
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.06em;
	}
	.empty-label { display: block; font-family: var(--font-mono); font-size: 0.78rem; letter-spacing: 0.06em; }
	.hint { font-size: 0.65rem; opacity: 0.8; letter-spacing: 0.1em; text-transform: uppercase; }
	.slot img { width: 100%; height: 100%; object-fit: cover; display: block; }
	.overlay {
		position: absolute; inset: 0;
		background: rgba(27, 26, 22, 0.55);
		color: var(--color-paper);
		display: grid; place-items: center;
		opacity: 0;
		transition: opacity 0.2s ease;
	}
	.slot:hover .overlay { opacity: 1; }
	.overlay-text {
		font-family: var(--font-mono); font-size: 0.7rem;
		letter-spacing: 0.14em; text-transform: uppercase;
	}
	input { display: none; }
	.err { font-family: var(--font-hand); color: var(--color-wax); margin: 0.4rem 0 0; }
</style>
