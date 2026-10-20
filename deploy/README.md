# Deploying and running the backend

## Production: the shared VPS (arenacity.arenaproplay.com)

Arena City runs on the same VPS and domain as Arena Pro Play (and qrkontaq), each project fully separate —
see `~/StudioProjects/ops` (backups, runbook). Files: `deploy/vps/`.

| | |
|---|---|
| URL | `https://arenacity.arenaproplay.com` (Cloudflare, account B; the same origin certificate as `api.arenaproplay.com`) |
| server dir | `/opt/arenacity/prod` (`docker-compose.yml`, `.env.prod`, `secrets/`) |
| containers | `arenacity-prod` on `127.0.0.1:8084` (qrkontaq 8080, Arena dev 8081, Arena prod 8082, letsplay 8083), `arenacity-db-prod` (no published port) |
| volume | `arenacity-prod-pgdata`; nightly off-site dumps by `/opt/ops/backup.sh` |
| image | `ghcr.io/inowios/arena-game-backend:<sha>` / `:prod`, built by CI on every push to `main` |

**First start** (once):
1. Cloudflare (arenaproplay.com) → DNS → an `A` record `arenacity` → the VPS IP, proxied (orange cloud), like `api`.
2. From this repo:
   ```bash
   ssh arena-prod 'mkdir -p /opt/arenacity/prod'
   scp -r deploy/vps/* arena-prod:/opt/arenacity/prod/
   ssh arena-prod 'bash /opt/arenacity/prod/setup.sh'
   ```
   `setup.sh` creates `.env.prod` with random secrets (never overwrites it), checks that port 8084 is free and that the
   origin certificate covers the subdomain, starts the containers, adds the nginx site (`nginx -t` before the reload;
   the other sites are not touched) and joins the nightly backups. Afterwards fill in `.env.prod` as needed
   (`SUPPORT_EMAIL`, sign-in, purchases, push) and `docker compose up -d app`.
3. `curl https://arenacity.arenaproplay.com/health` → `{"code":200,"data":"ok",…}`

**Updates:** push to `main`, then `deploy/deploy-vps.sh` (waits for CI on that commit, pulls its image on the VPS,
restarts the app — migrations run on boot — and checks `/health`). Roll back: `deploy/deploy-vps.sh <older sha>`.

**The app:** `SERVER=prod unity/scripts/run_sim.sh device` (Arena-Game-Mobile) builds the iPhone app against production.

## Configuration (.env.prod)

The VPS compose file reads `/opt/arenacity/prod/.env.prod`; `docker-compose.yml` in this repo is the same stack for a local
run (`docker compose up -d`, the app on `:8080`, PostgreSQL inside; `Caddyfile` is only used with `--profile tls`).
Database migrations (Flyway, `server/src/main/resources/db/migration`) run on startup. The matchday scheduler runs
inside the app and assumes **a single app instance**.

| Variable | Needed for |
|---|---|
| `JWT_SECRET`, `DATABASE_PASSWORD` | always |
| `PUBLIC_BASE_URL` | invite links `/l/{code}`, `/c/{code}`, app links, legal pages; the app reads it from `/api/v1/config` |
| `SUPPORT_EMAIL`, `LEGAL_COMPANY` | `/privacy`, `/terms`, `/delete-account` (store listings require these URLs) |
| `ANDROID_CERT_SHA256`, `IOS_TEAM_ID`, `PLAY_STORE_URL`, `APP_STORE_URL` | Android App Links / iOS Universal Links, store buttons on landing pages |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Google Play purchase verification (service account with *View financial data*) |
| `APPSTORE_ISSUER_ID`, `APPSTORE_KEY_ID`, `APPSTORE_PRIVATE_KEY` (+ optional `APPSTORE_ROOT_CERT_PATH`) | App Store purchase verification (App Store Server API key) |
| `FCM_SERVICE_ACCOUNT_JSON`, `FCM_PROJECT_ID` | push notifications |
| `ADMIN_TOKEN` (≥ 16 chars, e.g. `openssl rand -hex 24`) | `/admin/*` and `/metrics`; empty disables them |
| `IAP_DEV_MODE=false`, `KTOR_DEVELOPMENT=false` | must stay false in production |
| `CLIENT_MIN_VERSION` | force-update older apps, only once a client that enforces it is in the stores |

Files such as the Apple root certificate go to `./secrets/` (mounted read-only at `/app/secrets`).

AdMob: set the rewarded ad unit's **server-side verification URL** to `https://arenacity.arenaproplay.com/api/v1/ads/ssv`.

## Store checklist

- Privacy policy URL: `https://arenacity.arenaproplay.com/privacy` · Terms: `/terms` · Account deletion URL (Google Play): `/delete-account`.
- Play Console data safety / App Store privacy labels: device id, game content,
  purchase history, app interactions (analytics events), advertising id via AdMob, crash/diagnostics only if you add Crashlytics.
- `/.well-known/assetlinks.json` and `/.well-known/apple-app-site-association` must be served from `PUBLIC_BASE_URL`'s host.

## Operations

All ops endpoints take `X-Admin-Token: $ADMIN_TOKEN` (or `Authorization: Bearer $ADMIN_TOKEN`).

| Endpoint | |
|---|---|
| `GET /metrics` | Prometheus metrics: requests by route/status, latency histogram, scheduler ticks/failures and last tick time, matches played, cup rounds, pushes, purchases, ad rewards, JVM |
| `GET /admin/metrics?days=30` | launch KPIs vs the design targets: D1/D7 retention, private-league share, k-factor; DAU/WAU/MAU, cohorts, purchases, ad rewards, top events (`scripts/metrics.sh` prints it) |
| `GET /admin/stats` | row counts, scheduler state (last tick, errors), version |
| `GET /admin/clubs?q=name` · `GET /admin/clubs/{id}` | support lookup: club, owner, purchases, last active day |
| `POST /admin/clubs/{id}/grant` `{"coins":500,"gold":20,"reason":"…"}` | compensation; the player sees it in the inbox; logged as `admin_grant` |
| `POST /admin/scheduler/run` | run one scheduler pass now (after downtime) |

Monitoring: `deploy/prometheus.yml` (scrape config for `arenacity.arenaproplay.com`) and `deploy/alerts.yml` (API down, scheduler stale/failing, 5xx rate,
p95 latency, push and purchase failures). Any hosted Prometheus/Grafana works; a stale `scheduler_last_tick_timestamp_seconds`
is the most important alert — matchdays, season ends and cups stop with it.

Logs: `docker compose logs -f app` (rotated by Docker, 5 × 20 MB). Every response carries `requestId`, also in the log line.

Backups: the VPS's nightly off-site dumps (`/opt/ops/backup.sh`, see `~/StudioProjects/ops`); the volume `arenacity-prod-pgdata` holds the live data.

Scheduler after downtime: missed rounds are played on the next pass in order (league rounds, season ends, cup draws and
rounds), so a restart catches up by itself; `POST /admin/scheduler/run` triggers it immediately.
