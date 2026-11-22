# fartingaround

A small social site you run yourself. Stories, photos, audio and video, shared
with the people you choose, with an RSS feed anyone can follow. No ads, no
tracking, nothing deciding what your readers see next. Each person or family
owns their own site and their own data.

The name is from Kurt Vonnegut: "we are here on Earth to fart around, and
don't let anybody tell you any different."

This repo holds three things:

- **The website** — SvelteKit, one Docker container, at the repo root. The
  public site and the `/admin` authoring tool. (Docs below.)
- **fa** — a small program that sets a site up on a server and looks after it,
  from the terminal or a dashboard on your own computer. See
  [`tools/fa/README.md`](tools/fa/README.md).
- **The phone app** — native Android (Kotlin + Jetpack Compose) at
  [`capture/`](./capture). Records audio, takes photos and video, accepts
  shares from other apps, notes where you were with the weather and the moon,
  and uploads each story in the background. Also a reader for the sites you
  follow. See [`capture/README.md`](./capture/README.md).

A ready-made image of the site is published to
`ghcr.io/fartingaround-org/fartingaround` for each release, so a server does
not have to build it.

The phone app talks to the site with a shared token (`CAPTURE_API_TOKEN`); see
"Running it" below.

## Stack

- **SvelteKit 3** with Svelte 5 (runes), Vite 8, Tailwind 4, `adapter-node`
- **One container**, behind whatever reverse proxy you already run (Caddy, nginx, Traefik)
- **SQLite** by default (`node:sqlite`, nothing to install) — or **Postgres** by setting `DATABASE_URL`
- **Media on disk** under `DATA_DIR` — originals plus variants rendered on demand with `sharp` and cached

Everything the site owns lives in one folder (`./data` → `/data` in the
container): the database and every photo, recording and video. Backing up
the site is copying that folder.

## How stories are stored

```
stories  (slug, date, draft, updated_at, doc)   doc = the Story as JSON
drafts   (id, status, created_at, updated_at, doc)
images   (id, ext, mime, width, height, bytes, filename, created_at)

data/objects/
  images/<id>/orig.<ext>          uploaded photo
  images/<id>/cache/w800_q82_…    rendered variants (safe to delete)
  gallery/<id>/orig.<ext>, w<N>.jpg   retired astro galleries (display-only)
  drafts/<draftId>/<attachmentId>.<ext>   audio + video from the phone app
```

