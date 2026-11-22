#!/usr/bin/env bash
# Deploy on the droplet: pull, rebuild, restart, check. Build-on-host, no
# registry — the old container keeps serving until the new image is built.
#
#   /home/tony/fartingaround.fun/scripts/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# This repo's own deploy key (GitHub deploy keys are per repository, and the
# host runs several sites). IdentitiesOnly stops ssh offering the other
# sites' keys first. Override with DEPLOY_KEY=... if it lives elsewhere.
DEPLOY_KEY="${DEPLOY_KEY:-$HOME/.ssh/fartingaround_deploy}"
if [ -f "$DEPLOY_KEY" ]; then
  export GIT_SSH_COMMAND="ssh -i $DEPLOY_KEY -o IdentitiesOnly=yes"
fi

git pull --ff-only

# The data bind mount (DATA_PATH in .env, default ./data): the container writes
# it as user `node` (uid 1000), and a directory docker creates on first start is
# owned by root.
DATA_PATH="$(grep -E '^DATA_PATH=' .env 2>/dev/null | cut -d= -f2-)"; DATA_PATH="${DATA_PATH:-./data}"
mkdir -p "$DATA_PATH"
[ "$(stat -c %u "$DATA_PATH")" = 1000 ] || chown -R 1000:1000 "$DATA_PATH"

docker compose up -d --build --remove-orphans

# wait for the site to answer on the debug port
PORT="$(grep -E '^DEBUG_PORT=' .env 2>/dev/null | cut -d= -f2)"; PORT="${PORT:-8406}"
for i in $(seq 1 30); do
  if curl -fs -o /dev/null "localhost:$PORT/rss.xml" 2>/dev/null; then
    echo "deployed: $(git rev-parse --short HEAD)"
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 2
done
echo "site did not come up — docker compose logs site" >&2
docker compose logs --tail=50 site >&2
exit 1
