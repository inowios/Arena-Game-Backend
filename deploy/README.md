# Deploying and running the backend

One VPS with Docker is enough for launch (2 vCPU / 4 GB comfortably runs the app, PostgreSQL and Caddy).
The matchday scheduler runs inside the app and assumes **a single app instance**.

## First deploy

```bash
git clone … /opt/arenacity && cd /opt/arenacity
cp .env.example .env && $EDITOR .env        # JWT_SECRET, DB password, DOMAIN, PUBLIC_BASE_URL, ADMIN_TOKEN, …
docker compose --profile tls up -d --build  # app + postgres + Caddy (HTTPS on 443)
curl -s https://$DOMAIN/health              # {"code":200,"data":"ok"}
```

Without the `tls` profile the app listens on `:8080` (put your own TLS proxy in front and pass `X-Real-IP`;
rate limiting keys on it). With Caddy, set `APP_BIND=127.0.0.1` so the app port is not public.

Updates: CI builds `ghcr.io/<owner>/arena-game-backend:prod` on every push to `main`; on the server

```bash
APP_IMAGE=ghcr.io/<owner>/arena-game-backend:prod docker compose pull app && docker compose up -d app
```

Database migrations (Flyway, `server/src/main/resources/db/migration`) run on startup.

## Configuration (.env)

| Variable | Needed for |
|---|---|
| `JWT_SECRET`, `DATABASE_PASSWORD` | always |
| `DOMAIN`, `APP_BIND=127.0.0.1` | HTTPS with the bundled Caddy |
| `PUBLIC_BASE_URL` | invite links `/l/{code}`, `/c/{code}`, app links, legal pages; the app reads it from `/api/v1/config` |
| `SUPPORT_EMAIL`, `LEGAL_COMPANY` | `/privacy`, `/terms`, `/delete-account` (store listings require these URLs) |
| `GOOGLE_CLIENT_IDS`, `APPLE_BUNDLE_ID` | Google / Apple sign-in |
| `ANDROID_CERT_SHA256`, `IOS_TEAM_ID`, `PLAY_STORE_URL`, `APP_STORE_URL` | Android App Links / iOS Universal Links, store buttons on landing pages |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Google Play purchase verification (service account with *View financial data*) |
| `APPSTORE_ISSUER_ID`, `APPSTORE_KEY_ID`, `APPSTORE_PRIVATE_KEY` (+ optional `APPSTORE_ROOT_CERT_PATH`) | App Store purchase verification (App Store Server API key) |
| `FCM_SERVICE_ACCOUNT_JSON`, `FCM_PROJECT_ID` | push notifications |
| `ADMIN_TOKEN` (≥ 16 chars, e.g. `openssl rand -hex 24`) | `/admin/*` and `/metrics`; empty disables them |
| `IAP_DEV_MODE=false`, `ADS_CLIENT_REWARDS=false`, `KTOR_DEVELOPMENT=false` | must stay false in production |
| `CLIENT_MIN_VERSION` | force-update older apps |

Files such as the Apple root certificate go to `./secrets/` (mounted read-only at `/app/secrets`).

AdMob: set the rewarded ad unit's **server-side verification URL** to `https://$DOMAIN/api/v1/ads/ssv`.

## Store checklist

- Privacy policy URL: `https://$DOMAIN/privacy` · Terms: `/terms` · Account deletion URL (Google Play): `/delete-account`.
- Play Console data safety / App Store privacy labels: device id, account id + e-mail (Google/Apple sign-in), game content,
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

Monitoring: `deploy/prometheus.yml` (scrape config) and `deploy/alerts.yml` (API down, scheduler stale/failing, 5xx rate,
p95 latency, push and purchase failures). Any hosted Prometheus/Grafana works; a stale `scheduler_last_tick_timestamp_seconds`
is the most important alert — matchdays, season ends and cups stop with it.

Logs: `docker compose logs -f app` (rotated by Docker, 5 × 20 MB). Every response carries `requestId`, also in the log line.

Backups: `deploy/backup.sh` (daily `pg_dump`, keeps 14; restore command inside the script). Copy `backups/` off the server.

Scheduler after downtime: missed rounds are played on the next pass in order (league rounds, season ends, cup draws and
rounds), so a restart catches up by itself; `POST /admin/scheduler/run` triggers it immediately.
