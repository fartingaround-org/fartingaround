<script>
	import { commentHtml as html, commentDate as when } from '#lib/comment-html.js';
	import { imageUrl } from '#lib/image.js';
	import { photoFocus } from '#lib/photo-focus.svelte.js';

	/**
	 * @type {{
	 *   comments: import('#lib/server/comments.js').PublicComment[],
	 *   commenting: { enabled: boolean, as: string | null },
	 *   form?: { commentError?: string, draft?: string, parentId?: string, id?: string, photoId?: string } | null,
	 *   photos?: Map<string, { caption?: string, alt?: string }>
	 * }}
	 *   photos: the story's dump photos by image id, for the caption beside a photo's comments.
	 */
	let { comments, commenting, form = null, photos = new Map() } = $props();

	// Comments about the same photo are gathered under that photo, shown once,
	// where the first of them was posted. Everything else stays in order.
	/** @typedef {import('#lib/server/comments.js').PublicComment} C */
	const entries = $derived.by(() => {
		/** @type {({ photoId: null, c: C } | { photoId: string, list: C[] })[]} */
		const out = [];
		/** @type {Map<string, { photoId: string, list: C[] }>} */
		const groups = new Map();
		for (const c of comments) {
			if (!c.photoId) { out.push({ photoId: null, c }); continue; }
			let g = groups.get(c.photoId);
			if (!g) { g = { photoId: c.photoId, list: [] }; groups.set(c.photoId, g); out.push(g); }
			g.list.push(c);
		}
		return out;
	});

	let total = $derived(comments.reduce((n, c) => n + (c.deleted ? 0 : 1) + c.replies.length, 0));

	/** @param {SubmitEvent} e */
	function sure(e) {
		if (!confirm('Delete your comment? This cannot be undone.')) e.preventDefault();
	}
</script>

