<script>
	let { data, form } = $props();
	const site = $derived(data.site);
	const defaults = $derived(data.siteDefaults);
	// A field left at its default is shown empty, with the default as the hint.
	/** @param {string} value @param {string} fallback */
	const own = (value, fallback) => (value === fallback ? '' : value);
</script>

<svelte:head><title>Settings — admin</title></svelte:head>

<header class="header">
	<h1 class="display">Settings</h1>
	<p class="eyebrow">Your site's name and words, and who sees what</p>
</header>

{#if form?.error}<p class="a-error" role="alert">{form.error}</p>{/if}
{#if form?.saved}<p class="a-notice" role="status">Saved.</p>{/if}

<form method="POST" action="?/save" class="form">
	<section class="a-panel group">
		<h2>Site</h2>
		<label>Site name
			<input name="siteName" maxlength="80" placeholder={data.host} value={own(site.name, data.host)} />
		</label>
		<p class="a-fine">
			Shown in the header, in page titles and as the name of your feed. Left blank, the site is
			named after its address.
		</p>
		<label>Tagline
			<input name="tagline" maxlength="120" placeholder="a logbook" value={site.tagline} />
		</label>
		<p class="a-fine">A few words after the name in the home page's title. Optional.</p>
		<label>Your name on the About page
			<input name="ownerName" maxlength="80" value={site.ownerName} />
		</label>
		<label>Where that name links
			<input name="ownerUrl" type="url" maxlength="300" placeholder="https://" value={site.ownerUrl} />
		</label>
		<label>Contact email
			<input name="contactEmail" type="email" maxlength="200" value={site.contactEmail} />
		</label>
		<p class="a-fine">Each is shown at the foot of the About page when filled in. All optional. The email is public.</p>
		<label>Support link
			<input name="supportUrl" type="url" maxlength="300" placeholder="https://" value={site.supportUrl} />
		</label>
		<label>Support link wording
			<input name="supportLabel" maxlength="60" placeholder={defaults.supportLabel} value={own(site.supportLabel, defaults.supportLabel)} />
		</label>
		<p class="a-fine">A tip-jar link in the footer and on the About page. Left blank, there is none.</p>
	</section>

	<section class="a-panel group">
		<h2>Home page</h2>
		<label>Line above the headline
			<input name="homeEyebrow" maxlength="80" placeholder="Vol. 1 · Est. 2026" value={site.homeEyebrow} />
		</label>
		<label>Headline
			<textarea name="homeHeadline" rows="3" maxlength="200" placeholder={defaults.homeHeadline} value={own(site.homeHeadline.join('\n'), defaults.homeHeadline)}></textarea>
		</label>
		<p class="a-fine">Up to three lines; each line is drawn in its own style.</p>
		<label>Introduction
			<textarea name="homeIntro" rows="4" maxlength="600" placeholder={defaults.homeIntro} value={own(site.homeIntro, defaults.homeIntro)}></textarea>
		</label>
		<p class="a-fine">The paragraph under the quote. Left blank, the words shown in grey are used.</p>
	</section>

	<section class="a-panel group">
		<h2>About page</h2>
		<label>Headline
			<input name="aboutHeadline" maxlength="120" placeholder={defaults.aboutHeadline} value={own(site.aboutHeadline, defaults.aboutHeadline)} />
		</label>
		<label>Text
			<textarea name="about" rows="12" maxlength="8000" value={site.about}></textarea>
		</label>
		<p class="a-fine">
			Leave a blank line between paragraphs. Links: [the words](https://example.org). Left blank,
			the About page shows the story of where this kind of site gets its name.
		</p>
	</section>

	<section class="a-panel group">
		<h2>You</h2>
		<label>Your name
			<input name="authorName" maxlength="80" autocomplete="name" placeholder="Author" value={data.authorName === 'Author' ? '' : data.authorName} />
		</label>
		<p class="a-fine">
			Shown on your own comments, next to the "author" mark. Left blank, they are signed "Author".
		</p>
	</section>

	<section class="a-panel group">
		<h2>Stories</h2>
		<label>A new story starts as
			<select name="visibility" value={data.defaultVisibility}>
				<option value="public">Public: anyone can read it</option>
				<option value="private">Private: members only</option>
			</select>
		</label>
		<p class="a-fine">
			Applies to stories created from now on, here or from the phone. Existing stories keep
			theirs; change one from its editor.
		</p>
	</section>

	<section class="a-panel group">
		<h2>Comments</h2>
		<label>Comment sections are shown to
			<select name="mode" value={data.commentsMode}>
				<option value="everyone">Everyone who can read the story</option>
				<option value="readers">Signed-in readers only</option>
				<option value="off">Nobody (comments off)</option>
			</select>
		</label>
		<p class="a-fine">
			Only signed-in readers and you can ever post. With "signed-in readers only", the public sees
			your public stories with no comment section at all. Changing this deletes nothing.
		</p>
	</section>

	<section class="a-panel group">
		<h2>Following</h2>
		<label>The list of sites you follow is
			<select name="following" value={data.followingPublic ? 'public' : 'hidden'}>
				<option value="public">Public: shown at /following</option>
				<option value="hidden">Hidden: only you see it</option>
			</select>
		</label>
	</section>

	<button type="submit" class="a-btn primary">Save settings</button>
</form>

<style>
	.header { margin-bottom: 1.4rem; }
	.form { display: grid; gap: 1rem; max-width: 44rem; justify-items: start; }
	.group { display: grid; gap: 0.7rem; width: 100%; }
	h2 { font-family: var(--font-display); font-style: italic; font-weight: 500; font-size: 1.3rem; margin: 0; }
	label {
		display: grid; gap: 0.3rem; font-family: var(--font-mono); font-size: 0.68rem;
		letter-spacing: 0.12em; text-transform: uppercase; color: var(--color-pencil);
	}
	textarea { width: 100%; resize: vertical; line-height: 1.5; }
	select, input, textarea {
		font-family: var(--font-body); font-size: 0.95rem; letter-spacing: 0; text-transform: none;
		color: var(--color-ink); background: var(--color-paper); border: 1px solid var(--color-pencil-soft);
		border-radius: 3px; padding: 0.5rem 0.55rem; min-width: 0; max-width: 100%;
	}
</style>
