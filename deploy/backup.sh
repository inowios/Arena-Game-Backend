#!/usr/bin/env bash
# Daily PostgreSQL dump next to docker-compose.yml (keeps the last 14). Example cron on the server:
#   30 3 * * * cd /opt/arenacity && deploy/backup.sh >> backups/backup.log 2>&1
# Restore into a stopped app:
#   docker compose stop app && docker compose exec -T postgres pg_restore -U arena -d arenacity --clean --if-exists < backups/arenacity-YYYY-MM-DD.dump && docker compose start app
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a
mkdir -p backups
out="backups/arenacity-$(date +%F).dump"
docker compose exec -T postgres pg_dump -U "${DATABASE_USER:-arena}" -Fc "${DATABASE_NAME:-arenacity}" > "$out.tmp"
mv "$out.tmp" "$out"
ls -1t backups/arenacity-*.dump | tail -n +15 | xargs -r rm --
echo "$(date -Is) backup $out ($(du -h "$out" | cut -f1))"