{#snippet one(/** @type {import('#lib/server/comments.js').PublicComment} */ c, /** @type {boolean} */ isReply)}
	<article class="comment" class:reply={isReply} class:owner={c.byOwner} id="comment-{c.id}">
		{#if c.deleted}
			<p class="gone">[deleted]</p>
		{:else}
			<header>
				<strong>{c.name}</strong>
				{#if c.byOwner}<span class="tag">author</span>{/if}
				<time datetime={c.createdAt}>{when(c.createdAt)}</time>
				{#if c.editedAt}<span class="fine">edited</span>{/if}
				{#if c.held}<span class="tag held">hidden by the author of this site; only you can see it</span>{/if}
				{#if c.hidden}<span class="tag held">hidden</span>{/if}
			</header>
			<div class="body">{@html html(c.body)}</div>
			{#if form?.commentError && form?.id === c.id}<p class="error" role="alert">{form.commentError}</p>{/if}
			{#if commenting.as}
				<div class="tools">
					{#if !isReply && !c.held && !c.hidden}
						<details name="tools-{c.id}">
							<summary>Reply</summary>
							<form method="POST" action="?/comment">
								<input type="hidden" name="parentId" value={c.id} />
								<textarea name="body" rows="3" maxlength="4000" required aria-label="Your reply">{form?.parentId === c.id ? (form?.draft ?? '') : ''}</textarea>
								{#if form?.commentError && form?.parentId === c.id && !form?.id}<p class="error" role="alert">{form.commentError}</p>{/if}
								<button type="submit">Reply as {commenting.as}</button>
							</form>
						</details>
					{/if}
					{#if c.mine}
						<details name="tools-{c.id}">
							<summary>Edit</summary>
							<form method="POST" action="?/editComment">
								<input type="hidden" name="id" value={c.id} />
								<textarea name="body" rows="3" maxlength="4000" required aria-label="Edit your comment">{c.body}</textarea>
								<button type="submit">Save</button>
							</form>
						</details>
						<form method="POST" action="?/deleteComment" onsubmit={sure} class="bare">
							<input type="hidden" name="id" value={c.id} />
							<button type="submit" class="link">Delete</button>
						</form>
					{/if}
				</div>
			{/if}
		{/if}
	</article>
{/snippet}

{#if commenting.enabled && (comments.length || commenting.as)}
	<section class="comments" id="comments" aria-label="Comments">
		<h2 class="display">{total === 0 ? 'Comments' : total === 1 ? '1 comment' : `${total} comments`}</h2>

		{#each entries as e (e.photoId ?? e.c.id)}
			{#if e.photoId === null}
				<div class="thread">
					{@render one(e.c, false)}
					{#each e.c.replies as r (r.id)}
						{@render one(r, true)}
					{/each}
				</div>
			{:else}
				{@const info = photos.get(e.photoId)}
				<!-- One photo, shown once, with everything said about it underneath. -->
				<section class="thread photo-thread" aria-label="Comments about a photo">
					<button type="button" class="about" onclick={() => (photoFocus.imageId = e.photoId)}>
						<img src={imageUrl(e.photoId, { w: 160, h: 160, fit: 'cover' })} alt={info?.alt ?? ''} loading="lazy" />
						<span class="about-text">
							<span class="about-label">On this photo</span>
							{#if info?.caption}<span class="about-caption">{info.caption}</span>{/if}
						</span>
					</button>
					<div class="photo-comments">
						{#each e.list as c (c.id)}
							{@render one(c, false)}
							{#each c.replies as r (r.id)}
								{@render one(r, true)}
							{/each}
						{/each}
					</div>
				</section>
			{/if}
		{/each}

		{#if commenting.as}
			<form method="POST" action="?/comment" class="new">
				<label for="new-comment">Add a comment as {commenting.as}</label>
				<textarea id="new-comment" name="body" rows="4" maxlength="4000" required>{!form?.parentId && !form?.id && !form?.photoId ? (form?.draft ?? '') : ''}</textarea>
				{#if form?.commentError && !form?.parentId && !form?.id && !form?.photoId}<p class="error" role="alert">{form.commentError}</p>{/if}
				<button type="submit" class="primary">Post comment</button>
			</form>
		{:else}
			<p class="signin"><a href="/signin">Sign in</a> to add a comment.</p>
		{/if}
	</section>
{/if}

<style>
	/* Set apart from the story by a rule, and a step quieter than it: the
	   story is the letter, this is what people wrote back. */
	.comments {
		max-width: 38rem; margin: 4.5rem auto 0; padding-top: 2.5rem;
		border-top: 1px dashed var(--color-pencil-soft);
		display: grid; gap: 1.4rem;
	}
	h2 { font-size: 1.7rem; margin: 0 0 0.2rem; }

	.thread { display: grid; gap: 1.1rem; }
	.thread + .thread { border-top: 1px solid var(--color-paper-deep); padding-top: 1.4rem; }
	.comment { display: grid; gap: 0.45rem; }
	/* A reply hangs off its parent on a thread line instead of a second rule. */
	.comment.reply { margin-left: 0.4rem; padding-left: 1.1rem; border-left: 1px solid var(--color-pencil-soft); }

	header { display: flex; flex-wrap: wrap; gap: 0.3rem 0.6rem; align-items: baseline; }
	header strong { font-size: 0.95rem; color: var(--color-ink); }
	time, .fine {
		font-family: var(--font-mono); font-size: 0.66rem; letter-spacing: 0.1em; text-transform: uppercase;
		color: var(--color-pencil);
	}
	.tag {
		font-family: var(--font-mono); font-size: 0.6rem; letter-spacing: 0.12em; text-transform: uppercase;
		padding: 0.1rem 0.4rem; border-radius: 2px; color: var(--color-wax); border: 1px solid var(--color-wax);
	}
	.tag.held { border: 0; background: var(--color-paper-deep); color: var(--color-pencil); text-transform: none; letter-spacing: 0; }
	/* The photo a group of comments is about: thumbnail, caption, and the
	   comments hanging off it on a thread line. The whole header opens it. */
	.about {
		display: grid; grid-template-columns: auto 1fr; gap: 0.9rem; align-items: center;
		width: 100%; padding: 0; border: 0; background: none; cursor: zoom-in;
		text-align: left; text-transform: none; letter-spacing: 0; font-family: inherit; color: inherit;
	}
	.about img { display: block; width: 4.2rem; height: 4.2rem; object-fit: cover; border-radius: 2px; background: var(--color-paper-deep); }
	.about-text { display: grid; gap: 0.2rem; min-width: 0; }
	.about-label { font-family: var(--font-mono); font-size: 0.66rem; letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil); }
	.about-caption { font-family: var(--font-display); font-style: italic; font-size: 1.05rem; line-height: 1.3; color: var(--color-ink); overflow-wrap: anywhere; }
	.about:hover .about-label, .about:hover .about-caption { color: var(--color-wax); }
	.photo-comments {
		display: grid; gap: 1.1rem;
		margin-left: 2.1rem; padding-left: 1.1rem;
		border-left: 1px solid var(--color-pencil-soft);
	}
	.body { font-size: 0.97rem; }
	.body :global(p) { margin: 0 0 0.6rem; line-height: 1.6; color: var(--color-ink-soft); overflow-wrap: anywhere; }
	.body :global(p:last-child) { margin-bottom: 0; }
	.gone { margin: 0; color: var(--color-pencil); font-style: italic; }

	/* Reply, Edit and Delete are one row of identical quiet labels. The one
	   that is open drops to its own full-width row underneath. */
	.tools { display: flex; flex-wrap: wrap; align-items: center; gap: 0 1.2rem; margin-top: 0.1rem; }
	.tools details[open] { order: 2; flex: 1 0 100%; }
	summary, button.link {
		display: inline-flex; align-items: center; min-height: 2rem;
		font-family: var(--font-mono); font-size: 0.66rem; letter-spacing: 0.12em; text-transform: uppercase;
		color: var(--color-pencil); cursor: pointer;
	}
	summary { list-style: none; }
	summary::-webkit-details-marker { display: none; }
	summary:hover { color: var(--color-ink); }
	details[open] > summary { color: var(--color-ink); }
	details[open] > summary::after { content: '×'; margin-left: 0.5em; font-size: 1.2em; letter-spacing: 0; }
	button.link { border: 0; background: none; padding: 0; margin: 0; }
	button.link:hover { color: var(--color-wax); }

	form { display: grid; gap: 0.5rem; }
	form.bare { display: contents; }
	.tools details form { margin: 0.2rem 0 0.4rem; }
	label { font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil); }
	textarea {
		font: inherit; font-size: 0.95rem; line-height: 1.5; color: var(--color-ink); background: var(--color-paper-deep);
		border: 1px solid var(--color-pencil-soft); border-radius: 3px; padding: 0.6rem 0.7rem; resize: vertical; width: 100%; box-sizing: border-box;
	}
	textarea:focus { border-color: var(--color-ink); }
	button {
		font-family: var(--font-mono); font-size: 0.7rem; letter-spacing: 0.12em; text-transform: uppercase;
		background: transparent; color: var(--color-ink); border: 1px solid var(--color-pencil-soft);
		border-radius: 3px; padding: 0.5rem 0.8rem; cursor: pointer; justify-self: start;
	}
	button:hover { border-color: var(--color-ink); }
	button.primary { background: var(--color-ink); border-color: var(--color-ink); color: var(--color-paper); }
	button.primary:hover { background: var(--color-wax); border-color: var(--color-wax); }
	.new { border-top: 1px solid var(--color-paper-deep); padding-top: 1.4rem; }
	.signin { margin: 0; font-size: 0.9rem; color: var(--color-pencil); }
	.error { color: var(--color-wax); margin: 0; font-size: 0.85rem; }

	/* ── Phones: thumb-sized controls, less indent, a full-width Post ─────── */
	@media (max-width: 640px) {
		.comments { margin-top: 3rem; padding-top: 2rem; }
		.comment.reply { margin-left: 0; padding-left: 0.9rem; }
		.photo-comments { margin-left: 0.4rem; padding-left: 0.9rem; }
		.about img { width: 3.6rem; height: 3.6rem; }
		summary, button.link { min-height: 44px; }
		textarea { font-size: 1rem; }
		button { min-height: 44px; }
		button.primary { width: 100%; justify-self: stretch; }
	}
</style>
