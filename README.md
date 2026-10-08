# Arena Game Backend (Matchday Club)

Kotlin backend for the Matchday Club mobile game. Design spec and plans live in
`../Arena-Game-Mobile/docs/superpowers/` (specs) and `docs/superpowers/plans/` (plans for this repo).

## Modules

| Module | Purpose |
|---|---|
| `match-engine` | Pure Kotlin, deterministic match simulator. No Ktor/DB dependencies. |
| `server` | Ktor 3 API: auth, clubs, buildings, athletes, lineups, public/private leagues, matchday scheduler, friendlies. |

## Commands

    ./gradlew :match-engine:test              # unit + statistical balance tests
    ./gradlew :match-engine:run --args=3000   # balance table (3000 matches per scenario)

    ./gradlew :server:test                    # unit tests (fakes, no DB)
    ./gradlew :server:run                     # local server on :8080 (needs PostgreSQL `matchdayclub`)
    ./scripts/smoke.sh                        # end-to-end smoke against a running server

Config: `server/src/main/resources/application.conf` (HOCON, `${?ENV}` overrides: `DATABASE_URL`, `DATABASE_USER`,
`DATABASE_PASSWORD`, `JWT_SECRET`, `GOOGLE_CLIENT_IDS`, `APPLE_BUNDLE_ID`, `CORS_ALLOWED_HOSTS`, `PORT`).

## API (v1)

All responses: `{"code", "data", "errors", "requestId"}`. Bearer JWT from `/auth/*`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/auth/guest` | `{deviceId}` → tokens; creates a guest account |
| POST | `/api/v1/auth/oauth/google`, `/apple` | `{idToken}` verified server-side; upgrades a guest in place when called with its bearer |
| POST | `/api/v1/auth/refresh`, `/logout`, `/logout-all` | refresh rotation / revoke |
| GET | `/api/v1/me` | user + `hasClub` |
| GET | `/api/v1/catalog/buildings` | building catalog (costs, timers, effects per level) |
| POST/GET | `/api/v1/club` | create / full state (buildings, timers, pending income, grid) |
| POST | `/api/v1/club/buildings` | place `{type,x,y,rotation}` |
| POST | `/api/v1/club/buildings/{id}/upgrade`, `/collect`, `/speedup`; DELETE | building actions |
| GET | `/api/v1/club/athletes` | squad, active trainings, slots |
| POST | `/api/v1/club/academy/claim` | new graduate every 8 h |
| POST | `/api/v1/club/trainings`, `/trainings/{id}/complete` | train one attribute for 1 h |
| POST | `/api/v1/club/athletes/{id}/heal` | medical centre |
| GET/PUT | `/api/v1/club/lineup/{sport}` | starters, bench, tactics |
| GET | `/api/v1/leagues/mine` | public league (8 clubs, same timezone bucket) + private leagues, with tables |
| GET | `/api/v1/leagues/{id}`, `/{id}/fixtures?round=` | table, next round, fixtures |
| POST | `/api/v1/leagues/private` `{name}`, `/private/join` `{code}`; DELETE `/private/{id}/leave` | private leagues (code, max 20) |
| GET | `/api/v1/club/fixtures` | next scheduled + last 5 played |
| GET | `/api/v1/matches/{id}` | fixture + full match result (events for highlights) |
| POST | `/api/v1/friendlies` `{opponentClubId?}` | instant friendly (3 per day), vs bot if omitted |
| GET | `/api/v1/clubs/{id}` | public club profile |
| POST | `/api/v1/club/daily/claim` | 7-day login calendar + streak |
| GET | `/api/v1/club/pass`; POST `/club/pass/tasks/{task}/claim`, `/club/pass/claim` | season pass: 3 daily tasks, 30 levels, free/premium tracks |
| GET | `/api/v1/catalog/products`; POST `/purchases/verify` `{store, productId, token}` | gold/coins/premium pass; receipts verified server-side (dev verifier with `IAP_DEV_MODE=true`, token `dev:<order>`) |
| POST | `/api/v1/ads/reward` `{placement, buildingId?}` | rewarded ads: `DOUBLE_INCOME` (4h cooldown), `FREE_SPEEDUP` (≤30 min, once a day) |
| POST/DELETE | `/api/v1/push-tokens` `{token, platform}` | FCM tokens; matchday result pushes via FCM HTTP v1 (`FCM_SERVICE_ACCOUNT_JSON`, `FCM_PROJECT_ID`) |
| POST | `/api/v1/dev/matchday/run` | dev only (`KTOR_DEVELOPMENT=true`): run the scheduler tick now |

Not yet implemented: real Google Play / App Store receipt verifiers (interface `ReceiptVerifier` is in place; production needs store credentials), rewarded-ad server-side verification (SSV).

Matchday: a scheduler tick every 5 minutes plays every public league whose next round (20:00 league-local, one round per day, 28 rounds per season)
is due; empty slots are filled with bot clubs when the season starts; results are immutable; rewards: win 300 coins / +12 reputation,
draw 150 / +5, loss 60 / +1; starters gain fatigue, injuries from the engine last 2 days. Single-instance scheduler (in-process lock).

Server rules: all timers are absolute server instants; finished timers are finalised on read; clients send commands, never state.

## match-engine rules

- **Determinism.** Same lineups + tactics + sport profile + seed ⇒ identical `MatchResult`. Only `Rng` (SplittableRandom) may be a randomness source.
- **One engine, many sports.** Sport differences live in `SportProfile` (possessions, chance rate, conversion, duration, subs). The simulator never branches on sport id.
- **Events are the API.** `MatchResult.events` is what the client plays back as highlights; every event has a minute, type, team and optional athlete.
- **Tuning knobs.** `SportProfile.baseChanceRate`, `baseConversion`, `homeAdvantage` and `Possession.CHANCE_EXP` / `CONVERSION_EXP`. Change them only together with the balance table and `BalanceTest` bounds.

### Adding a sport

1. Add a `SportProfile` constant in `model/Sport.kt`.
2. Add a scenario row in `balance/Balance.kt` `main`, run the table, tune the profile.
3. Add a `BalanceTest` case with the sport's target ranges.
