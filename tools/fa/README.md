# fa — control center for a fartingaround site

One static binary that sets up, runs, checks, deploys and backs up a
[fartingaround](https://github.com/fartingaround-org/fartingaround) install.
It runs on the server; from your laptop, `fa --host user@box …` runs the
same command there over ssh. No daemon, no open port — ssh is the auth.

Works on a $6 VPS (droplet or the like) or a Raspberry Pi at home behind a
Cloudflare Tunnel.

## Install

fa lives in the site's own repository, under `tools/fa`. It is plain Go with
no dependencies.

On your Mac:

```sh
go install github.com/fartingaround-org/fartingaround/tools/fa@latest
# or, from a checkout:
cd tools/fa && go build -o fa . && mv fa /usr/local/bin/
```

On the server (Linux, amd64 or arm64), either build it there the same way if
Go is installed, or build it on your Mac and copy it over:

```sh
tools/fa/scripts/release.sh v0.2.0          # cross-compiles into tools/fa/dist/
scp tools/fa/dist/fa-linux-amd64 user@box:/usr/local/bin/fa
```

After that first install, fa updates itself:

```sh
fa self-update user@box     # git pull, build, replace fa here AND on the server
fa self-update              # this computer only
fa self-update --no-pull    # build what is in the checkout as it stands
```

or **update fa** in the dashboard's Settings, which restarts the page into the
new one. The server needs no Go: its fa is cross-compiled here and copied over
ssh. The source is found from where the binary sits, or from `FA_SRC` if the
checkout is elsewhere (fa remembers after the first update). The previous
binary is kept beside each new one as `fa.prev`. If the server keeps fa in a
folder your user cannot write (and sudo wants a password), the new one is left
in `/tmp` there with the one `sudo mv` to finish it.

Keep the two the same version: the dashboard (`fa ui`, on your Mac) talks to
the `fa` on the server, and a newer page asks an older server for things it
does not know.

## First run

```sh
fa setup
```

asks where to put things, which database, how the site is reached, and
starts it.

Every question can also be answered ahead of time, so a script (or a server's
first-boot script) can run it with nothing to type:

```sh
fa setup --yes --domain example.org --proxy caddy --acme-email me@example.org
fa setup --yes --answers site.answers --env ELEVENLABS_API_KEY=…
```

`--yes` asks nothing and takes the default for anything not given; without it,
setup still asks whatever you did not answer. `--answers` is a file of
`key=value` lines with the same names as the flags. `fa setup --help` lists
them all. A generated admin password is not printed in this mode; read it with
`fa env get ADMIN_PASSWORD`.

Three ways to expose a site:

| mode | when |
|---|---|
| `existing` | you already run Caddy/Traefik/nginx in Docker on a network called `web` (the fleet pattern) |
| `caddy` | a fresh VPS: fa runs Caddy, ports 80/443 open, DNS pointed at the box |
| `tunnel` | home / Pi: Cloudflare Tunnel, no inbound ports, no dynamic DNS |

Then point DNS, and `fa doctor` tells you what's left.

## More than one site on a server

```
fa add notes --domain notes.example.org    another site, beside the first
fa sites                                   every site here, with its state
fa -s notes status                         any command, for one site
fa -s notes backup
```

`fa add` asks for the name and the address when they aren't given. The new
site is a complete install of its own: its own folder (`fa-notes`, next to the
first site's), its own data and SQLite database, its own container
(`fa-notes`) and its own admin password. It starts empty and runs the same
version as the first site. Nothing is shared between sites except the
downloaded image and the reverse proxy.

Without `-s`, fa means the site the server started with (`main`), or the one
whose folder you are in. Each site is updated by its own `fa -s NAME deploy`.

The proxy: where fa runs Caddy itself, `fa add` adds the new address and
reloads it. A server behind a Cloudflare Tunnel can't take a second site yet.

### Your own Caddy: a file per site

Where the proxy is your own Caddy, fa never edits its Caddyfile. Each site
gets a small file of its own in a folder the proxy reads:

```
fa proxy show     print this site's entry
fa proxy write    save it as the site's own file, check the proxy accepts it, reload
```

Once per server, give the proxy that folder and tell it to read it. In the
proxy's compose file, under the caddy service's volumes:

```
      - ./sites.d:/etc/caddy/sites:ro
```

and in its Caddyfile, on a line of its own outside any block:

```
import sites/*.caddy
```

(If the whole of `/etc/caddy` is already a folder on the server, only the
`import` line is needed.) `fa proxy write` tells you which of these is missing.
It finds the folder by asking Docker, so there is nothing to configure in fa.

The file holds the site's address (and `www.` for a bare domain), and its
upload address when `UPLOAD_ORIGIN` is set; run `fa proxy write` again after
changing either. A block for the same address left in the main Caddyfile has
to be removed first, and fa says so instead of writing a duplicate. If the
proxy won't accept the result, the file is taken back out and nothing changes
for the other sites. `fa add` does all this for a new site by itself.

The list of sites is `~/.config/fa/sites.json` on the server. A server with
one site has no such file and needs none.

## The dashboard

```sh
fa ui                    # on your laptop; opens http://127.0.0.1:7321/?t=…
```

A page with Overview (status at a glance, doctor, disk), live Logs, Actions
(deploy / restart / backup / pull, output streamed), and Settings (.env,
token rotation), for as many servers as you add. It listens on localhost
only, each run has its own token, and every button is one of the commands
below run over ssh — the server never opens a port for it.

- **One next step.** When commits are pushed but not deployed, Overview names
  them and *deploy* becomes the filled button.
- Anything that needs a yes is asked beside the button that raised it, and
  the button names what it will do; deploy's question lists what it pulls.
- Logs, Actions and Settings each have their own console, so a log follow
  can stream while a deploy runs. A console keeps earlier runs, each under a
  rule; while one is running its buttons are off and *stop* is on.
- **A run outlives the page.** Deploy, restart, backup and pull belong to the
  `fa ui` process, not the tab: close the page and they carry on; open it
  again and it shows what it missed. (A log follow and a token rotation do
  end with the page. Ctrl-C on `fa ui` ends everything.)
- **.env** is one table: what is set (secrets masked), a field to change each
  row, a tick to remove one, *save and restart* or *save only*.
- When the server's fa is a different release from the one on your laptop,
  Overview says so: every button is a command the server's fa has to know.
- The page remembers the server and view you were on.

## Every day

```
fa status              what's running, disk, last backup
fa logs [site|caddy|tunnel|db] [-f] [-n N] [--since 15m]
fa doctor              the usual problems, each with its fix
fa deploy              download the newest image, restart, health-check; goes back to the previous image if it doesn't come up
fa update              what a deploy would bring in: a newer image, new commits, or nothing
fa backup [--to DIR]   consistent DB copy + all media as one tar, off the data volume (keeps 7)
fa restore FILE        put a backup back (SQLite installs)
fa env list|get|set|unset   edit .env safely; the file as it was stays beside it as .env.fa-backup
fa token rotate        new phone-app token
```

Which image a deploy downloads is `FA_VERSION` in the server's `.env`
(`fa env set FA_VERSION edge`): `latest` is the newest release and the default,
`edge` is every commit on `main`, and a version such as `0.2.0` stays on that
release. An install whose compose file only builds, or one with `FA_BUILD=1`,
is built on the server as before.

From the laptop: `fa --host tony@box deploy`. Set `FA_HOST` to skip the flag (`fa ui` picks it up as the first server).

## Backups on your own computer

The backup that matters is the one not on the server. From your laptop:

```sh
fa pull tony@box              # → ~/Backups/fartingaround/{db/site-<stamp>.db, objects/}
```

It streams a consistent database copy over ssh and `rsync`s the media folder
into a local mirror — media never changes once uploaded, so after the first
run only new photos move. Keeps the last 14 database dumps. Let Time Machine
(or whatever you use) take it from there. Run it by hand, or from launchd/cron
on the laptop.

`fa backup` on the server still exists for a quick on-box tarball before a
risky change; it's not a substitute for `pull`. Its tarballs go in a folder
beside the install (`<install>-backups`), never inside it: the checkout is what
`docker compose build` uploads, and tarballs in it rode along with every deploy.

## Automatic off-server backups (restic)

`fa backups` keeps encrypted, deduplicated snapshots somewhere other than the
server, on a schedule, and can put the site back from any of them. It drives
[restic](https://restic.net) through
[resticprofile](https://creativeprojects.github.io/resticprofile/); install both
on the server first (`fa backups show` prints the commands if they are missing).

```sh
fa backups setup --repo s3:https://nyc3.digitaloceanspaces.com/my-bucket/backups \
    --env AWS_ACCESS_KEY_ID=… --env AWS_SECRET_ACCESS_KEY=…
fa backups run                    # the first one copies everything
fa backups show                   # destination, schedule, how the last run went
fa backups set schedule 03:30     # daily, the server's clock; or: off
fa backups set keep daily=7 weekly=4 monthly=6
fa backups list
fa backups restore <id|latest>    # stops the site, swaps the data, starts it
```

The same things are on the **Backups** tab of `fa ui`: change the schedule and
what is kept, back up now, and restore a snapshot with one button and a confirm.

- **Anything restic can write to** works as the destination: S3-compatible
  storage, Backblaze B2, SFTP, a REST server, a mounted disk.
- **One setup per server.** The settings live in
  `~/.config/resticprofile/profiles.json`: a `base` profile (destination and
  keys) and one profile per site. fa manages only its own site's profile, so
  other sites on the box can share the repository and the password.
- **The password** is made by `setup` and kept in
  `~/.config/resticprofile/password.txt`. Copy it somewhere else. Without it
  nobody can read the backups, and the server is the only place it exists.
- **A snapshot** is a consistent copy of the database (taken by a hook just
  before restic runs) plus `objects/`.
- **A restore** fetches the snapshot beside the live data, checks it, then stops
  the site and swaps. What was there is moved to `<data>/.replaced-<time>`, not
  deleted (for Postgres, today's database is dumped there first). Delete that
  folder once you are happy.
- **The schedule** is a line in the user's crontab, written by resticprofile.
  No daemon.

## Where things are

- `<install>/.env` — the site's configuration (secrets; mode 0600)
- `<install>/.fa.json` — fa's own choices: proxy mode, domain, proxy container, backup dir
- `~/.config/resticprofile/` — the server's backup settings and repository password (`fa backups`), shared by every site on it
- `<install>/fa/` — compose overlays and Caddyfile fa generated (`caddy` / `tunnel` modes)
- `DATA_PATH` — the database (SQLite) and `objects/` (every photo, audio, video). This folder *is* the site.

## Development

```sh
go test ./...
go build -o fa .
./scripts/release.sh v0.1.0     # cross-compiles into dist/
```

Standard library only. Keep it that way unless something really earns a dependency.
