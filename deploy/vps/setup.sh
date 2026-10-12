#!/usr/bin/env bash
# First start of Arena City on the shared VPS (run as root ON the server, once):
#   ssh arena-prod 'mkdir -p /opt/arenacity/prod'
#   scp -r deploy/vps/* arena-prod:/opt/arenacity/prod/
#   ssh arena-prod 'bash /opt/arenacity/prod/setup.sh'
# Touches nothing of the other projects: its own dir, compose project, containers, volume, port 8083 and nginx site.
# Re-running is safe (keeps .env.prod and the database).
set -euo pipefail
cd "$(dirname "$0")"
DOMAIN=arenacity.arenaproplay.com
PORT=8083
say() { echo "▸ $*"; }; die() { echo "✗ $*" >&2; exit 1; }

command -v docker >/dev/null || die "no docker"
command -v nginx >/dev/null || die "no nginx"

# the port must be free (or already ours)
if ss -ltn "sport = :$PORT" | grep -q LISTEN && ! docker ps --format '{{.Names}}' | grep -qx arenacity-prod; then
  die "127.0.0.1:$PORT is taken by something else — pick another port in docker-compose.prod.yml and nginx/arenacity.conf"
fi

# secrets: generated once, never overwritten
if [ ! -f .env.prod ]; then
  say "creating .env.prod with random secrets"
  pw="$(openssl rand -hex 24)"
  sed -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$pw/" -e "s/^DATABASE_PASSWORD=.*/DATABASE_PASSWORD=$pw/" \
      -e "s/^JWT_SECRET=.*/JWT_SECRET=$(openssl rand -hex 48)/" -e "s/^ADMIN_TOKEN=.*/ADMIN_TOKEN=$(openssl rand -hex 24)/" \
      env.prod.example > .env.prod
  chmod 600 .env.prod
fi
mkdir -p secrets

# the Cloudflare origin certificate must name the subdomain
if ! openssl x509 -in /etc/ssl/cloudflare-cert.pem -noout -ext subjectAltName 2>/dev/null | grep -qE "\*\.arenaproplay\.com|$DOMAIN"; then
  echo "⚠ /etc/ssl/cloudflare-cert.pem does not cover $DOMAIN — make a new origin certificate for *.arenaproplay.com in Cloudflare"
fi

cp docker-compose.prod.yml docker-compose.yml   # docker compose finds it without -f
say "starting the database and the app"
docker compose pull
docker compose up -d
for i in $(seq 1 60); do curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 2; done
curl -sf "http://127.0.0.1:$PORT/health" >/dev/null || { docker compose logs --tail=60 app; die "the app did not answer on :$PORT"; }
say "app is up on 127.0.0.1:$PORT"

say "nginx site"
cp nginx/arenacity.conf /etc/nginx/sites-available/arenacity.conf
ln -sf /etc/nginx/sites-available/arenacity.conf /etc/nginx/sites-enabled/arenacity.conf
nginx -t || { rm -f /etc/nginx/sites-enabled/arenacity.conf; die "nginx config test failed — the site was removed again, nothing reloaded"; }
systemctl reload nginx

# nightly off-site backups (shared ops toolkit)
if [ -f /opt/ops/projects.conf ] && ! grep -q '^arenacity|' /opt/ops/projects.conf; then
  echo 'arenacity|arenacity-db-prod|' >> /opt/ops/projects.conf
  say "added to /opt/ops/projects.conf (nightly backups)"
fi

say "done. Check from anywhere: curl https://$DOMAIN/health"
