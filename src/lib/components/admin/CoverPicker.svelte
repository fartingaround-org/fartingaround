<script>
	import { imageUrl } from '#lib/image.js';

	/**
	 * @type {{
	 *   imageId: string,
	 *   focus?: {x: number, y: number},
	 *   slug: string,
	 *   onChange: (id: string) => void,
	 *   onFocusChange?: (focus: {x: number, y: number}) => void
	 * }}
	 */
	let { imageId = '', focus, slug, onChange, onFocusChange } = $props();

	const point = $derived(focus ?? { x: 0.5, y: 0.5 });

	let uploading = $state(false);
	let error = $state('');
	/** @type {HTMLDivElement | undefined} */
	let stage = $state();

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
			onChange(data.imageId);
			// Reset focal point on new image.
			onFocusChange?.({ x: 0.5, y: 0.5 });
		} catch (e) {
			error = e instanceof Error ? e.message : 'Upload failed';
		} finally {
			uploading = false;
			input.value = '';
		}
	}

	/** @param {MouseEvent} ev */
	function setFocus(ev) {
		if (!stage || !onFocusChange) return;
		const rect = stage.getBoundingClientRect();
		const x = (ev.clientX - rect.left) / rect.width;
		const y = (ev.clientY - rect.top) / rect.height;
		onFocusChange({
			x: Math.max(0, Math.min(1, x)),
			y: Math.max(0, Math.min(1, y))
		});
	}

	function reset() {
		onFocusChange?.({ x: 0.5, y: 0.5 });
	}
</script>

<div class="picker">
	{#if imageId}
		<div
			class="stage"
			bind:this={stage}
			onclick={setFocus}
			onkeydown={(e) => { if (e.key === 'Enter') setFocus(/** @type {any} */ (e)); }}
			role="button"
			tabindex="0"
			aria-label="Click to set focal point for cover crop"
		>
			<!-- show the full uncropped image so you can pick any point -->
			<img
				class="full"
				src={imageUrl(imageId, { w: 800, q: 80, fit: 'scale-down' })}
				alt=""
				draggable="false"
			/>
			<!-- the 21:9 crop window that will actually show on the public page -->
			<div class="crop-frame" style:--fx={point.x} style:--fy={point.y}></div>
			<!-- the focal point marker -->
			<div class="marker" style:left={`${point.x * 100}%`} style:top={`${point.y * 100}%`}>
				<svg viewBox="0 0 32 32" aria-hidden="true">
					<circle cx="16" cy="16" r="10" fill="none" stroke="currentColor" stroke-width="2" />
					<circle cx="16" cy="16" r="3" fill="currentColor" />
					<line x1="16" y1="0" x2="16" y2="6" stroke="currentColor" stroke-width="2" />
					<line x1="16" y1="26" x2="16" y2="32" stroke="currentColor" stroke-width="2" />
					<line x1="0" y1="16" x2="6" y2="16" stroke="currentColor" stroke-width="2" />
					<line x1="26" y1="16" x2="32" y2="16" stroke="currentColor" stroke-width="2" />
				</svg>
			</div>
		</div>
		<p class="hint">
			Click anywhere on the photo to set the focal point.
			The dashed box shows the 21:9 cover crop. ({Math.round(point.x * 100)}%, {Math.round(point.y * 100)}%)
		</p>
	{:else}
		<div class="empty"><span class="hand">no cover yet</span></div>
	{/if}

	<div class="controls">
		<label class="upload">
			<input type="file" accept="image/*" onchange={handleFile} disabled={uploading} />
			{uploading ? 'Uploading…' : imageId ? 'Replace cover' : 'Upload cover'}
		</label>
		{#if imageId && onFocusChange}
			<button type="button" class="link" onclick={reset}>Reset focus</button>
		{/if}
		{#if imageId}
			<button type="button" class="link danger" onclick={() => onChange('')}>Remove</button>
		{/if}
	</div>
	{#if error}<p class="err">{error}</p>{/if}
</div>

<style>
	.picker { display: grid; gap: 0.6rem; }

	.stage {
		position: relative;
		background: var(--color-paper-deep);
		border-radius: 3px;
		overflow: hidden;
		cursor: crosshair;
		user-select: none;
		line-height: 0;
	}
	.stage:focus-visible { outline: 2px solid var(--color-wax); outline-offset: 2px; }
	.full { width: 100%; height: auto; display: block; pointer-events: none; }

	/* The 21:9 crop frame, positioned around the focal point.
	   --fx and --fy are 0..1 normalized coords from the parent. The frame is
	   the largest 21:9 rectangle that fits inside the image AND is centered
	   on the focal point (clamped to image edges). We approximate this in CSS
	   by using transform-origin offsets and clamp() to keep it inside. */
	.crop-frame {
		position: absolute;
		inset: 0;
		pointer-events: none;
		--crop-w: 80%;
		--crop-h: calc(var(--crop-w) * 9 / 21);
		/* Frame: a translucent rectangle of size --crop-w × --crop-h
		   whose center is the focal point, clamped so it doesn't leave the box. */
	}
	.crop-frame::before {
		content: '';
		position: absolute;
		width: var(--crop-w);
		aspect-ratio: 21/9;
		left: clamp(0%, calc(var(--fx, 0.5) * 100% - var(--crop-w) / 2), calc(100% - var(--crop-w)));
		top: clamp(
			0%,
			calc(var(--fy, 0.5) * 100% - (var(--crop-w) * 9 / 21) / 2),
			calc(100% - (var(--crop-w) * 9 / 21))
		);
		border: 2px dashed rgba(255, 255, 255, 0.9);
		box-shadow: 0 0 0 9999px rgba(27, 26, 22, 0.45);
		border-radius: 2px;
		pointer-events: none;
	}

	.marker {
		position: absolute;
		width: 32px;
		height: 32px;
		margin: -16px 0 0 -16px;
		color: var(--color-wax);
		pointer-events: none;
		filter: drop-shadow(0 0 2px rgba(0, 0, 0, 0.6));
	}
	.marker svg { width: 100%; height: 100%; }

	.empty {
		aspect-ratio: 16/10;
		background: repeating-linear-gradient(45deg, var(--color-paper-deep) 0 6px, var(--color-paper) 6px 12px);
		display: grid; place-items: center;
		color: var(--color-pencil);
		font-size: 1.2rem;
		border-radius: 3px;
	}

	.hint {
		font-family: var(--font-mono);
		font-size: 0.65rem;
		letter-spacing: 0.04em;
		color: var(--color-pencil);
		margin: 0;
		line-height: 1.4;
	}

	.controls { display: flex; align-items: center; gap: 0.7rem; flex-wrap: wrap; }
	.upload {
		display: inline-block;
		padding: 0.5rem 0.8rem;
		background: var(--color-ink);
		color: var(--color-paper);
		border-radius: 3px;
		font-family: var(--font-mono);
		font-size: 0.7rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		cursor: pointer;
	}
	.upload:hover { background: var(--color-wax); }
	.upload input { display: none; }
	.link {
		background: none;
		border: none;
		color: var(--color-pencil);
		font-family: var(--font-mono);
		font-size: 0.7rem;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		cursor: pointer;
		padding: 0;
	}
	.link:hover { color: var(--color-ink); text-decoration: underline; }
	.link.danger { color: var(--color-wax); }
	.err { font-family: var(--font-hand); color: var(--color-wax); margin: 0; }
</style>