A story is a list of typed blocks: `text`, `photo`, `diptych`, `gallery`
(retired astrophotography filmstrip — existing ones still display, new ones can't be made), `dump`
(photo dump — any number of trip photos, bulk-uploaded, justified-row grid),
`pull`, `aside`, `divider`, `audio`, `video`. Photos carry an image id; URLs
are built at render time as `/img/<id>/w=1200,q=85` and rendered by the
server the first time each size is asked for.

## Running it

```bash
cp .env.example .env     # ADMIN_PASSWORD at minimum; CAPTURE_API_TOKEN for the phone app
docker network create web
docker compose up -d --build
```

Point your reverse proxy at `fartingaround-site:3000` on the `web` network —
there's a Caddy snippet in `docker-compose.yml`. Log in at `/login`.

To use Postgres instead of SQLite, set `DATABASE_URL=postgres://…` in `.env`
before the first start. The schema is created automatically either way
(`db/migrations/`, applied at startup).

### fa, the control center

`tools/fa` is a small Go program that does the above for you and then looks
after the install: setup, deploys with rollback, status and logs, the server's
`.env`, automatic off-server backups and one-button restores, from the terminal
or a dashboard on your own computer (`fa ui`). See
[`tools/fa/README.md`](tools/fa/README.md).

## Local development

```bash
cp .env.example .env
pnpm install
pnpm dev                 # http://localhost:5173 — data in ./data
```

`pnpm build && pnpm start` runs the production server the same way the
container does.

## Authoring flow

1. Log in.
2. `/admin` → "New story". You get a draft with title + date.
3. Editor opens. Add blocks in any order: text, photos, diptychs, etc. Drag
   files onto a photo slot to upload — it's stored on the server and the
   image ID is stored in the story.
4. Toggle **Draft** off when ready. Save (⌘S works).
5. The story is live at `/stories/<slug>`.

## Project layout

```
tools/fa/             the control center (Go): setup, deploy, backups, dashboard
src/
  app.html            shell + Google Fonts
  app.css             tokens + global type styles
  app.d.ts            App.Locals typing
  env.js              declared environment variables
  hooks.server.js     opens the db + migrates at startup; admin cookie check
  lib/
    types.js          Story / Block JSDoc typedefs
    image.js          /img URL builder
    format.js
    server/
      config.js       runtime config from env
      db.js           sqlite / postgres behind one tiny interface; migrations
      stories.js, drafts.js   repositories
      objects.js      files under DATA_DIR/objects
      images.js       image store + on-demand sharp variants
      serve.js        Range/ETag streaming of stored objects
      upload-urls.js  short-lived signed PUT URLs for big uploads
    components/
      StoryBlocks.svelte
      blocks/
        BlockText / BlockPhoto / BlockDiptych / BlockPull /
        BlockAside / BlockDivider
      admin/
        CoverPicker / PhotoSlot / BlockEditor
  routes/
    +layout.svelte    header, footer, postmark
    +page.svelte      home (recent stories)
    stories/
      +page.svelte    index by year
      [slug]/+page.svelte
    about/
    login/
    admin/
      +layout.svelte  red ribbon shell, auth gate
      +page.svelte    list of stories + new-story form
      [slug]/+page.svelte   editor
      api/upload/+server.js  POST → image store
      logout/+server.js
db/migrations/        schema, applied at startup
Dockerfile, docker-compose.yml
```

## Drafts from the phone

The capture app at `capture/` (native Android) pushes drafts (title, body,
tags, location, audio, photo, video) to this site via the `/api/drafts/*`
endpoints. They show up under `/admin/drafts` for review and promotion into
a real Story.

- **Photos** → the image store; we store the image id on the attachment.
- **Audio + video** → `data/objects/drafts/<id>/…` via a signed PUT URL
  (`sign` → `PUT /api/upload/<key>` → `confirm`), so big files stream
  straight to disk. Served back through `/api/drafts/:id/media/:attachmentId`.
- **Draft document** → the `drafts` table.

Set `CAPTURE_API_TOKEN` in `.env` (`openssl rand -hex 32`) and paste the same
value into the phone app's Settings.

## Roadmap

- [ ] Drag-to-reorder blocks (right now: ↑/↓ buttons)
- [ ] Markdown shortcuts in text blocks (`**bold**`, `*italic*`, `[link](url)`)
- [ ] Add a real video block type so promoted drafts can keep their video
- [ ] Tag pages at `/tags/:tag`
- [ ] Proton Drive personal backup (phase 2, blocked on Proton's
      production-ready SDK release — currently target end-2026/early-2027)

Recently shipped:
- ✓ Photo dump block — drop a whole trip's worth of photos at once; they
  upload three at a time (downscaled client-side only if over the 10 MB
  Images cap), land in filename order, and render as justified rows with a
  lightbox + slideshow. Drag thumbnails to reorder, click one to caption it.
  GPS and time-taken are read from EXIF at upload and kept **private** on the
  photo (`exif`, stripped by `publicStory()` before a visitor sees the story);
  "Use location" copies it to the public `coords`. With locations applied,
  the block's Map option shows thumbnail pins, or pins joined by a dashed
  path in time-taken order.
- ✓ Map view of stories with `coords`
- ✓ RSS feed at `/rss.xml`
- ✓ Audio block — embeds the original recording in promoted stories
- ✓ Auto-context — weather + moon phase + sunset captured with each GPS tap

## Licence

AGPL-3.0-or-later; see [`LICENSE`](LICENSE). If you run a changed version of
this as a service for other people, the licence asks you to share your
changes with them.

