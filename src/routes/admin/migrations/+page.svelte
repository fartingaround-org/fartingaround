<script>
	/** Per-migration state: result blob, running flag, last error. */
	function makeState() {
		return {
			result: /** @type {any} */ (null),
			running: false,
			error: /** @type {string | null} */ (null)
		};
	}

	let video = $state(makeState());
	let audio = $state(makeState());
	let geocode = $state(makeState());

	async function run(
		/** @type {{ result: any, running: boolean, error: string | null }} */ s,
		/** @type {string} */ endpoint,
		/** @type {boolean} */ dry
	) {
		if (!dry) {
			if (!confirm('Apply migration to KV? Each affected story will be rewritten.')) return;
		}
		s.running = true;
		s.error = null;
		try {
			const res = await fetch(`${endpoint}?dry=${dry ? 1 : 0}`, { method: 'POST' });
			if (!res.ok) {
				s.error = `HTTP ${res.status} ${await res.text().catch(() => '')}`;
				return;
			}
			s.result = await res.json();
		} catch (e) {
			s.error = e instanceof Error ? e.message : String(e);
		} finally {
			s.running = false;
		}
	}
</script>

<svelte:head><title>Admin — Migrations</title></svelte:head>

<header class="header">
	<a class="back" href="/admin">← admin</a>
	<h1 class="display">Migrations</h1>
</header>

