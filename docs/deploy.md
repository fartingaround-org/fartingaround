# Deploying fartingaround.fun on the droplet

One container, built on the host, next to the other sites. No CI, no
registry: `git pull` plus `docker compose up --build` (`scripts/deploy.sh`
does exactly that). Same pattern as theschmidt.house.

## Prerequisites on the host

- The external `web` docker network exists and the shared Caddy proxy is on
  it. The proxy is its own compose stack in `/home/tony/proxy` (container
  `caddy`; `Caddyfile` and `certs/` beside its `docker-compose.yml`), with
  `web` listed under the service's `networks:` so a recreate keeps it.
- **No web server installed on the host itself.** A host `caddy`, `nginx` or
  `apache2` package competes with the proxy container for ports 80/443 at
  every boot; see "If the sites are down after a reboot" below.
- **Optional — Postgres.** The default is SQLite in `./data`, which needs
  nothing. To join the fleet's shared `personal-db` instead, the
  `fartingaround` role + database must exist. They're created on the cluster's
  first start from `DB_FARTINGAROUND_PASSWORD` in outerspiralarm-web's `.env`;
  if the cluster was already initialised without it:

  ```sh
  docker exec -it personal-db psql -U postgres -v pw="$(openssl rand -hex 24)" <<'SQL'
  \echo :pw
  CREATE ROLE fartingaround LOGIN PASSWORD :'pw';
  CREATE DATABASE fartingaround OWNER fartingaround;
  REVOKE CONNECT ON DATABASE fartingaround FROM PUBLIC;
  SQL
  ```

  (and record that password as `DB_FARTINGAROUND_PASSWORD` in
  outerspiralarm-web's `.env` so it's in one place).
- A deploy key for this repo:

  ```sh
  ssh-keygen -t ed25519 -f ~/.ssh/fartingaround_deploy -N '' -C 'fartingaround.fun deploy @ droplet'
  cat ~/.ssh/fartingaround_deploy.pub   # → GitHub → repo → Settings → Deploy keys (read-only)
  ```

## 1. Storage: a block-storage volume for `./data`

Everything the site owns — the SQLite database and all media — lives in one
folder. On the droplet that folder is a DigitalOcean Volume, so it survives a
droplet rebuild and can be grown online when it fills.

Create the volume in the DO dashboard (10 GB to start) and attach it; then on
the droplet (DO shows the exact device name):

```sh
DEV=/dev/disk/by-id/scsi-0DO_Volume_fartingaround-data
mkfs.ext4 -F $DEV
mkdir -p /mnt/fartingaround-data
mount -o discard,defaults,noatime $DEV /mnt/fartingaround-data
echo "$DEV /mnt/fartingaround-data ext4 defaults,nofail,discard,noatime 0 2" >> /etc/fstab
chown -R 1000:1000 /mnt/fartingaround-data      # the container writes as uid 1000
```

After cloning (next step), tell compose to mount it instead of `./data` by
adding to `.env`:

```
DATA_PATH=/mnt/fartingaround-data
```

Turn on automatic snapshots for the volume (Volumes → the volume → Snapshots)
— one snapshot is a complete, restorable copy of the site.

## 2. Clone and configure

```sh
GIT_SSH_COMMAND='ssh -i ~/.ssh/fartingaround_deploy -o IdentitiesOnly=yes' \
  git clone https://github.com/fartingaround-org/fartingaround.git /home/tony/fartingaround
cd /home/tony/fartingaround.fun
cp .env.example .env
```

Fill in `.env`:

- `ADMIN_PASSWORD` — the `/admin` login.
- `CAPTURE_API_TOKEN` — the phone app's token (`openssl rand -hex 32`); paste
  the same value into the app's Settings.
- `DATABASE_URL` — leave empty for SQLite, or
  `postgres://fartingaround:<pw>@personal-db:5432/fartingaround` for the
  shared cluster. Decide before the first start; switching later means
  migrating data.
- `BODY_SIZE_LIMIT` — default `2G`; raise if your videos are bigger.
- `ELEVENLABS_API_KEY` — optional. Turns on **Transcribe** for audio captions
  on photo-dump photos in `/admin` (Scribe, with word timestamps so the
  lightbox can show closed-caption style text). `ELEVENLABS_MODEL` defaults
  to `scribe_v1`.
