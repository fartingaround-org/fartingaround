<script>
	let { data, form } = $props();
	let contacts = $derived(data.contacts);
	let copied = $state(false);

	function when(/** @type {string | null} */ iso) {
		if (!iso) return 'never';
		try { return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }); } catch { return iso; }
	}
	async function copy(/** @type {string} */ text) {
		try {
			await navigator.clipboard.writeText(text);
			copied = true;
			setTimeout(() => (copied = false), 2000);
		} catch { /* the field is selectable; copy by hand */ }
	}
	/** @param {SubmitEvent} e @param {string} question */
	function sure(e, question) {
		if (!confirm(question)) e.preventDefault();
	}
	/**
	 * The one-time link appears inside its person's card, which can be far
	 * down the page after the form reloads it: bring it into view and select it.
	 * @param {HTMLElement} node
	 */
	function reveal(node) {
		node.scrollIntoView({ block: 'center' });
		const input = node.querySelector('input');
		input?.focus({ preventScroll: true });
		input?.select();
	}
</script>

<svelte:head><title>Readers — admin</title></svelte:head>

<header class="header">
	<h1 class="display">Contact book</h1>
	<p class="eyebrow">{contacts.length} {contacts.length === 1 ? 'person' : 'people'}</p>
	<p class="intro">
		The people you've let in. Each gets in with a link you send them yourself; a link works once.
		Members can read your private stories and comment. Commenters can only comment on public ones.
	</p>
</header>

