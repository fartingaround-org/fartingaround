<script>
	import { imageUrl, imageSrcset, r2DerivativeUrl, r2Srcset, r2LargestUrl, r2OriginalUrl } from '#lib/image.js';

	/** @type {{ block: import('#lib/types.js').GalleryBlock }} */
	let { block } = $props();

	const size = $derived(block.size || 'wide');
	const aspect = $derived(block.aspect || '3/2');
	/** @type {import('#lib/types.js').GalleryItem[]} */
	const items = $derived((block.items || []).filter((it) => it && (it.imageId || it.r2)));

	const thumbWidths = [480, 720, 960, 1280];
	const thumbSizes = '(min-width: 720px) 30rem, 80vw';

	// Each gallery item is either a Cloudflare Images photo (imageId) or a
	// full-resolution R2 photo with pre-generated derivatives (r2). These pick
	// the right URL source for each render slot.
	/** @param {import('#lib/types.js').GalleryItem} it */
	const thumbSrc = (it) => (it.r2 ? r2DerivativeUrl(it.r2, 960) : imageUrl(it.imageId, { w: 960, q: 84, aspect }));
	/** @param {import('#lib/types.js').GalleryItem} it */
	const thumbSet = (it) => (it.r2 ? r2Srcset(it.r2) : imageSrcset(it.imageId, thumbWidths, { aspect }));
	/** @param {import('#lib/types.js').GalleryItem} it */
	const lbSrc = (it) => (it.r2 ? r2LargestUrl(it.r2) : imageUrl(it.imageId, { w: 2400, q: 90 }));
	/** @param {import('#lib/types.js').GalleryItem} it */
	const fullResHref = (it) => (it.r2 ? r2OriginalUrl(it.r2) : '');
	/** @param {import('#lib/types.js').GalleryItem} it @param {number} i */
	const itemKey = (it, i) => (it.imageId || it.r2?.base || 'item') + i;

	// ── Lightbox ────────────────────────────────────────────────────────────
	let lightboxIndex = $state(-1); // -1 = closed
	const open = $derived(lightboxIndex >= 0);
	const current = $derived(open ? items[lightboxIndex] : null);

	// Layer state. The base layer is one of photo / starless / annotated; the
	// transparent overlay is an additive toggle that composites labels on top of
	// the photo or starless base (it's redundant over the baked-in annotated one).
	let lbBase = $state(/** @type {'photo'|'starless'|'annotated'} */ ('photo'));
	let showOverlay = $state(false);

	const hasStarless = $derived(!!current?.starlessId);
	const hasOverlay = $derived(!!current?.overlayId);
	const hasAnnotated = $derived(!!current?.annotationId);
	const overlayVisible = $derived(showOverlay && hasOverlay && lbBase !== 'annotated');

	const baseSrc = $derived(
		!current
			? ''
			: lbBase === 'starless'
				? imageUrl(current.starlessId, { w: 2400, q: 90 })
				: lbBase === 'annotated'
					? imageUrl(current.annotationId, { w: 2400, q: 90 })
					: lbSrc(current)
	);

	function resetLayers() {
		lbBase = 'photo';
		showOverlay = false;
	}

	/** @param {'photo'|'starless'|'annotated'} mode */
	function setBase(mode) {
		lbBase = mode;
		if (mode === 'annotated') showOverlay = false; // already has labels baked in
	}

	/** Toggle the labels: the transparent overlay if present, else swap to the
	 *  full annotated image. Bound to the "Labels" button, key `a`, and the
	 *  click-while-zoomed gesture. */
	function toggleLabels() {
		if (hasOverlay) {
			if (lbBase === 'annotated') lbBase = 'photo';
			showOverlay = !showOverlay;
		} else if (hasAnnotated) {
			lbBase = lbBase === 'annotated' ? 'photo' : 'annotated';
		}
	}

	function toggleStarless() {
		if (!hasStarless) return;
		setBase(lbBase === 'starless' ? 'photo' : 'starless');
	}

	// Zoom + pan state for the lightbox image. zoom === 1 means "fit"; > 1 is
	// magnified and the image can be panned. When zoomed, a click on the image
	// toggles between the regular photo and the star map.
	let zoom = $state(1);
	let tx = $state(0);
	let ty = $state(0);
	const MIN_ZOOM = 1;
	const MAX_ZOOM = 4;
	/** @type {HTMLDivElement | undefined} */
	let zoomEl = $state();

	function resetZoom() {
		zoom = 1;
		tx = 0;
		ty = 0;
	}

	/** @param {number} i */
	function openLightbox(i) {
		lightboxIndex = i;
		resetLayers();
		resetZoom();
	}
	function close() {
		lightboxIndex = -1;
		resetLayers();
		resetZoom();
	}
	function next() {
		if (!items.length) return;
		lightboxIndex = (lightboxIndex + 1) % items.length;
		resetLayers();
		resetZoom();
	}
	function prev() {
		if (!items.length) return;
		lightboxIndex = (lightboxIndex - 1 + items.length) % items.length;
		resetLayers();
		resetZoom();
	}

	/** @param {KeyboardEvent} e */
	function onKey(e) {
		if (!open) return;
		if (e.key === 'Escape') close();
		else if (e.key === 'ArrowRight') next();
		else if (e.key === 'ArrowLeft') prev();
		else if (e.key === '0') resetZoom();
		else if (e.key === 'a' || e.key === 'A') toggleLabels();
		else if (e.key === 's' || e.key === 'S') toggleStarless();
	}

	// ── Lightbox zoom + pan ───────────────────────────────────────────────────
	// Keep the panned image roughly within its own frame: with transform-origin
	// at the centre, the most we ever need to translate is half the extra size
	// the scale adds.
	function clampPan() {
		if (!zoomEl) return;
		const r = zoomEl.getBoundingClientRect(); // already includes the scale
		const maxX = Math.max(0, (r.width - r.width / zoom) / 2);
		const maxY = Math.max(0, (r.height - r.height / zoom) / 2);
		tx = Math.min(maxX, Math.max(-maxX, tx));
		ty = Math.min(maxY, Math.max(-maxY, ty));
	}

	/**
	 * Set the zoom level while keeping the point under (clientX, clientY) fixed
	 * on screen, so wheel/pinch zoom feels anchored to the cursor.
	 * @param {number} next @param {number} clientX @param {number} clientY
	 */
	function setZoomAt(next, clientX, clientY) {
		if (!zoomEl) return;
		const z = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, next));
		const r = zoomEl.getBoundingClientRect();
		const cx = clientX - (r.left + r.width / 2); // cursor offset from current centre
		const cy = clientY - (r.top + r.height / 2);
		const u = cx / zoom; // same point in unscaled content coords
		const v = cy / zoom;
		tx = cx + tx - u * z;
		ty = cy + ty - v * z;
		zoom = z;
		if (zoom === 1) {
			tx = 0;
			ty = 0;
		} else {
			clampPan();
		}
	}

	/** Zoom toggle anchored on the stage centre (for the on-screen button). */
	function toggleZoom() {
		if (zoom > 1) {
			resetZoom();
		} else if (zoomEl) {
			const r = zoomEl.getBoundingClientRect();
			setZoomAt(2.5, r.left + r.width / 2, r.top + r.height / 2);
		}
	}

	/** @param {WheelEvent} e */
	function onWheel(e) {
		e.preventDefault();
		setZoomAt(zoom * (e.deltaY < 0 ? 1.2 : 1 / 1.2), e.clientX, e.clientY);
	}

	/** @param {MouseEvent} e */
	function onDblClick(e) {
		// Double-click zooms in to a point; when already zoomed it's a no-op so
		// it doesn't fight with the click-to-toggle gesture below.
		if (zoom === 1) setZoomAt(2.5, e.clientX, e.clientY);
	}

	// Pointer tracking: one pointer = drag-to-pan, two pointers = pinch zoom.
	/** @type {Map<number, {x: number, y: number}>} */
	const lbPointers = new Map();
	let lbDragging = $state(false);
	let lbMoved = false;
	let lbStartX = 0;
	let lbStartY = 0;
	let lbStartTx = 0;
	let lbStartTy = 0;
	let pinchStartDist = 0;
	let pinchStartZoom = 1;

	/** @param {PointerEvent} e */
	function onLbPointerDown(e) {
		zoomEl?.setPointerCapture?.(e.pointerId);
		lbPointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
		if (lbPointers.size === 2) {
			const [a, b] = [...lbPointers.values()];
			pinchStartDist = Math.hypot(a.x - b.x, a.y - b.y);
			pinchStartZoom = zoom;
			lbDragging = false;
		} else {
			lbDragging = true;
			lbMoved = false;
			lbStartX = e.clientX;
			lbStartY = e.clientY;
			lbStartTx = tx;
			lbStartTy = ty;
		}
	}

	/** @param {PointerEvent} e */
	function onLbPointerMove(e) {
		if (lbPointers.has(e.pointerId)) lbPointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
		if (lbPointers.size === 2) {
			const [a, b] = [...lbPointers.values()];
			const dist = Math.hypot(a.x - b.x, a.y - b.y);
			if (pinchStartDist > 0) {
				setZoomAt(pinchStartZoom * (dist / pinchStartDist), (a.x + b.x) / 2, (a.y + b.y) / 2);
			}
			return;
		}
		if (!lbDragging || zoom === 1) return;
		const dx = e.clientX - lbStartX;
		const dy = e.clientY - lbStartY;
		if (Math.abs(dx) > 4 || Math.abs(dy) > 4) lbMoved = true;
		tx = lbStartTx + dx;
		ty = lbStartTy + dy;
		clampPan();
	}

	/** @param {PointerEvent} e */
	function onLbPointerUp(e) {
		lbPointers.delete(e.pointerId);
		if (lbPointers.size < 2) pinchStartDist = 0;
		lbDragging = lbPointers.size > 0 && zoom > 1 ? lbDragging : false;
	}

	/** @param {MouseEvent} e */
	function onLbImageClick(e) {
		// While zoomed, a clean click (not a pan, not part of a double-click)
		// flips the labels on/off.
		if (zoom <= 1 || lbMoved) return;
		if (e.detail > 1) return;
		toggleLabels();
	}

	// ── Drag-to-scroll filmstrip ─────────────────────────────────────────────
	/** @type {HTMLDivElement} */
	let strip;
	let dragging = $state(false);
	let moved = false;
	let startX = 0;
	let startScroll = 0;

	/** @param {PointerEvent} e */
	function onPointerDown(e) {
		if (e.button !== 0) return;
		dragging = true;
		moved = false;
		startX = e.clientX;
		startScroll = strip.scrollLeft;
	}
	/** @param {PointerEvent} e */
	function onPointerMove(e) {
		if (!dragging) return;
		const dx = e.clientX - startX;
		if (Math.abs(dx) > 4) moved = true;
		strip.scrollLeft = startScroll - dx;
	}
	function onPointerUp() {
		dragging = false;
	}

	/**
	 * Swallow the click that ends a drag so we don't open the lightbox.
	 * @param {MouseEvent} e
	 * @param {number} i
	 */
	function onSlideClick(e, i) {
		if (moved) {
			e.preventDefault();
			return;
		}
		openLightbox(i);
	}