- `DEBUG_PORT` — host port for on-host curl checks (default `8406`; pick one
  the other sites aren't using).

## 3. Bring the content over from Cloudflare (once)

Two stages. **Export** on your laptop, where wrangler is logged in and
`.dev.vars` still has the Cloudflare credentials:

```sh
cd ~/Projects/fartingaround.fun
npx wrangler whoami                                  # logged in?
node scripts/migrate-export.mjs ./migration-export   # KV via wrangler, Images API, R2 via S3 API
```

It writes stories/drafts as JSON, every Images original, and every object
from both R2 buckets, then a `manifest.json`. Re-runnable; it skips what's
already on disk. Then copy the folder to the droplet (it can be a few GB —
rsync resumes):

```sh
rsync -avP ./migration-export/ tony@<droplet>:/mnt/fartingaround-data/import/
```

**Import** on the droplet, inside the running site container (it has the
database, the data volume and sharp):

```sh
cd /home/tony/fartingaround.fun
docker compose exec site node scripts/migrate-import.mjs /data/import
```

Image ids and object keys are kept, so stories need no rewriting beyond the
`imagedelivery.net` URLs on draft attachments, which become `/img/<id>/…`.
The report at the end lists any image or file a story references that
didn't come across. When it says everything referenced is present, you can
delete `data/import/`. Re-running replaces rows and leaves existing files.

Then pre-render the common image sizes, so photo dumps and galleries aren't
slow on first view (every size is otherwise rendered by sharp the first time
someone asks for it):

```sh
curl -N -X POST -H "Cookie: admin=$ADMIN_PASSWORD" http://localhost:8406/admin/api/warm-images
```

It streams progress and takes a few minutes for a few hundred photos. New
uploads warm themselves in the background.

## 4. Start it

```sh
docker compose up -d --build
curl -s localhost:8406/rss.xml | head -c 300
docker compose logs site        # "[db] applied migration 001_init.sql", "[startup] data in /data; database: sqlite"
```

Log in at `/login`; `/admin/api/diag` (once logged in) shows the database in
use, row counts, free disk and whether `sharp` loaded.

## 5. Route it

The site sits behind Cloudflare's proxy (for the edge analytics). The proxy
caps request bodies at 100 MB on the Free plan, so big phone uploads go to a
separate **DNS-only** hostname on the same container — `UPLOAD_ORIGIN` in
`.env` makes the signed upload URLs use it.

`.env`:

```
UPLOAD_ORIGIN=https://upload.fartingaround.fun
```

Site blocks, added to the shared proxy's `/home/tony/proxy/Caddyfile`:

```caddy
fartingaround.fun, www.fartingaround.fun {
	encode zstd gzip
	request_body {
		max_size 60MB     # admin photo uploads (the 50 MB cap + headroom)
	}
	reverse_proxy fartingaround-site:3000
}

upload.fartingaround.fun {
	request_body {
		max_size 2GB      # phone video; match BODY_SIZE_LIMIT
	}
	reverse_proxy fartingaround-site:3000
}
```

Validate and reload Caddy:

```sh
cd /home/tony/proxy
docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile
docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile
```

Then DNS in the Cloudflare zone — delete the `*.pages.dev`
CNAMEs first, then:

| Type  | Name                | Content           | Proxy    |
|-------|---------------------|-------------------|----------|
| A     | `fartingaround.fun` | droplet IP        | Proxied  |
| CNAME | `www`               | `fartingaround.fun` | Proxied |
| A     | `upload`            | droplet IP        | DNS only |

SSL/TLS → Overview → **Full (strict)** (Caddy holds real certificates; its
HTTP-01 challenge works through the proxy, and `upload.` isn't proxied).

Caching → Cache Rules → create one: URI Path starts with `/img/` **or**
starts with `/media/` → Eligible for cache, Edge TTL "respect origin". Our
image and media URLs are immutable and carry one-year cache headers, so this
makes Cloudflare's edge the image CDN; without the rule the proxy skips them
(it only caches extension-bearing URLs by default).

**Never cache `/private/`.** Photos and media of private stories and drafts
are served from `/private/img/…` and `/private/media/…`, checked against who
is asking on every request. The rule above does not match those paths; keep
it that way, and do not add a "cache everything" rule in front of the site.
A shared cache in front of `/private/` would hand private photos to anyone
with the address. The public `/img/` and `/media/` addresses only ever serve
files that a published public story uses, and redirect everything else to
`/private/`.

One limit to know about: if a public story is later made private (or
unpublished), copies of its photos already in Cloudflare's cache stay
fetchable, by anyone who has the exact address, until they expire. The site
stops serving them at once; the cache does not know. Purge the cache from
the Cloudflare dashboard if that matters for a particular story.

Pages keeps serving until the records propagate, so there's no gap. Once the
droplet is answering, delete the Pages project; the KV namespace, R2 buckets
and Images can go after the migration is verified.

## Private stories: what they protect, and what they don't

Stories can be marked private and shown only to people you invite as
members (Admin → Readers). Before relying on that, know its limits:

- **It stops strangers, search engines and scrapers.** A private story, its
  photos, audio, video and comments are only served to you and to signed-in
  members, and it is left out of the public feeds and lists.
- **It does not stop a member passing things on.** Anyone you invite can
  save or forward what they see. Invite people you would hand the photos to.
- **Removing someone is not retroactive.** It cuts them off at once on the
  site, but copies already in their browser, feed reader or phone stay there.
- **Once public, not fully recallable.** Making a public story private stops
  the site serving it, but copies in Cloudflare's cache (see above), in feed
  readers and in search engines linger until they expire.
- **Where the site runs is visible.** A server at home exposes your home's
  IP address unless it sits behind a proxy or tunnel (Cloudflare's proxy as
  set up above, or a Cloudflare Tunnel). On a VPS, the provider's address is
  what shows.
- **Your domain registration is public record** unless the registrar's
  privacy protection (WHOIS privacy) is on. Turn it on.
- **Invite and recovery links are keys.** Send them over a channel you
  already use with that person. Someone pretending to be a reader and asking
  for a new link is the realistic way in.
- **The admin password protects everything.** Use a long random one.

## Later deploys

```sh
/home/tony/fartingaround.fun/scripts/deploy.sh
```

## If the sites are down after a reboot

Run `fa doctor` first; it checks for both of the failures below. This is
what took every site down on 2026-11-20.

1. **The proxy container didn't start.** `docker ps -a` shows `caddy` as
   `Exited (128)`, and

   ```sh
   docker inspect caddy --format '{{.State.Error}}'
   ```

   says `failed to bind host port 0.0.0.0:80/tcp: address already in use`.
   Something on the host took the port before docker did, typically a
   distro `caddy`/`nginx`/`apache2` package serving its placeholder page.
   Docker does not retry a failed start, so the proxy stays down.

   ```sh
   sudo ss -ltnp | grep -E ':(80|443)\s'     # names the process
   sudo systemctl disable --now caddy         # or nginx / apache2
   cd /home/tony/proxy && docker compose up -d
   ```

2. **Every site answers 502.** The proxy is up but its log says
   `lookup <site-container> ... server misbehaving`: it is not attached to
   the `web` network, so it cannot resolve the site containers.

   ```sh
   docker inspect caddy --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
   cd /home/tony/proxy && docker compose up -d --force-recreate
   ```

   If `web` is missing from the first command and the recreate doesn't bring
   it back, `web` is not declared under the service's `networks:` in
   `/home/tony/proxy/docker-compose.yml`. Add it there; a hand-run
   `docker network connect` does not survive a failed start.

Check with `curl -sI https://fartingaround.fun | head -1`.

## Backups

- **SQLite mode:** everything is on the volume — `site.db` plus `objects/`.
  DO volume snapshots cover it in one go; for a file-level copy use
  `sqlite3 data/site.db ".backup data/site.db.bak"` first so the database
  part is consistent while the site runs.
- **Postgres mode:** `pg_dump` the `fartingaround` database (the existing
  `scripts/atlas-backup.sh` in outerspiralarm-web covers the whole cluster)
  plus a copy of `./data/objects`.
- `data/objects/images/*/cache/` is derived and safe to leave out.

## Testing locally against the live content

From the repo on your laptop, with `pnpm dev` stopped:

```sh
scripts/pull-prod.sh tony@your-droplet            # database + media
scripts/pull-prod.sh tony@your-droplet --no-media # database only
```

It reads the live database through the running container (so it works for
SQLite and Postgres alike), replaces the local stories, comments, contacts
and settings with it, and rsyncs `objects/` down. Sign-in sessions, links and
passkeys are not copied. The dump is kept at `data/prod-dump.jsonl`, which git
ignores; it holds private stories and the contact book.
