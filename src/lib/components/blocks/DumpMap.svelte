<script>
	/**
	 * Map under a photo dump: a thumbnail pin per located photo and, in 'path'
	 * mode, a dashed line joining them in the order they were taken. Leaflet is
	 * loaded on demand from esm.sh, same as the /map atlas, so stories without a
	 * map pay nothing for it.
	 */
	import { onMount, onDestroy } from 'svelte';
	import { imageUrl } from '#lib/image.js';

	/**
	 * @type {{
	 *   photos: import('#lib/types.js').DumpPhoto[],
	 *   mode: 'pins' | 'path',
	 *   onOpen: (index: number) => void
	 * }}
	 */
	let { photos, mode, onOpen } = $props();

	/** Located photos with their index in the full list, in time-taken order. */
	const stops = $derived.by(() => {
		/** @type {{ p: import('#lib/types.js').DumpPhoto, index: number, c: { lat: number, lon: number } }[]} */
		const located = [];
		photos.forEach((p, index) => {
			if (p.coords && Number.isFinite(p.coords.lat) && Number.isFinite(p.coords.lon)) located.push({ p, index, c: p.coords });
		});
		located.sort((a, b) => {
			if (a.p.takenAt && b.p.takenAt) return a.p.takenAt < b.p.takenAt ? -1 : a.p.takenAt > b.p.takenAt ? 1 : a.index - b.index;
			if (a.p.takenAt) return -1;
			if (b.p.takenAt) return 1;
			return a.index - b.index;
		});
		return located;
	});

	/** @type {HTMLDivElement} */
	let mapEl;
	/** @type {any} */
	let map;
	/** @type {any} */
	let leaflet;
	/** @type {any} */
	let layer;
	let failed = $state(false);

	onMount(async () => {
		try {
			// @ts-ignore — runtime import, no types
			leaflet = await import('https://esm.sh/leaflet@1.9.4');
			await loadCss('https://esm.sh/leaflet@1.9.4/dist/leaflet.css');
		} catch {
			failed = true;
			return;
		}
		map = leaflet.map(mapEl, { scrollWheelZoom: false, attributionControl: true });
		leaflet
			.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
				maxZoom: 19,
				attribution: '© OpenStreetMap contributors'
			})
			.addTo(map);
		draw();
	});

	// Redraw when the photos or mode change (admin preview edits, mostly).
	$effect(() => {
		void stops;
		void mode;
		if (map) draw();
	});

	function draw() {
		if (!map || !leaflet) return;
		layer?.remove();
		layer = leaflet.layerGroup().addTo(map);
		if (!stops.length) return;

		const latlngs = stops.map(({ c }) => [c.lat, c.lon]);

		if (mode === 'path' && latlngs.length > 1) {
			leaflet
				.polyline(latlngs, { color: '#b3322a', weight: 2.5, opacity: 0.85, dashArray: '6 6', lineJoin: 'round' })
				.addTo(layer);
		}

		stops.forEach(({ p, index, c }, n) => {
			const icon = leaflet.divIcon({
				className: 'dump-pin',
				html:
					`<span class="dump-pin__frame"><img src="${imageUrl(p.imageId, { w: 96, h: 96, fit: 'cover', q: 75 })}" alt=""></span>` +
					(mode === 'path' ? `<span class="dump-pin__n">${n + 1}</span>` : ''),
				iconSize: [44, 44],
				iconAnchor: [22, 22]
			});
			leaflet
				.marker([c.lat, c.lon], { icon, title: p.caption || `Photo ${index + 1}` })
				.on('click', () => onOpen(index))
				.addTo(layer);
		});

		const bounds = leaflet.latLngBounds(latlngs);
		if (latlngs.length === 1) map.setView(latlngs[0], 14);
		else map.fitBounds(bounds, { padding: [36, 36], maxZoom: 16 });
	}

	onDestroy(() => {
		map?.remove();
	});

	function loadCss(/** @type {string} */ href) {
		return new Promise((resolve, reject) => {
			if (document.querySelector(`link[href="${href}"]`)) return resolve(null);
			const l = document.createElement('link');
			l.rel = 'stylesheet';
			l.href = href;
			l.onload = () => resolve(null);
			l.onerror = reject;
			document.head.appendChild(l);
		});
	}
</script>

<div class="dump-map" class:failed>
	<div class="canvas" bind:this={mapEl}></div>
	{#if failed}
		<p class="fallback">Map couldn't load. {stops.length} located {stops.length === 1 ? 'photo' : 'photos'}.</p>
	{/if}
	<div class="legend" aria-hidden="true">
		{stops.length} located {stops.length === 1 ? 'photo' : 'photos'}{#if mode === 'path' && stops.length > 1} · path in time order · tap a pin to open{:else} · tap a pin to open{/if}
	</div>
</div>

<style>
	.dump-map { margin-top: 1rem; }
	.canvas {
		width: 100%;
		height: clamp(16rem, 45vh, 28rem);
		border-radius: 2px;
		background: var(--color-paper-deep);
		overflow: hidden;
		isolation: isolate; /* keep Leaflet's z-indexes from escaping into the page */
	}
	.failed .canvas { display: none; }
	.fallback, .legend {
		font-family: var(--font-mono);
		font-size: 0.62rem;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--color-pencil);
		margin: 0.4rem 0 0;
		padding: 0 0.25rem;
	}

	/* Pins are rendered by Leaflet outside this component's scope. */
	:global(.dump-pin) { background: none; border: none; }
	:global(.dump-pin__frame) {
		display: block;
		width: 44px;
		height: 44px;
		border-radius: 3px;
		overflow: hidden;
		border: 2px solid #f5efe1;
		box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
		background: #ece4d0;
	}
	:global(.dump-pin__frame img) { width: 100%; height: 100%; object-fit: cover; display: block; }
	:global(.dump-pin__n) {
		position: absolute;
		top: -6px;
		right: -6px;
		min-width: 18px;
		height: 18px;
		padding: 0 4px;
		border-radius: 9px;
		background: #b3322a;
		color: #f5efe1;
		font-family: var(--font-mono);
		font-size: 10px;
		line-height: 18px;
		text-align: center;
		box-sizing: border-box;
	}
	:global(.dump-pin:hover .dump-pin__frame) { border-color: #b3322a; }
</style>
