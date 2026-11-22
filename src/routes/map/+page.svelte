<script>
	import { page } from '$app/state';
	import { onMount, onDestroy } from 'svelte';
	import { imageUrl } from '#lib/image.js';
	import { formatDate } from '#lib/format.js';

	/**
	 * @typedef {{ slug: string, title: string, date: string, location: string, summary: string, coverImageId: string, coords: { lat: number, lon: number }, draft: boolean }} StoryPin
	 * @typedef {{ lat: number, lon: number, count: number, label: string, stories: StoryPin[] }} MapLocation
	 */

	/** @type {{ data: { locations: MapLocation[] } }} */
	let { data } = $props();

	/** How many stories we show inline in a multi-story popup before "show more". */
	const POPUP_MAX = 4;

	/** @type {HTMLDivElement | undefined} */
	let mapEl = $state();
	/** @type {any} */
	let map;
	/** @type {any} */
	let leaflet;

	let visitedCount = $state(0);

	onMount(async () => {
		if (!mapEl) return;
		// Dynamically import Leaflet so the library only loads on this page —
		// no point paying ~140 KB on every story view for a map most visitors
		// won't open. Loaded straight from CDN; Leaflet ships its own CSS so
		// we import that too.
		// @ts-ignore — runtime import, no types
		leaflet = await import('https://esm.sh/leaflet@1.9.4');
		await loadCss('https://esm.sh/leaflet@1.9.4/dist/leaflet.css');

		// Center on the centroid of the locations so the user sees their actual
		// fart-around territory, not the equator. Fallback to a wide view.
		const center = centroid(data.locations) || [20, 0];
		map = leaflet.map(mapEl, { scrollWheelZoom: true, attributionControl: true })
			.setView(center, data.locations.length ? 3 : 2);

		leaflet
			.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
				maxZoom: 19,
				attribution: '© OpenStreetMap contributors'
			})
			.addTo(map);

		// 1) Visited-places layer (subtle, behind stories). Loaded after the
		//    map is up so the page renders fast even when the file is large.
		//    Failing to load — typically because the import script hasn't
		//    been run yet — is non-fatal; we just skip the layer.
		fetch('/data/places-visited.json')
			.then((r) => (r.ok ? r.json() : null))
			.then((places) => {
				if (!places?.places?.length) return;
				visitedCount = places.places.length;
				const layer = leaflet.layerGroup();
				for (const p of places.places) {
					const r = visitRadius(p.totalMinutes);
					leaflet
						.circleMarker([p.lat, p.lon], {
							radius: r,
							color: 'transparent',
							fillColor: '#6e6a5e',
							fillOpacity: 0.35,
							weight: 0,
							interactive: true
						})
						.bindTooltip(visitTooltip(p), { direction: 'top', offset: [0, -r - 2] })
						.addTo(layer);
				}
				layer.addTo(map);
			})
			.catch(() => {
				/* No visited-places file yet — fine, map shows only stories. */
			});

		// 2) Story location layer (prominent, in front). One marker per cluster;
		//    markers with more than one story carry a small count badge. The
		//    zIndexOffset bumps them above any visited-place circles at the spot.
		for (const loc of data.locations) {
			const multi = loc.count > 1;
			const icon = leaflet.divIcon({
				className: 'story-pin' + (multi ? ' story-pin--multi' : ''),
				html: multi
					? `<span class="dot" aria-hidden="true"></span><span class="count">${loc.count}</span>`
					: '<span class="dot" aria-hidden="true"></span>',
				iconSize: [20, 20],
				iconAnchor: [10, 10]
			});
			const title = multi ? `${loc.count} stories${loc.label ? ' · ' + loc.label : ''}` : loc.stories[0].title;
			leaflet
				.marker([loc.lat, loc.lon], { icon, title, zIndexOffset: 1000 })
				.addTo(map)
				.bindPopup(buildPopup(loc), { maxWidth: 300, minWidth: multi ? 240 : 220, closeButton: multi });
		}

		// If we have at least two locations, fit the bounds; one keeps the
		// initial zoom so the map doesn't snap to a single-point bbox.
		if (data.locations.length > 1) {
			const bounds = leaflet.latLngBounds(data.locations.map((loc) => [loc.lat, loc.lon]));
			map.fitBounds(bounds, { padding: [40, 40] });
		}
	});

	/** Visited-place dot radius scales with total time spent (in minutes). */
	function visitRadius(/** @type {number} */ totalMinutes) {
		const hours = totalMinutes / 60;
		// 3px floor for a tiny coffee stop, ~10px cap for a city you lived in.
		return Math.min(10, Math.max(3, 2.5 + Math.log2(1 + hours)));
	}

	function visitTooltip(/** @type {any} */ p) {
		const name = p.name || (p.googleNames?.[0] ?? '');
		const days = Math.round(p.totalMinutes / 60 / 24);
		const hrs = Math.round(p.totalMinutes / 60);
		const time = days >= 1 ? `${days} day${days > 1 ? 's' : ''}` : `${hrs} hr${hrs !== 1 ? 's' : ''}`;
		const range = `${p.firstVisit.slice(0, 7)} – ${p.lastVisit.slice(0, 7)}`;
		const safe = (/** @type {string} */ s) =>
			s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
		return `
			<div class="visit-tip">
				${name ? `<div class="visit-name">${safe(name)}</div>` : ''}
				<div class="visit-stat">${time} across ${p.visits} visit${p.visits !== 1 ? 's' : ''}</div>
				<div class="visit-range">${safe(range)}</div>
			</div>
		`;
	}

	onDestroy(() => {
		if (map) map.remove();
	});

	/** Average lat/lon of locations — good enough for an initial view. */
	function centroid(/** @type {MapLocation[]} */ locs) {
		if (!locs.length) return null;
		let lat = 0, lon = 0;
		for (const l of locs) { lat += l.lat; lon += l.lon; }
		return [lat / locs.length, lon / locs.length];
	}

	const safe = (/** @type {string} */ s) =>
		String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

	/** @param {MapLocation} loc */
	function buildPopup(loc) {
		return loc.count > 1 ? buildListPopup(loc) : buildCard(loc.stories[0]);
	}

	/** A single rich card with cover image — used when only one story sits here. */
	function buildCard(/** @type {StoryPin} */ p) {
		const cover = p.coverImageId ? imageUrl(p.coverImageId, { w: 280, h: 140, fit: 'cover', q: 75 }) : '';
		const dateStr = formatDate(p.date);
		return `
			<a class="pin-card" href="/stories/${safe(p.slug)}">
				${cover ? `<img src="${safe(cover)}" alt="" loading="lazy" />` : ''}
				<div class="pin-body">
					<p class="pin-eyebrow">${safe(dateStr)}${p.location ? ' · ' + safe(p.location) : ''}</p>
					<h3 class="pin-title">${safe(p.title || 'Untitled')}</h3>
					${p.summary ? `<p class="pin-summary">${safe(p.summary)}</p>` : ''}
				</div>
			</a>
		`;
	}

	/**
	 * Several stories at one spot: a compact, scannable list of the most recent
	 * few, with a "show more" row linking to the full logbook when there are
	 * more than we display.
	 * @param {MapLocation} loc
	 */
	function buildListPopup(loc) {
		const shown = loc.stories.slice(0, POPUP_MAX);
		const rows = shown
			.map((p) => {
				const thumb = p.coverImageId
					? `<img class="row-thumb" src="${safe(imageUrl(p.coverImageId, { w: 96, h: 96, fit: 'cover', q: 75 }))}" alt="" loading="lazy" />`
					: '<span class="row-thumb row-thumb--blank"></span>';
				return `
					<a class="pin-row" href="/stories/${safe(p.slug)}">
						${thumb}
						<span class="row-body">
							<span class="row-date">${safe(formatDate(p.date))}</span>
							<span class="row-title">${safe(p.title || 'Untitled')}</span>
						</span>
					</a>`;
			})
			.join('');
		const more =
			loc.count > POPUP_MAX
				? `<a class="pin-row pin-row--more" href="/stories">Show all ${loc.count} stories →</a>`
				: '';
		return `
			<div class="pin-multi">
				<div class="pin-multi-head">
					<span class="pin-multi-title">${loc.label ? safe(loc.label) : `${loc.count} stories here`}</span>
					<span class="pin-multi-count">${loc.count} ${loc.count === 1 ? 'story' : 'stories'}</span>
				</div>
				<div class="pin-rows">${rows}${more}</div>
			</div>
		`;
	}

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