</script>

<svelte:window onkeydown={onKey} onpointerup={onPointerUp} />

<figure
	class="gallery"
	class:gallery--wide={size === 'wide'}
	class:gallery--full={size === 'full'}
>
	<div
		class="strip"
		class:dragging
		bind:this={strip}
		onpointerdown={onPointerDown}
		onpointermove={onPointerMove}
		role="list"
	>
		{#each items as item, i (itemKey(item, i))}
			<button
				type="button"
				class="slide"
				class:has-map={!!(item.overlayId || item.annotationId)}
				style:aspect-ratio={aspect}
				onclick={(e) => onSlideClick(e, i)}
				aria-label={item.alt || item.caption || `Open photo ${i + 1}`}
			>
				<img
					class="base"
					src={thumbSrc(item)}
					srcset={thumbSet(item)}
					sizes={thumbSizes}
					alt={item.alt ?? ''}
					loading="lazy"
					decoding="async"
					draggable="false"
				/>
				{#if item.overlayId}
					<!-- Transparent label overlay — composites over the photo on hover. -->
					<img
						class="map"
						src={imageUrl(item.overlayId, { w: 960, q: 84, aspect, format: 'auto' })}
						srcset={imageSrcset(item.overlayId, thumbWidths, { aspect, format: 'auto' })}
						sizes={thumbSizes}
						alt=""
						loading="lazy"
						decoding="async"
						draggable="false"
					/>
					<span class="badge">✦ star map</span>
				{:else if item.annotationId}
					<img
						class="map"
						src={imageUrl(item.annotationId, { w: 960, q: 84, aspect })}
						srcset={imageSrcset(item.annotationId, thumbWidths, { aspect })}
						sizes={thumbSizes}
						alt=""
						loading="lazy"
						decoding="async"
						draggable="false"
					/>
					<span class="badge">✦ star map</span>
				{/if}
				<span class="expand" aria-hidden="true">⤢</span>
				{#if item.caption}<span class="micro">{item.caption}</span>{/if}
			</button>
		{/each}
	</div>

	{#if items.length > 1}
		<div class="rail" aria-hidden="true">
			{items.length} photos · drag to browse · tap to enlarge
		</div>
	{/if}

	{#if block.caption}<figcaption>{block.caption}</figcaption>{/if}
</figure>

{#if open && current}
	<!-- Lightbox. Click on the backdrop closes; Esc / arrows handled on window. -->
	<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
	<div
		class="lightbox"
		role="dialog"
		aria-modal="true"
		aria-label="Photo viewer"
		tabindex="-1"
		onclick={close}
	>
		<button type="button" class="lb-close" onclick={close} aria-label="Close (Esc)">×</button>

		{#if items.length > 1}
			<button
				type="button"
				class="lb-nav lb-prev"
				onclick={(e) => { e.stopPropagation(); prev(); }}
				aria-label="Previous (←)"
			>‹</button>
			<button
				type="button"
				class="lb-nav lb-next"
				onclick={(e) => { e.stopPropagation(); next(); }}
				aria-label="Next (→)"
			>›</button>
		{/if}

		<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
		<div class="lb-stage" onclick={(e) => e.stopPropagation()}>
			<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
			<div
				class="lb-zoom"
				class:zoomed={zoom > 1}
				bind:this={zoomEl}
				style:transform={zoom === 1 ? null : `translate(${tx}px, ${ty}px) scale(${zoom})`}
				style:cursor={zoom > 1 ? (lbDragging ? 'grabbing' : 'grab') : 'zoom-in'}
				onwheel={onWheel}
				ondblclick={onDblClick}
				onclick={onLbImageClick}
				onpointerdown={onLbPointerDown}
				onpointermove={onLbPointerMove}
				onpointerup={onLbPointerUp}
				onpointercancel={onLbPointerUp}
			>
				<img
					class="lb-img"
					src={baseSrc}
					alt={current.alt ?? ''}
					draggable="false"
				/>
				{#if hasOverlay}
					<!-- Transparent label overlay, composited on top of the base. -->
					<img
						class="lb-img lb-map"
						class:visible={overlayVisible}
						src={imageUrl(current.overlayId, { w: 2400, q: 90, format: 'auto' })}
						alt=""
						draggable="false"
					/>
				{/if}
			</div>
		</div>

		<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
		<div class="lb-bar" onclick={(e) => e.stopPropagation()}>
			<span class="lb-caption">
				{current.caption ?? ''}
				{#if items.length > 1}<span class="lb-count">{lightboxIndex + 1} / {items.length}</span>{/if}
			</span>
			<button
				type="button"
				class="lb-toggle"
				class:on={zoom > 1}
				onclick={toggleZoom}
			>⤢ {zoom > 1 ? 'Reset zoom' : 'Zoom'}</button>
			{#if fullResHref(current)}
				<a
					class="lb-toggle"
					href={fullResHref(current)}
					target="_blank"
					rel="noopener noreferrer"
					onclick={(e) => e.stopPropagation()}
				>⬇ Full-res</a>
			{/if}
			{#if hasStarless || hasAnnotated}
				<div class="lb-seg" role="group" aria-label="Image layer">
					<button type="button" class="lb-toggle" class:on={lbBase === 'photo'} onclick={() => setBase('photo')}>Photo</button>
					{#if hasStarless}
						<button type="button" class="lb-toggle" class:on={lbBase === 'starless'} onclick={() => setBase('starless')}>Nebula only</button>
					{/if}
					{#if hasAnnotated}
						<button type="button" class="lb-toggle" class:on={lbBase === 'annotated'} onclick={() => setBase('annotated')}>✦ Star map</button>
					{/if}
				</div>
			{/if}
			{#if hasOverlay}
				<button
					type="button"
					class="lb-toggle"
					class:on={overlayVisible}
					disabled={lbBase === 'annotated'}
					onclick={toggleLabels}
				>✦ {overlayVisible ? 'Hide labels' : 'Labels'}</button>
			{/if}
		</div>
	</div>
{/if}

<style>
	.gallery {
		margin: 2.5rem auto;
		width: 100%;
		max-width: 36rem;
	}
	.gallery--wide { max-width: min(56rem, 92vw); }
	.gallery--full {
		max-width: none;
		width: 100vw;
		margin-left: calc(50% - 50vw);
		margin-right: calc(50% - 50vw);
	}

	.strip {
		display: flex;
		gap: 0.75rem;
		/* Centre the slides when they don't fill the strip (e.g. a single
		   photo, common for a promoted star-map gallery, or a couple of slides
		   in a full-bleed strip). `safe` falls back to start-alignment once the
		   content overflows so the first slide is never scrolled out of reach. */
		justify-content: safe center;
		overflow-x: auto;
		scroll-snap-type: x mandatory;
		scrollbar-width: thin;
		padding-bottom: 0.5rem;
		cursor: grab;
		-webkit-overflow-scrolling: touch;
	}
	.strip.dragging { cursor: grabbing; scroll-snap-type: none; }
	.gallery--full .strip { padding-left: 1.5rem; padding-right: 1.5rem; }

	.slide {
		position: relative;
		flex: 0 0 auto;
		width: min(30rem, 80vw);
		padding: 0;
		border: none;
		border-radius: 2px;
		overflow: hidden;
		scroll-snap-align: center;
		background: var(--color-paper-deep);
		box-shadow: 0 1px 0 rgba(0, 0, 0, 0.04), 0 12px 28px -18px rgba(0, 0, 0, 0.35);
		cursor: pointer;
	}
	.gallery--full .slide { width: min(40rem, 86vw); }

	.slide img {
		display: block;
		width: 100%;
		height: 100%;
		object-fit: cover;
		user-select: none;
	}
	.slide .map {
		position: absolute;
		inset: 0;
		opacity: 0;
		transition: opacity 0.35s ease;
	}
	/* Reveal the star map on hover (desktop). Touch users get it in the lightbox. */
	.slide.has-map:hover .map { opacity: 1; }

	.badge {
		position: absolute;
		top: 0.5rem;
		left: 0.5rem;
		font-family: var(--font-mono);
		font-size: 0.6rem;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: rgba(245, 239, 225, 0.95);
		background: rgba(27, 26, 22, 0.55);
		padding: 0.2rem 0.45rem;
		border-radius: 2px;
		backdrop-filter: blur(2px);
		opacity: 0;
		transition: opacity 0.2s ease;
	}
	.slide.has-map .badge { opacity: 0.9; }
	.slide.has-map:hover .badge { opacity: 0; }

	.expand {
		position: absolute;
		top: 0.5rem;
		right: 0.5rem;
		width: 1.7rem;
		height: 1.7rem;
		display: grid;
		place-items: center;
		font-size: 0.95rem;
		color: rgba(245, 239, 225, 0.95);
		background: rgba(27, 26, 22, 0.5);
		border-radius: 2px;
		/* Persistently visible so the tap-to-enlarge affordance is discoverable
		   on touch (no hover) and at a glance on desktop; brightens on hover. */
		opacity: 0.85;
		transition: opacity 0.2s ease, background 0.2s ease;
	}
	.slide:hover .expand { opacity: 1; background: rgba(27, 26, 22, 0.72); }
	@media (hover: hover) and (prefers-reduced-motion: no-preference) {
		/* On true pointer devices, ease it back a touch when idle so it reads as
		   a hint rather than chrome, then pops on hover. */
		.expand { opacity: 0.6; }
	}

	.micro {
		position: absolute;
		bottom: 0.4rem;
		left: 0.6rem;
		right: 0.6rem;
		text-align: left;
		font-family: var(--font-mono);
		font-size: 0.65rem;
		letter-spacing: 0.06em;
		color: rgba(245, 239, 225, 0.95);
		text-shadow: 0 1px 3px rgba(0, 0, 0, 0.7);
		pointer-events: none;
	}

	.rail {
		font-family: var(--font-mono);
		font-size: 0.62rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--color-pencil);
		margin-top: 0.4rem;
		padding: 0 0.25rem;
	}
	.gallery--full .rail { padding: 0 1.5rem; }

	figcaption {
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.04em;
		color: var(--color-pencil);
		margin-top: 0.7rem;
		padding: 0 0.25rem;
		max-width: 36rem;
	}
	.gallery--full figcaption { padding: 0 1.5rem; }

	/* ── Lightbox ─────────────────────────────────────────────────────────── */
	.lightbox {
		/* The viewer is dark in either theme; its light chips need dark text. */
		--color-ink: #1b1a16;
		position: fixed;
		inset: 0;
		z-index: 100;
		/* Size to the *dynamic* viewport. On mobile (esp. Firefox) 100vh is the
		   large viewport behind the URL bar, so a 100vh lightbox is taller than
		   what's visible and the bottom controls sit below the fold until you
		   scroll. 100dvh tracks the actually-visible area so nothing scrolls. */
		height: 100vh;
		height: 100dvh;
		overscroll-behavior: contain;
		background: rgba(12, 11, 9, 0.94);
		display: grid;
		grid-template-rows: 1fr auto;
		place-items: center;
		padding: 2.5rem 1rem 1rem;
		box-sizing: border-box;
		animation: fade 0.18s ease;
	}
	@keyframes fade { from { opacity: 0; } to { opacity: 1; } }

	.lb-stage {
		position: relative;
		max-width: 100%;
		max-height: 100%;
		/* Let this 1fr grid cell shrink instead of growing to the image's
		   intrinsic height (grid items default to min-height:auto), which would
		   overflow the lightbox and push the controls off-screen. */
		min-width: 0;
		min-height: 0;
		display: grid;
		place-items: center;
	}
	.lb-zoom {
		position: relative;
		display: block;
		line-height: 0;
		transform-origin: center center;
		/* Take over touch gestures so pinch-zoom / drag-pan don't scroll the page. */
		touch-action: none;
		/* Deliberately no `will-change: transform` here. Promoting this to a
		   permanent compositor layer makes the browser rasterise it once at 1×
		   and GPU-upscale the bitmap when zoomed, which looks crisp for a moment
		   then turns pixelated — very visible on a sharp-lined star map. Letting
		   the browser re-rasterise at the displayed scale keeps it sharp. */
	}
	.lb-img {
		display: block;
		max-width: min(96vw, 1600px);
		/* Leave room for the top padding + the bottom control bar. dvh keeps the
		   whole image on screen on mobile without scrolling. */
		max-height: calc(100vh - 7rem);
		max-height: calc(100dvh - 7rem);
		object-fit: contain;
		border-radius: 2px;
		user-select: none;
	}
	.lb-map {
		position: absolute;
		inset: 0;
		margin: auto;
		opacity: 0;
		transition: opacity 0.3s ease;
		pointer-events: none;
	}
	.lb-map.visible { opacity: 1; }

	.lb-close {
		position: absolute;
		top: 0.6rem;
		right: 0.9rem;
		width: 2.4rem;
		height: 2.4rem;
		font-size: 1.7rem;
		line-height: 1;
		background: transparent;
		border: none;
		color: rgba(245, 239, 225, 0.8);
		cursor: pointer;
		z-index: 3;
	}
	.lb-close:hover { color: #fff; }

	.lb-nav {
		position: absolute;
		top: 50%;
		transform: translateY(-50%);
		width: 3rem;
		height: 4.5rem;
		font-size: 2.4rem;
		line-height: 1;
		background: transparent;
		border: none;
		color: rgba(245, 239, 225, 0.7);
		cursor: pointer;
		z-index: 2;
	}
	.lb-nav:hover { color: #fff; }
	.lb-prev { left: 0.3rem; }
	.lb-next { right: 0.3rem; }

	.lb-bar {
		position: relative;
		z-index: 3;
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 1rem;
		flex-wrap: wrap;
		padding: 0.9rem 1rem 0.3rem;
		max-width: 96vw;
	}
	.lb-caption {
		font-family: var(--font-mono);
		font-size: 0.78rem;
		letter-spacing: 0.04em;
		color: rgba(245, 239, 225, 0.85);
		text-align: center;
	}
	.lb-count {
		margin-left: 0.6rem;
		opacity: 0.6;
	}
	.lb-toggle {
		font-family: var(--font-mono);
		font-size: 0.68rem;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: rgba(245, 239, 225, 0.9);
		background: transparent;
		border: 1px solid rgba(245, 239, 225, 0.35);
		border-radius: 2px;
		padding: 0.4rem 0.7rem;
		cursor: pointer;
		text-decoration: none;
		display: inline-flex;
		align-items: center;
	}
	.lb-toggle:hover { border-color: rgba(245, 239, 225, 0.8); }
	.lb-toggle.on {
		background: rgba(245, 239, 225, 0.92);
		color: var(--color-ink);
		border-color: transparent;
	}
	.lb-toggle:disabled { opacity: 0.35; cursor: not-allowed; }
	.lb-toggle:disabled:hover { border-color: rgba(245, 239, 225, 0.35); }

	/* Segmented base-layer control: buttons sit flush in one pill. */
	.lb-seg { display: inline-flex; }
	.lb-seg .lb-toggle {
		border-radius: 0;
		border-right-width: 0;
	}
	.lb-seg .lb-toggle:first-child { border-radius: 2px 0 0 2px; }
	.lb-seg .lb-toggle:last-child { border-radius: 0 2px 2px 0; border-right-width: 1px; }
</style>
