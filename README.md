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
`DATABASE_PASSWORD`, `JWT_SECRET`, `GOOGLE_CLIENT_IDS`, `APPLE_BUNDLE_ID`, `CORS_ALLOWED_HOSTS`, `PORT`, `PUBLIC_BASE_URL`,
`ANDROID_PACKAGE`, `ANDROID_CERT_SHA256`, `IOS_TEAM_ID`, `PLAY_STORE_URL`, `APP_STORE_URL`, `CLIENT_MIN_VERSION`; stores and ads:
`GOOGLE_PLAY_PACKAGE`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, `APPSTORE_ISSUER_ID`, `APPSTORE_KEY_ID`, `APPSTORE_PRIVATE_KEY`,
`APPSTORE_ROOT_CERT_PATH`, `IAP_DEV_MODE`, `ADS_CLIENT_REWARDS`; ops: `ADMIN_TOKEN`, `SUPPORT_EMAIL`, `LEGAL_COMPANY`).
Production setup, monitoring, backups and the store checklist: `deploy/README.md`.

## API (v1)

All responses: `{"code", "data", "errors", "requestId"}`. Bearer JWT from `/auth/*`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/auth/guest` | `{deviceId}` → tokens; creates a guest account |
| POST | `/api/v1/auth/oauth/google`, `/apple` | `{idToken}` verified server-side; upgrades a guest in place when called with its bearer |
| POST | `/api/v1/auth/refresh`, `/logout`, `/logout-all` | refresh rotation / revoke |
| GET/PATCH/DELETE | `/api/v1/me`, `{locale}` | user + `hasClub`; the app reports its language (pushes are sent in it); DELETE erases the account (sign-in data, tokens, chat lines) and retires the club into a bot |
| POST | `/api/v1/auth/transfer-code`; `/auth/transfer` `{code, deviceId}` | move an account to another device with a one-time code (24 h) |
| GET/POST | `/api/v1/club/inbox`, `/club/inbox/read` | in-app inbox (matchday results, season end, accepted challenges, new league members); `GET /club` has `inboxUnread` |
| GET | `/api/v1/catalog/buildings` | building catalog (costs, timers, effects per level) |
| POST/GET | `/api/v1/club` | create / full state (buildings, timers, pending income, grid) |
| POST | `/api/v1/club/buildings` | place `{type,x,y,rotation}` |
| POST | `/api/v1/club/buildings/{id}/upgrade`, `/collect`, `/speedup`, `/move` `{x,y,rotation}`; DELETE | building actions (one builder; constructions queue on it) |
| POST | `/api/v1/club/builders` | hire the second builder for 250 gold (parallel construction) |
| GET | `/api/v1/club/athletes` | squad, active trainings, slots |
| POST | `/api/v1/club/academy/claim` `{sport?}` | new graduate every 8 h (football, or any open section) |
| POST | `/api/v1/club/trainings`, `/trainings/{id}/complete`, `/trainings/{id}/speedup` | train one attribute for 1 h; finish now for gold (1 per 10 min) |
| POST | `/api/v1/club/athletes/{id}/heal`, `/club/medical/speedup` | medical centre; end its cooldown for gold |
| DELETE | `/api/v1/club/athletes/{id}` | release an athlete (free; the section must still field a lineup) |
| GET/POST | `/api/v1/club/scouting`, `/club/scouting/{slot}/sign` | daily scouting report (needs an academy): prospect / prime / veteran from the club's sections; one free signing a day |
| GET/PUT | `/api/v1/club/lineup/{sport}` | starters, bench, tactics per section (`football` 11+7, `basketball` 5+5, `tennis` 1+1) |
| GET | `/api/v1/leagues/mine` | public league (8 clubs, same timezone bucket) + private leagues, with tables |
| GET | `/api/v1/leagues/{id}`, `/{id}/fixtures?round=` | table, next round, fixtures |
| POST | `/api/v1/leagues/private` `{name}`, `/private/join` `{code}`; DELETE `/private/{id}/leave` | private leagues (code, max 20) |
| GET | `/api/v1/club/fixtures` | next scheduled + last 5 played |
| GET | `/api/v1/club/history`, `/clubs/{id}/history` | finished seasons (position, movement, prizes) and trophies |
| GET | `/api/v1/matches/{id}` | fixture + full match result (events for highlights) |
| POST | `/api/v1/friendlies` `{opponentClubId?}` | instant friendly (3 per day), vs bot if omitted |
| GET | `/api/v1/clubs/{id}` | public club profile |
| POST | `/api/v1/club/daily/claim` | 7-day login calendar + streak |
| GET | `/api/v1/club/pass`; POST `/club/pass/tasks/{task}/claim`, `/club/pass/claim` `{eliteSport?}` | season pass: 3 daily tasks, 30 levels, free/premium tracks; pass cosmetics (levels 10/15/20/25, free 30) and an elite athlete at premium 30 |
| GET | `/api/v1/catalog/products`; POST `/purchases/verify` `{store, productId, token}` | gold packs + premium pass. `GOOGLE`: Play purchase token (`purchases.products.get`, order id = idempotency key); `APPLE`: transaction id (App Store Server API, production then sandbox; optional x5c chain check against Apple Root CA G3); `DEV` with `IAP_DEV_MODE=true`, token `dev:<order>` |
| GET | `/api/v1/catalog/coin-packs`; POST `/club/shop/coins/{pack}` | coins for gold (25 → 3 000, 100 → 13 000, 400 → 60 000) |
| GET | `/api/v1/catalog/cosmetics`; GET `/club/cosmetics`; POST `/club/cosmetics/{id}/buy`, `/club/cosmetics/equip` `{kind, itemId?}` | kits, emblem frames, goal celebrations, decor looks: shop (gold), season pass or achievements; equipped looks are on `ClubDto`/`ClubRefDto` (`kit`, `emblemFrame`/`frame`, `celebration`) |
| POST | `/api/v1/club/buildings` `{…, variant?}`, `/club/buildings/{id}/variant` `{itemId}` | decor look at placement / later (owned decor items) |
| GET | `/api/v1/club/achievements`; POST `/club/achievements/{id}/claim` | 14 tiered achievements (wins, sections, divisions, titles, builds, streak, invites…): gold per tier, some tiers give cosmetics |
| POST | `/api/v1/ads/reward` `{placement, buildingId?}` | client-reported reward (development, or `ADS_CLIENT_REWARDS=true`): `DOUBLE_INCOME` (4h cooldown), `FREE_SPEEDUP` (≤30 min, once a day) |
| GET | `/api/v1/ads/ssv` | AdMob server-side verification callback (signature checked with Google's keys; `user_id` = user id, `custom_data` = `PLACEMENT[:buildingId]`; idempotent per `transaction_id`) |
| GET/POST | `/api/v1/leagues/{id}/messages` `?before=&after=` / `{text}` | private-league chat (members only, 2 s / 20 per 5 min, masked; system lines `join|Name`, `leave|…`, `restart|n|winner`, `kick|…`) |
| POST/DELETE | `/api/v1/messages/{id}/report`, `/messages/{id}`; `/clubs/{id}/block` | moderation: 3 reports hide a line; author or creator deletes; blocks hide a club's lines |
| POST/DELETE/GET | `/api/v1/leagues/private/{id}/restart`, `/members/{clubId}`, `/history` | creator: new private season (table frozen into history), remove members |
| GET | `/api/v1/clubs/{id}/h2h`, `/clubs/{id}/grounds` | head-to-head record; another club's buildings (visit) |
| POST/GET | `/api/v1/challenges`, `/challenges/mine`, `/challenges/{code}`, `/challenges/{code}/accept` | one-off friendly by link (7 days, first taker plays; both clubs rewarded) |
| GET | `/l/{code}`, `/c/{code}`, `/.well-known/assetlinks.json`, `/.well-known/apple-app-site-association` | invite landing pages (5 languages, store links, clipboard hand-off) and app-link association |
| GET | `/api/v1/config` | public client config: base URL, link templates, store URLs, minimum client version |
| POST | `/api/v1/analytics/events` `{platform, appVersion, events:[{name, props?, at?}]}` | product events from the app (≤ 50 per batch; names `[a-z][a-z0-9_]`, ≤ 12 short string props; queued events may be up to 3 days old) |
| GET | `/privacy`, `/terms`, `/delete-account` (`?lang=ru`) | store-required public pages, English/Russian (contact `SUPPORT_EMAIL`, publisher `LEGAL_COMPANY`) |
| GET/POST | `/metrics`, `/admin/metrics?days=`, `/admin/stats`, `/admin/clubs?q=`, `/admin/clubs/{id}`, `/admin/clubs/{id}/grant`, `/admin/scheduler/run` | ops and support with `X-Admin-Token: $ADMIN_TOKEN` — see `deploy/README.md` |
| POST/DELETE | `/api/v1/push-tokens` `{token, platform}` | FCM tokens; matchday result pushes via FCM HTTP v1 (`FCM_SERVICE_ACCOUNT_JSON`, `FCM_PROJECT_ID`) |
| POST | `/api/v1/dev/matchday/run` | dev only (`KTOR_DEVELOPMENT=true`): run the scheduler tick now |

Monetization (spec §6): gold buys speed-ups (buildings, trainings, medical cooldown), the second builder, coin packs and cosmetics;
athletes, attributes, reputation and league places are never sold. `GET /club` carries `ads` (double-income window/cooldown, today's
free speed-up). Store products to create: `gold_small` (100), `gold_medium` (550), `gold_large` (1200), `gold_huge` (2600) — consumables,
`season_pass` — consumable bought once per season. AdMob SSV callback URL: `https://<host>/api/v1/ads/ssv`.