<svelte:head>
	<title>Map — {page.data.site.name}</title>
	<meta name="description" content="An atlas of farting around." />
</svelte:head>

<section class="map-page">
	<header class="map-header">
		<p class="eyebrow">Atlas</p>
		<h1 class="display">Where I've been farting around</h1>
		<p class="lede">
			{data.locations.length} {data.locations.length === 1 ? 'spot' : 'spots'} with a story.
			{#if visitedCount}
				<span class="lede-secondary">
					· {visitedCount.toLocaleString()} other places visited (small grey dots).
				</span>
			{/if}
			Tap a pin to read.
		</p>
	</header>

	{#if data.locations.length === 0}
		<p class="empty">No stories with GPS yet. Tag a draft with a location in the
			capture app and it'll land here once promoted.</p>
	{/if}

	<div class="map-wrap">
		<div bind:this={mapEl} class="map"></div>
	</div>
</section>

<style>
	.map-page { padding: 2rem 1.5rem 4rem; max-width: 76rem; margin: 0 auto; }
	.map-header { text-align: center; max-width: 38rem; margin: 0 auto 2rem; }
	.map-header h1 { font-size: clamp(2rem, 5vw, 3.4rem); margin: 0.4rem 0 0.8rem; }
	.lede { color: var(--color-pencil); font-size: 0.95rem; }
	.empty { text-align: center; color: var(--color-pencil); margin: 2rem 0; }

	.map-wrap {
		border: 1px solid var(--color-pencil-soft);
		border-radius: 4px;
		overflow: hidden;
		box-shadow: 0 1px 0 rgba(0,0,0,0.04), 0 24px 50px -32px rgba(0,0,0,0.45);
	}
	.map {
		width: 100%;
		height: clamp(420px, 70vh, 720px);
		background: var(--color-paper-deep);
		/* A small filter knocks the OSM tiles down to a paper-ish desaturation
		   so the pins read as the focal point instead of fighting the basemap. */
		filter: saturate(0.65) contrast(0.95) brightness(1.02);
	}

	/* Custom pin styling — applied via the divIcon className above. The dot
	   uses the wax color so it matches the rest of the site's accents. */
	:global(.story-pin .dot) {
		display: block; width: 20px; height: 20px; border-radius: 50%;
		box-sizing: border-box; position: relative;
		background: var(--color-wax);
		border: 2px solid var(--color-paper);
		box-shadow: 0 1px 3px rgba(0,0,0,0.4);
	}
	/* An invisible 44px target around the dot, so a thumb can hit it. */
	:global(.story-pin .dot::after) { content: ''; position: absolute; inset: -14px; border-radius: 50%; }
	/* Count badge for spots with more than one story. */
	:global(.story-pin--multi .count) {
		position: absolute;
		top: -9px; left: 13px;
		min-width: 17px; height: 17px;
		padding: 0 3px;
		box-sizing: border-box;
		border-radius: 8px;
		background: var(--color-ink);
		color: var(--color-paper);
		font-family: var(--font-mono);
		font-size: 10px; line-height: 14px;
		text-align: center;
		border: 1.5px solid var(--color-paper);
		box-shadow: 0 1px 2px rgba(0,0,0,0.4);
	}

	/* Popup card. Leaflet renders the popup HTML inside its own container, so
	   these have to be :global to reach it. */
	:global(.leaflet-popup-content) { margin: 0; min-width: 220px; }
	:global(.leaflet-popup-content-wrapper) {
		border-radius: 4px; padding: 0; overflow: hidden;
		background: var(--color-paper);
	}
	:global(.pin-card) {
		display: block; text-decoration: none; color: inherit;
	}
	:global(.pin-card img) {
		width: 100%; height: 140px; object-fit: cover; display: block;
	}
	:global(.pin-body) { padding: 0.7rem 0.9rem 0.85rem; }
	:global(.pin-eyebrow) {
		font-family: var(--font-mono); font-size: 0.68rem;
		letter-spacing: 0.14em; text-transform: uppercase;
		color: var(--color-pencil); margin: 0 0 0.3rem;
	}
	:global(.pin-title) {
		font-family: var(--font-display); font-style: italic;
		font-size: 1.15rem; margin: 0; color: var(--color-ink);
		line-height: 1.25;
	}
	:global(.pin-summary) {
		font-size: 0.85rem; color: var(--color-ink-soft);
		margin: 0.4rem 0 0; line-height: 1.5;
	}

	/* Multi-story popup: header + scannable list of recent stories here. */
	:global(.pin-multi) { padding: 0.2rem 0; }
	:global(.pin-multi-head) {
		display: flex; align-items: baseline; justify-content: space-between; gap: 0.6rem;
		padding: 0.55rem 0.9rem 0.5rem;
		border-bottom: 1px solid var(--color-pencil-soft);
	}
	:global(.pin-multi-title) {
		font-family: var(--font-display); font-style: italic;
		font-size: 1.05rem; color: var(--color-ink); line-height: 1.2;
	}
	:global(.pin-multi-count) {
		font-family: var(--font-mono); font-size: 0.6rem;
		letter-spacing: 0.12em; text-transform: uppercase;
		color: var(--color-pencil); white-space: nowrap;
	}
	:global(.pin-rows) { display: block; max-height: 16rem; overflow-y: auto; }
	:global(.pin-row) {
		display: flex; align-items: center; gap: 0.6rem;
		padding: 0.5rem 0.9rem; text-decoration: none; color: inherit;
		border-bottom: 1px solid var(--color-paper-deep);
	}
	:global(.pin-row:last-child) { border-bottom: none; }
	:global(.pin-row:hover) { background: var(--color-paper-deep); }
	:global(.pin-row .row-thumb) {
		flex: 0 0 auto; width: 40px; height: 40px; border-radius: 3px;
		object-fit: cover; display: block; background: var(--color-paper-deep);
	}
	:global(.pin-row .row-thumb--blank) { background: var(--color-pencil-soft); }
	:global(.pin-row .row-body) { display: flex; flex-direction: column; min-width: 0; }
	:global(.pin-row .row-date) {
		font-family: var(--font-mono); font-size: 0.6rem;
		letter-spacing: 0.1em; text-transform: uppercase; color: var(--color-pencil);
	}
	:global(.pin-row .row-title) {
		font-size: 0.9rem; color: var(--color-ink); line-height: 1.3;
		overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
	}
	:global(.pin-row--more) {
		justify-content: center;
		font-family: var(--font-mono); font-size: 0.7rem;
		letter-spacing: 0.1em; text-transform: uppercase;
		color: var(--color-wax);
	}

	.lede-secondary { color: var(--color-pencil-soft); }

	/* Visited-place tooltip — compact, monospace, mirrors the postal aesthetic. */
	:global(.visit-tip) {
		font-family: var(--font-mono);
		font-size: 0.7rem;
		line-height: 1.5;
		color: var(--color-ink-soft);
	}
	:global(.visit-tip .visit-name) {
		font-family: var(--font-display);
		font-style: italic;
		font-size: 0.92rem;
		color: var(--color-ink);
	}
	:global(.visit-tip .visit-stat) { color: var(--color-pencil); }
	:global(.visit-tip .visit-range) {
		color: var(--color-pencil-soft);
		font-size: 0.62rem;
		letter-spacing: 0.08em;
	}
</style>
