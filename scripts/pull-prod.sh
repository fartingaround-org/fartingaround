#!/usr/bin/env bash
# Load the live site's content into this local copy, for testing against
# real stories. Run it on your laptop, from the repo:
#
#   scripts/pull-prod.sh tony@your-droplet            # database + media
#   scripts/pull-prod.sh tony@your-droplet --no-media # database only
#
# (or set FA_HOST once and leave the argument off.)
#
# What it does:
#   1. runs the export half of scripts/db-dump.mjs inside the live container,
#      over ssh, and saves the result to data/prod-dump.jsonl
#   2. replaces the local database's stories, comments, contacts and settings
#      with it (a SQLite database is backed up beside itself first)
#   3. copies the media folder down with rsync — only what is new or changed,
#      so the first run is slow (it is every photo) and later runs are quick
#
# It only READS from the server. Sign-in sessions, links and passkeys are not
# copied. The dump holds private stories and your contact book, so it stays
# in data/, which git ignores.
#
# Stop `pnpm dev` first, and start it again afterwards.
#
# Overrides: FA_REMOTE_DIR (the checkout on the server, default
# /home/tony/fartingaround.fun), FA_CONTAINER (default fartingaround-site).
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${FA_HOST:-}"
MEDIA=1
for arg in "$@"; do
  case "$arg" in
    --no-media) MEDIA=0 ;;
    -*) echo "unknown option: $arg" >&2; exit 2 ;;
    *) HOST="$arg" ;;
  esac
done
if [ -z "$HOST" ]; then
  echo "usage: scripts/pull-prod.sh user@host [--no-media]   (or set FA_HOST)" >&2
  exit 2
fi
REMOTE_DIR="${FA_REMOTE_DIR:-/home/tony/fartingaround.fun}"
CONTAINER="${FA_CONTAINER:-fartingaround-site}"

# Same settings pnpm dev uses, so we load into the database it will open.
if [ -f .env ]; then set -a; . ./.env; set +a; fi
DATA_DIR="${DATA_DIR:-./data}"
mkdir -p "$DATA_DIR"
DUMP="$DATA_DIR/prod-dump.jsonl"

echo "→ exporting the database from $HOST"
ssh "$HOST" "docker exec -i $CONTAINER node --input-type=module - export" < scripts/db-dump.mjs > "$DUMP.part"
if [ ! -s "$DUMP.part" ]; then
  echo "the export came back empty — nothing was changed locally" >&2
  exit 1
fi
mv "$DUMP.part" "$DUMP"

echo "→ loading it into the local database"
node scripts/db-dump.mjs import "$DUMP" --yes

if [ "$MEDIA" = 1 ]; then
  # The server keeps its data folder wherever DATA_PATH in its .env says.
  REMOTE_DATA="$(ssh "$HOST" "cd $REMOTE_DIR && (grep -E '^DATA_PATH=' .env 2>/dev/null | cut -d= -f2-) || true")"
  REMOTE_DATA="${REMOTE_DATA:-./data}"
  case "$REMOTE_DATA" in /*) ;; *) REMOTE_DATA="$REMOTE_DIR/$REMOTE_DATA" ;; esac
  echo "→ copying media from $HOST:$REMOTE_DATA/objects/"
  mkdir -p "$DATA_DIR/objects"
  # Resized copies under images/*/cache/ are derived; the local site remakes
  # them on demand, so they are left behind.
  rsync -a --partial --exclude='/images/*/cache/' "$HOST:$REMOTE_DATA/objects/" "$DATA_DIR/objects/"
fi

echo "done. Start pnpm dev again."