Sections: football always; a basketball court (club level 3) or tennis court (level 5) opens that section with a starter squad
when its construction finishes; the venue level caps that section's attributes. A matchday is one opponent across all sections either
club fields; a section only one club has is a walkover (3:0 / 20:0 / 2:0). Table points = sum of section points (3/1/0), so a three-sport
club can take 9 a matchday. `GET /club` returns `sections`; fixtures carry `homePoints/awayPoints` and per-section scores; `GET /matches/{id}`
returns `sections[]` with each sport's full result (`result` stays the football match).

Seasons & divisions: 7 divisions (1 Sunday League … 7 Legends League). After round 28 the league is closed (`status=FINISHED`):
final table → prizes by position × division, a `club_seasons` history row per member, trophies (title, promotion), top 2 promoted,
bottom 2 relegated, `clubs.season_no` + 1 (keys the season pass), and every human is placed in next season's league of their new
division (season starts the next day; freed bots of that division are reused). Matchday coin rewards grow 25% per division.

Careers: every athlete has a potential (the attribute average training can reach); at season end everyone ages a year —
young players grow, veterans decline, 34+ may retire (replaced by a youth prospect; retired rows stay for old highlights,
inbox `RETIREMENTS`). League matchdays move morale (+5 win / +1 draw / −4 loss for starters, −2 for those left out; the
engine weighs morale ±10%). Squad limits: football 25, basketball 14, tennis 6.

