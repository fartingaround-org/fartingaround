<script>
	/**
	 * Pick a lat/lon by hand: click the map, drag the pin, or paste
	 * "lat, lon" / a Google Maps or OpenStreetMap link into the field. Leaflet
	 * loads on demand, same as the public DumpMap.
	 */
	import { onMount, onDestroy } from 'svelte';

	/**
	 * @type {{
	 *   value: { lat: number, lon: number } | undefined,
	 *   hint?: { lat: number, lon: number },   // where to centre when there's no value yet
	 *   onChange: (coords: { lat: number, lon: number } | undefined) => void
	 * }}
	 */
	let { value, hint, onChange } = $props();

	/** @type {HTMLDivElement | undefined} */
	let mapEl = $state();
	/** @type {any} */ let leaflet;
	/** @type {any} */ let map;
	/** @type {any} */ let marker;
	let failed = $state(false);
	// Seeded once from the initial value on purpose: the picker owns the text
	// field afterwards; a new `value` re-mounts the component (parent uses {#key}).
	// svelte-ignore state_referenced_locally
	let text = $state(value ? `${value.lat.toFixed(5)}, ${value.lon.toFixed(5)}` : '');
	let bad = $state(false);

	onMount(async () => {
		try {
			// @ts-ignore — runtime import, no types
			leaflet = await import('https://esm.sh/leaflet@1.9.4');
			await loadCss('https://esm.sh/leaflet@1.9.4/dist/leaflet.css');
		} catch {
			failed = true;
			return;
		}
		const start = value ?? hint ?? { lat: 44.9778, lon: -93.265 };
		if (!mapEl) return;
		map = leaflet.map(mapEl, { attributionControl: true }).setView([start.lat, start.lon], value ? 14 : hint ? 11 : 3);
		leaflet.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap contributors' }).addTo(map);
		map.on('click', (/** @type {any} */ e) => set({ lat: e.latlng.lat, lon: e.latlng.lng }));
		if (value) place(value);
	});

	// Follow outside changes (switching photos, "same as previous").
	$effect(() => {
		const v = value;
		text = v ? `${v.lat.toFixed(5)}, ${v.lon.toFixed(5)}` : '';
		bad = false;
		if (!map) return;
		if (v) { place(v); map.panTo([v.lat, v.lon]); }
		else { marker?.remove(); marker = null; }
	});

	/** @param {{ lat: number, lon: number }} c */
	function place(c) {
		if (!marker) {
			marker = leaflet.marker([c.lat, c.lon], { draggable: true }).addTo(map);
			marker.on('dragend', () => { const ll = marker.getLatLng(); set({ lat: ll.lat, lon: ll.lng }); });
		} else marker.setLatLng([c.lat, c.lon]);
	}
	/** @param {{ lat: number, lon: number }} c */
	function set(c) {
		const r = { lat: +c.lat.toFixed(6), lon: +c.lon.toFixed(6) };
		onChange(r);
	}

	/** "lat, lon", "lat lon", or a maps link with @lat,lon / ?q=lat,lon / #map=z/lat/lon / mlat=&mlon=. */
	function parse(/** @type {string} */ s) {
		s = s.trim();
		const pats = [
			/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/,                 // google: .../@44.97,-93.26,15z
			/[?&](?:q|ll|query)=(-?\d+(?:\.\d+)?)(?:,|%2C)(-?\d+(?:\.\d+)?)/i, // google: ?q=44.97,-93.26
			/#map=\d+\/(-?\d+(?:\.\d+)?)\/(-?\d+(?:\.\d+)?)/,       // osm: #map=15/44.97/-93.26
			/mlat=(-?\d+(?:\.\d+)?).*?mlon=(-?\d+(?:\.\d+)?)/,      // osm: ?mlat=..&mlon=..
			/^(-?\d+(?:\.\d+)?)\s*[, ]\s*(-?\d+(?:\.\d+)?)$/        // plain
		];
		for (const re of pats) {
			const m = s.match(re);
			if (m) {
				const lat = Number(m[1]), lon = Number(m[2]);
				if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) return { lat, lon };
			}
		}
		return null;
	}
	function commitText() {
		if (!text.trim()) { onChange(undefined); bad = false; return; }
		const c = parse(text);
		if (!c) { bad = true; return; }
		bad = false;
		set(c);
		map?.setView([c.lat, c.lon], Math.max(map.getZoom(), 13));
	}

	onDestroy(() => map?.remove());

	function loadCss(/** @type {string} */ href) {
		return new Promise((resolve, reject) => {
			if (document.querySelector(`link[href="${href}"]`)) return resolve(null);
			const l = document.createElement('link');
			l.rel = 'stylesheet'; l.href = href;
			l.onload = () => resolve(null); l.onerror = reject;
			document.head.appendChild(l);
		});
	}
</script>

<div class="picker">
	<label class="field">
		<span>Lat, lon — or paste a Google Maps / OSM link</span>
		<input
			value={text}
			class:bad
			placeholder="44.97780, -93.26500"
			oninput={(e) => (text = e.currentTarget.value)}
			onblur={commitText}
			onkeydown={(e) => { if (e.key === 'Enter') { e.preventDefault(); commitText(); } }}
		/>
	</label>
	{#if failed}
		<p class="note">Map couldn't load — the field above still works.</p>
	{:else}
		<div class="map" bind:this={mapEl}></div>
		<p class="note">Click the map to place the pin; drag to adjust.</p>
	{/if}
</div>

<style>
	.picker { display: grid; gap: 0.4rem; }
	.field { display: grid; gap: 0.2rem; }
	.field span { font-family: var(--font-mono); font-size: 0.66rem; letter-spacing: 0.04em; color: var(--color-pencil); }
	.field input { font: inherit; padding: 0.35rem 0.5rem; border: 1px solid var(--color-pencil-soft); background: var(--color-paper); color: var(--color-ink); }
	.field input.bad { border-color: var(--color-wax); }
	.map { height: 220px; border: 1px solid var(--color-pencil-soft); background: var(--color-paper-deep); }
	.note { margin: 0; font-family: var(--font-mono); font-size: 0.62rem; color: var(--color-pencil); }
</style>