{#if form?.error && !form?.id}
	<p class="error" role="alert">{form.error}</p>
{/if}
{#if form?.done}
	<p class="notice" role="status">Contact {form.done}.</p>
{/if}

<details class="add-wrap" open={!contacts.length || (!!form?.error && !form?.id)}>
	<summary class="a-btn primary">+ Add someone</summary>
	<form method="POST" action="?/add" class="add">
	<div class="row">
		<label>Name <input name="displayName" required maxlength="80" autocomplete="off" /></label>
		<label>Access
			<select name="role">
				<option value="commenter">Commenter: public stories only</option>
				<option value="member">Member: private stories too</option>
			</select>
		</label>
	</div>
	<div class="row">
		<label>Note to yourself <input name="note" maxlength="500" placeholder="How you know them, how you reach them" autocomplete="off" /></label>
		<label>Email (optional) <input name="email" type="email" autocomplete="off" /></label>
	</div>
	<button type="submit" class="primary">Add to contact book</button>
	</form>
</details>

{#if !contacts.length}
	<p class="empty">Nobody yet. Add someone, then create their link.</p>
{:else}
	<ul class="list">
		{#each contacts as c (c.id)}
			<li class="card" class:banned={c.status === 'banned'}>
				<div class="card-head">
					<h2>{c.displayName}</h2>
					<span class="badges">
						<span class="badge role-{c.role}">{c.role}</span>
						<span class="badge status-{c.status}">{c.status}</span>
					</span>
				</div>
				{#if c.note}<p class="note">{c.note}</p>{/if}
				<p class="meta">
					{#if c.status === 'active'}
						<span>last seen {when(c.lastSeenAt)}</span>
						<span>· {c.sessions} {c.sessions === 1 ? 'device' : 'devices'} signed in</span>
						<span>· {c.passkeys} {c.passkeys === 1 ? 'passkey' : 'passkeys'}</span>
					{:else if c.status === 'invited'}
						<span>hasn't signed in yet</span>
					{:else}
						<span>banned: signed out everywhere, links revoked</span>
					{/if}
					{#if c.linkExpiresAt}<span>· link outstanding until {when(c.linkExpiresAt)}</span>{/if}
				</p>

				{#if form?.error && form?.id === c.id}
					<p class="error" role="alert">{form.error}</p>
				{/if}

				{#if form?.link?.id === c.id}
					<div class="link-box" role="status" {@attach reveal}>
						<p><strong>Link for {c.displayName}.</strong> Copy it now: it is shown once and can't be retrieved later.</p>
						<div class="link-row">
							<input readonly value={form.link.url} onfocus={(e) => e.currentTarget.select()} aria-label="Sign-in link" />
							<button type="button" onclick={() => copy(form?.link?.url ?? '')}>{copied ? 'Copied' : 'Copy'}</button>
						</div>
						<p class="fine">
							Works once, until {when(form.link.expiresAt)}. Send it over a channel you already use with them.
						</p>
					</div>
				{/if}

				{#if c.status !== 'banned'}
				<div class="actions">
						<form method="POST" action="?/link" class="inline"
							onsubmit={(e) => c.status === 'active' && sure(e, `A new link for ${c.displayName} signs them out of every device and removes their passkeys once they use it. Any earlier link stops working now. Continue?`)}>
							<input type="hidden" name="id" value={c.id} />
							<select name="ttlHours" aria-label="Link expires after">
								<option value="">{c.status === 'invited' ? 'Expires in 7 days' : 'Expires in 24 hours'}</option>
								<option value="1">1 hour</option>
								<option value="24">1 day</option>
								<option value="168">7 days</option>
								<option value="720">30 days</option>
							</select>
							<button type="submit" class="primary">{c.status === 'invited' ? (c.linkExpiresAt ? 'Replace invite link' : 'Create invite link') : 'New sign-in link'}</button>
						</form>
				</div>
				{/if}

				<div class="disclosures">
				<details class="more" name="card-{c.id}" open={c.status === 'banned'}>
					<summary>{c.status === 'banned' ? 'More: unban or remove' : 'More: ban or remove'}</summary>
					<div class="actions">
					{#if c.status !== 'banned'}
						<form method="POST" action="?/ban" class="inline"
							onsubmit={(e) => sure(e, `Ban ${c.displayName}? They are signed out everywhere and their passkeys and links are destroyed.`)}>
							<input type="hidden" name="id" value={c.id} />
							<label class="check"><input type="checkbox" name="hideComments" /> hide their comments</label>
							<button type="submit">Ban</button>
						</form>
					{:else}
						<form method="POST" action="?/unban" class="inline">
							<input type="hidden" name="id" value={c.id} />
							<button type="submit">Unban</button>
						</form>
					{/if}
					<form method="POST" action="?/remove" class="inline"
						onsubmit={(e) => sure(e, `Remove ${c.displayName} from the contact book? This can't be undone.`)}>
						<input type="hidden" name="id" value={c.id} />
						<select name="comments" aria-label="What happens to their comments">
							<option value="placeholder">comments become [deleted]</option>
							<option value="delete">delete their comments</option>
						</select>
						<button type="submit" class="danger">Remove</button>
					</form>
					</div>
					{#if c.status === 'banned'}
						<p class="fine">Unbanning only lifts the block. They will need a new link to get back in.</p>
					{/if}
				</details>

				<details class="edit" name="card-{c.id}">
					<summary>Edit details</summary>
					<form method="POST" action="?/save">
						<input type="hidden" name="id" value={c.id} />
						<div class="row">
							<label>Name <input name="displayName" required maxlength="80" value={c.displayName} autocomplete="off" /></label>
							<label>Access
								<select name="role" value={c.role}>
									<option value="commenter">Commenter: public stories only</option>
									<option value="member">Member: private stories too</option>
								</select>
							</label>
						</div>
						<div class="row">
							<label>Note to yourself <input name="note" maxlength="500" value={c.note} autocomplete="off" /></label>
							<label>Email (optional) <input name="email" type="email" value={c.email ?? ''} autocomplete="off" /></label>
						</div>
						<button type="submit">Save</button>
						<span class="fine">A change of access applies the next time they load a page.</span>
					</form>
				</details>
				</div>
			</li>
		{/each}
	</ul>
{/if}

<style>
	.header { margin-bottom: 1.4rem; }
	.intro { color: var(--color-ink-soft); max-width: 44rem; line-height: 1.55; margin: 0.6rem 0 0; }
	h2 { font-family: var(--font-display); font-style: italic; font-weight: 500; font-size: 1.3rem; margin: 0; }
	.add, .card {
		border: 1px solid var(--color-pencil-soft); background: var(--color-paper-deep);
		border-radius: 4px; padding: 1rem 1.1rem;
	}
	.add { margin-top: 0.8rem; display: grid; gap: 0.8rem; }
	.add-wrap { margin-bottom: 1.6rem; }
	.add-wrap > summary { list-style: none; width: max-content; }
	.add-wrap > summary::-webkit-details-marker { display: none; }
	.add-wrap[open] > summary { background: var(--color-paper); color: var(--color-ink); border-color: var(--color-pencil-soft); }
	.row { display: grid; grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr)); gap: 0.8rem; }
	label {
		display: grid; gap: 0.25rem; font-family: var(--font-mono); font-size: 0.68rem;
		letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil);
	}
	input, select {
		font: inherit; font-family: var(--font-body); font-size: 0.95rem; letter-spacing: 0; text-transform: none;
		color: var(--color-ink); background: var(--color-paper); border: 1px solid var(--color-pencil-soft);
		border-radius: 3px; padding: 0.45rem 0.55rem; min-width: 0;
	}
	button {
		font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.12em; text-transform: uppercase;
		background: var(--color-paper); color: var(--color-ink); border: 1px solid var(--color-pencil-soft);
		border-radius: 3px; padding: 0.5rem 0.8rem; cursor: pointer; justify-self: start;
	}
	button:hover { border-color: var(--color-ink); }
	button.primary { background: var(--color-ink); border-color: var(--color-ink); color: var(--color-paper); }
	button.primary:hover { background: var(--color-wax); border-color: var(--color-wax); }
	button.danger { color: var(--color-wax); }
	.list { list-style: none; padding: 0; margin: 0; display: grid; gap: 0.9rem; }
	.card { display: grid; gap: 0.6rem; }
	.card.banned { opacity: 0.8; border-style: dashed; }
	.card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 1rem; flex-wrap: wrap; }
	.badges { display: flex; gap: 0.4rem; }
	.badge {
		font-family: var(--font-mono); font-size: 0.65rem; letter-spacing: 0.14em; text-transform: uppercase;
		padding: 0.15rem 0.5rem; border-radius: 2px; background: var(--color-paper); color: var(--color-pencil);
	}
	.badge.role-member { background: var(--color-postmark); color: var(--color-paper); }
	.badge.status-banned { background: var(--color-wax); color: var(--color-paper); }
	.badge.status-active { background: var(--color-stamp); color: var(--color-ink); }
	.note { margin: 0; color: var(--color-ink-soft); }
	.meta { margin: 0; display: flex; flex-wrap: wrap; gap: 0.5rem; color: var(--color-pencil); font-family: var(--font-mono); font-size: 0.72rem; }
	.actions { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: center; }
	.inline { display: flex; gap: 0.4rem; align-items: center; }
	.inline select { font-size: 0.8rem; padding: 0.35rem 0.4rem; }
	.check { display: flex; align-items: center; gap: 0.3rem; text-transform: none; letter-spacing: 0; font-family: var(--font-body); font-size: 0.8rem; }
	.link-box { border: 1px solid var(--color-postmark); background: var(--color-paper); border-radius: 4px; padding: 0.8rem 0.9rem; display: grid; gap: 0.5rem; }
	.link-box p { margin: 0; }
	.link-row { display: flex; gap: 0.5rem; }
	.link-row input { flex: 1; font-family: var(--font-mono); font-size: 0.8rem; }
	.fine { color: var(--color-pencil); font-size: 0.82rem; margin: 0; }
	/* Closed, the two sit side by side; the open one takes the row beneath. */
	.disclosures { display: flex; flex-wrap: wrap; gap: 0.3rem 1.6rem; align-items: start; }
	.disclosures details[open] { order: 2; flex: 1 0 100%; }
	.more .actions { margin-top: 0.7rem; }
	.more .fine { margin-top: 0.6rem; }
	.edit summary, .more summary { cursor: pointer; font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil); }
	.edit form { display: grid; gap: 0.8rem; margin-top: 0.8rem; }
	.error { color: var(--color-wax); margin: 0 0 1rem; }
	.notice { color: var(--color-postmark); margin: 0 0 1rem; }
	.empty { padding: 3rem 1rem; text-align: center; color: var(--color-pencil); border: 1px dashed var(--color-pencil-soft); border-radius: 4px; }
</style>