Matchday: a scheduler tick every 5 minutes plays every public league whose next round (20:00 league-local, one round per day, 28 rounds per season)
is due; empty slots are filled with bot clubs when the season starts; results are immutable; rewards: win 300 coins / +12 reputation,
draw 150 / +5, loss 60 / +1; starters gain fatigue, injuries from the engine last 2 days. Single-instance scheduler (in-process lock).

Analytics: every authenticated request marks the user active for the UTC day (`user_days`, one insert per user and day);
the app sends product events in batches (`analytics_events`; the server adds `club_created`, `account_deleted`, `admin_grant`).
`GET /admin/metrics` reports the spec's launch KPIs — D1/D7 retention by signup cohort (only complete days count), share of
weekly-active players in a private league, k-factor (new players who joined through an invite ÷ active players) — with their
targets/thresholds, plus DAU/WAU/MAU, purchases, ad rewards and top events. Deleting an account erases that player's events.

Languages: en, es, pt, ru, uk. Error messages are answered in the request's `Accept-Language` (catalog
`server/src/main/resources/i18n/messages.tsv`, keyed by the English template: `ConflictException("Requires club level {0}", n)`;
`ServerI18nTest` fails when a thrown message has no translations); pushes use the user's stored locale.

Server rules: all timers are absolute server instants; finished timers are finalised on read; clients send commands, never state.
Every mutating use case runs in one transaction (`Tx`) and locks the club row (`SELECT … FOR UPDATE`) before touching currencies,
so parallel requests can neither double-grant nor overspend (`ConcurrencyIT`).

Integration tests (real PostgreSQL, skipped when the variable is unset):

    TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/matchdayclub_test TEST_DATABASE_USER=$USER ./gradlew :server:test

## match-engine rules

- **Determinism.** Same lineups + tactics + sport profile + seed ⇒ identical `MatchResult`. Only `Rng` (SplittableRandom) may be a randomness source.
- **One engine, many sports.** Sport differences live in `SportProfile` (possessions, chance rate, conversion, duration, subs). The simulator never branches on sport id.
- **Events are the API.** `MatchResult.events` is what the client plays back as highlights; every event has a minute, type, team and optional athlete.
- **Tuning knobs.** `SportProfile.baseChanceRate`, `baseConversion`, `homeAdvantage` and `Possession.CHANCE_EXP` / `CONVERSION_EXP`. Change them only together with the balance table and `BalanceTest` bounds.

Sports: `FOOTBALL` (possession-based, 90'), `BASKETBALL` (alternating possessions, 2/3-point shots, overtime, no draws),
`TENNIS` (`SimMode.SETS`: best of three, serve-point model, tiebreak at 6-6). Run the balance table for all three with
`./gradlew :match-engine:run --args=3000`.

### Adding a sport

1. Add a `SportProfile` constant in `model/Sport.kt`.
2. Add a scenario row in `balance/Balance.kt` `main`, run the table, tune the profile.
3. Add a `BalanceTest` case with the sport's target ranges.