{#snippet migrationCard(
	/** @type {string} */ title,
	/** @type {string} */ lede,
	/** @type {any} */ state,
	/** @type {string} */ endpoint
)}
	<section class="card">
		<h2>{title}</h2>
		<p class="lede">{lede}</p>
		<div class="actions">
			<button type="button" class="ghost" disabled={state.running} onclick={() => run(state, endpoint, true)}>
				{state.running ? 'Running…' : 'Dry run'}
			</button>
			<button type="button" class="primary" disabled={state.running} onclick={() => run(state, endpoint, false)}>
				Apply
			</button>
		</div>
		{#if state.error}
			<p class="error">{state.error}</p>
		{/if}

		{#if state.result}
			<div class="result">
				<h3>{state.result.dry ? 'Dry-run result' : 'Applied'}</h3>
				<dl class="summary">
					<dt>Total stories</dt><dd>{state.result.summary.totalStories}</dd>
					<dt>{state.result.dry ? 'Would migrate' : 'Migrated'}</dt><dd>{state.result.summary.migrated}</dd>
					<dt>Skipped</dt><dd>{state.result.summary.skipped}</dd>
					<dt>No change</dt><dd>{state.result.summary.noChange}</dd>
				</dl>

				{#if state.result.stories.length}
					<ul class="story-list">
						{#each state.result.stories as s}
							<li class={`row row--${s.action}`}>
								<div class="row-head">
									<a href={`/admin/${s.slug}`}>{s.title || s.slug}</a>
									<span class="tag">{s.action}</span>
								</div>
								{#if s.reason}<p class="reason">{s.reason}</p>{/if}
								{#if s.replaced?.length}
									<ul class="replaced">
										{#each s.replaced as r}
											<li class="mono">
												{#if r.pass}<span class="pass">[{r.pass}]</span>{/if}
												{r.oldBlockId} → {r.newBlockId}
												<span class="muted">({r.r2Key})</span>
											</li>
										{/each}
									</ul>
								{/if}
								{#if s.ambiguous?.length}
									<ul class="ambiguous">
										{#each s.ambiguous as a}
											<li class="mono">⚠ {a.reason}</li>
										{/each}
									</ul>
								{/if}
							</li>
						{/each}
					</ul>
				{:else}
					<p class="empty">No stories needed migration.</p>
				{/if}
			</div>
		{/if}
	</section>
{/snippet}

{@render migrationCard(
	'Video aside → VideoBlock',
	'Rebuild legacy "Video recorded {timestamp}" aside blocks into proper VideoBlocks by looking up r2Key/mimeType/durationMs from the source draft. Idempotent — safe to run more than once.',
	video,
	'/admin/api/migrate-video-blocks'
)}

{@render migrationCard(
	'Audio asides + transcript text blocks → AudioBlock',
	'Two-pass rebuild: (A) finds text blocks whose body matches an audio transcript on the source draft and converts them into AudioBlocks with the transcript folded in; (B) replaces audio-labeled asides with AudioBlocks for recordings without transcripts. Ambiguous matches (transcript matches multiple text blocks) are skipped and reported.',
	audio,
	'/admin/api/migrate-audio-blocks'
)}

<section class="card">
	<h2>Geocode stories with location text</h2>
	<p class="lede">
		The /map page filters by GPS coords, not by the location string.
		Stories that have "Westminster, CO" but no lat/lon are invisible
		there. This pass runs each story's location text through Nominatim
		(paced at 1.1 sec/request) and saves the result back as coords.
		Stories that already have coords are skipped.
	</p>
	<div class="actions">
		<button type="button" class="ghost" disabled={geocode.running} onclick={() => run(geocode, '/admin/api/geocode-stories', true)}>
			{geocode.running ? 'Geocoding…' : 'Dry run'}
		</button>
		<button type="button" class="primary" disabled={geocode.running} onclick={() => run(geocode, '/admin/api/geocode-stories', false)}>
			Apply
		</button>
	</div>
	{#if geocode.error}<p class="error">{geocode.error}</p>{/if}

	{#if geocode.result}
		<div class="result">
			<h3>{geocode.result.dry ? 'Dry-run result' : 'Applied'}</h3>
			<dl class="summary">
				<dt>Total stories</dt><dd>{geocode.result.summary.totalStories}</dd>
				<dt>Candidates (location, no coords)</dt><dd>{geocode.result.summary.candidates}</dd>
				<dt>{geocode.result.dry ? 'Would geocode' : 'Geocoded'}</dt><dd>{geocode.result.summary.geocoded}</dd>
				<dt>No match</dt><dd>{geocode.result.summary.failed}</dd>
				<dt>Already had coords</dt><dd>{geocode.result.summary.noChange}</dd>
			</dl>

			{#if geocode.result.stories.length}
				<ul class="story-list">
					{#each geocode.result.stories as s}
						<li class={`row row--${s.action}`}>
							<div class="row-head">
								<a href={`/admin/${s.slug}`}>{s.title || s.slug}</a>
								<span class="tag">{s.action}</span>
							</div>
							<p class="reason mono">
								"{s.location}"
								{#if s.coords}
									→ {s.coords.lat.toFixed(4)}, {s.coords.lon.toFixed(4)}
									{#if s.displayName}<span class="muted">({s.displayName})</span>{/if}
								{/if}
							</p>
							{#if s.reason}<p class="reason">{s.reason}</p>{/if}
						</li>
					{/each}
				</ul>
			{:else}
				<p class="empty">Nothing to geocode — every story with location text already has coords.</p>
			{/if}
		</div>
	{/if}
</section>

<style>
	.header { margin-bottom: 1.5rem; }
	.back {
		font-family: var(--font-mono); font-size: 0.72rem; letter-spacing: 0.14em;
		text-transform: uppercase; color: var(--color-pencil); text-decoration: none;
	}
	.header h1 { font-size: 2.2rem; margin: 0.4rem 0 0; }

	.card {
		border: 1px solid var(--color-pencil-soft); border-radius: 4px;
		padding: 1.5rem; margin-bottom: 2rem; background: var(--color-paper-deep);
	}
	.card h2 { margin: 0 0 0.5rem; font-family: var(--font-display); font-style: italic; }
	.lede { color: var(--color-ink-soft); line-height: 1.6; margin: 0 0 1rem; max-width: 44rem; }

	.actions { display: flex; gap: 0.5rem; flex-wrap: wrap; }
	button {
		font-family: var(--font-mono); font-size: 0.75rem; letter-spacing: 0.1em;
		text-transform: uppercase; padding: 0.55rem 1rem; border-radius: 2px;
		cursor: pointer; border: 1px solid var(--color-ink-soft); background: var(--color-paper);
	}
	button.primary { background: var(--color-wax); color: var(--color-paper); border-color: var(--color-wax); }
	button.ghost { background: transparent; }
	button[disabled] { opacity: 0.5; cursor: not-allowed; }

	.error { color: var(--color-wax); margin: 1rem 0 0; font-family: var(--font-mono); font-size: 0.85rem; }

	.result h3 { font-family: var(--font-display); font-style: italic; margin: 0 0 0.7rem; }
	.summary {
		display: grid; grid-template-columns: max-content 1fr; gap: 0.3rem 1rem;
		font-family: var(--font-mono); font-size: 0.85rem;
		padding: 0.8rem 1rem; background: var(--color-paper-deep); border-radius: 4px;
		margin: 0 0 1.5rem; max-width: 28rem;
	}
	.summary dt { color: var(--color-pencil); }
	.summary dd { margin: 0; }

	.story-list { list-style: none; padding: 0; display: grid; gap: 0.5rem; }
	.row {
		padding: 0.7rem 1rem; border: 1px solid var(--color-pencil-soft);
		border-radius: 4px; background: var(--color-paper);
	}
	.row--skipped { background: var(--color-paper-deep); opacity: 0.85; }
	.row--migrated, .row--geocoded { border-color: var(--color-postmark); }
	.row--would-migrate, .row--would-geocode { border-color: var(--color-stamp); }
	.row--no-match { border-color: var(--color-wax); }

	.row-head { display: flex; align-items: center; gap: 0.6rem; flex-wrap: wrap; }
	.row-head a { color: inherit; font-weight: 500; }
	.row-head .tag {
		font-family: var(--font-mono); font-size: 0.62rem;
		letter-spacing: 0.14em; text-transform: uppercase;
		padding: 0.1rem 0.45rem; border-radius: 2px;
		background: var(--color-paper-deep); color: var(--color-pencil);
	}
	.row--migrated .tag { background: var(--color-postmark); color: var(--color-paper); }
	.row--would-migrate .tag { background: var(--color-stamp); color: var(--color-ink); }

	.reason { font-size: 0.85rem; color: var(--color-pencil); margin: 0.3rem 0 0; }
	.replaced, .ambiguous {
		list-style: none; padding: 0; margin: 0.5rem 0 0;
		font-size: 0.78rem; display: grid; gap: 0.2rem;
	}
	.ambiguous { color: var(--color-wax); }
	.mono { font-family: var(--font-mono); color: var(--color-ink-soft); }
	.muted { color: var(--color-pencil-soft); }
	.pass {
		display: inline-block; padding: 0 0.3rem; margin-right: 0.3rem;
		font-size: 0.62rem; letter-spacing: 0.12em;
		background: var(--color-paper-deep); color: var(--color-pencil);
		border-radius: 2px;
	}
	.empty { color: var(--color-pencil); }
	.result { margin-top: 1rem; }
</style>
