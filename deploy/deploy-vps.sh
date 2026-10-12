#!/usr/bin/env bash
# Update Matchday Club on the shared VPS from this Mac:
#   deploy/deploy-vps.sh            # waits for CI on the pushed HEAD (it builds ghcr.io/inowios/arena-game-backend:<sha>), deploys that
#   deploy/deploy-vps.sh <sha>      # deploys an image CI already built
# The first start is deploy/vps/setup.sh (see the README). Migrations run when the app boots.
set -euo pipefail
cd "$(dirname "$0")/.."
VPS="${MDC_VPS:-arena-prod}"
DIR=/opt/arenacity/prod
DOMAIN=arenacity.arenaproplay.com
REPO=inowios/Arena-Game-Backend

SHA="${1:-}"
if [ -z "$SHA" ]; then
  [ -z "$(git status --porcelain)" ] || { echo "✗ uncommitted changes — commit and push first"; exit 1; }
  SHA="$(git rev-parse HEAD)"
  git fetch -q origin main
  [ "$(git rev-parse origin/main)" = "$SHA" ] || { echo "✗ HEAD is not origin/main — push first"; exit 1; }
  RUN="$(gh run list -R "$REPO" --commit "$SHA" --workflow ci --json databaseId -q '.[0].databaseId')"
  [ -n "$RUN" ] || { echo "✗ no CI run for $SHA yet — wait a moment and retry"; exit 1; }
  echo "▸ waiting for CI run $RUN ($SHA)…"
  gh run watch -R "$REPO" "$RUN" --exit-status >/dev/null || { echo "✗ CI failed: gh run view -R $REPO $RUN --log-failed"; exit 1; }
fi

echo "▸ deploying $SHA on $VPS"
scp -q deploy/vps/docker-compose.prod.yml "$VPS:$DIR/docker-compose.yml"
GHCR_TOKEN="$(gh auth token)"
ssh "$VPS" "echo '$GHCR_TOKEN' | docker login ghcr.io -u inowios --password-stdin >/dev/null && cd $DIR \
  && MDC_TAG=$SHA docker compose pull app && MDC_TAG=$SHA docker compose up -d app && docker image prune -f >/dev/null"

echo "▸ health"
for i in $(seq 1 30); do curl -sf -m 5 "https://$DOMAIN/health" >/dev/null && break; sleep 3; done
curl -sf -m 5 "https://$DOMAIN/health" && echo || { echo "✗ https://$DOMAIN/health does not answer — ssh $VPS 'cd $DIR && docker compose logs --tail=80 app'"; exit 1; }
echo "✓ Matchday Club $SHA is live on https://$DOMAIN"
