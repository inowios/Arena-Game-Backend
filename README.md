# Arena Game Backend (Arena City)

Kotlin backend for the Arena City mobile game. Design spec and plans live in
`../Arena-Game-Mobile/docs/superpowers/` (specs) and `docs/superpowers/plans/` (plans for this repo).

## Modules

| Module | Purpose |
|---|---|
| `match-engine` | Pure Kotlin, deterministic match simulator. No Ktor/DB dependencies. |
| `server` | Ktor 3 API: auth, clubs and the five islands map, heroes, the story «Іскра», the Arena match, public/private leagues, matchday scheduler, friendlies, cups, alliances. |

## Commands

    ./gradlew :match-engine:test              # unit + statistical balance tests
    ./gradlew :match-engine:run --args=3000   # balance table (3000 matches per scenario)

    ./gradlew :server:test                    # unit tests (fakes, no DB)
    ./gradlew :server:run                     # local server on :8080 (needs PostgreSQL `arenacity`)
    ./scripts/smoke.sh                        # end-to-end smoke against a running server

Config: `server/src/main/resources/application.conf` (HOCON, `${?ENV}` overrides: `DATABASE_URL`, `DATABASE_USER`,
`DATABASE_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_HOSTS`, `PORT`, `PUBLIC_BASE_URL`,
`ANDROID_PACKAGE`, `ANDROID_CERT_SHA256`, `IOS_TEAM_ID`, `PLAY_STORE_URL`, `APP_STORE_URL`, `CLIENT_MIN_VERSION`; stores and ads:
`GOOGLE_PLAY_PACKAGE`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, `APPSTORE_ISSUER_ID`, `APPSTORE_KEY_ID`, `APPSTORE_PRIVATE_KEY`,
`APPSTORE_ROOT_CERT_PATH`, `IAP_DEV_MODE`; ops: `ADMIN_TOKEN`, `SUPPORT_EMAIL`, `LEGAL_COMPANY`).
Production setup, monitoring, backups and the store checklist: `deploy/README.md`.

## API (v1)

All responses: `{"code", "data", "errors", "requestId"}`. Bearer JWT from `/auth/*`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/auth/guest` | `{deviceId}` → tokens; creates a guest account |
| POST | `/api/v1/auth/refresh` | refresh rotation (Google/Apple sign-in and logout went on 20 Oct 2026: guests and device transfer only; see git history when accounts come back) |
| GET/PATCH/DELETE | `/api/v1/me`, `{locale}` | user + `hasClub`; the app reports its language (pushes are sent in it); DELETE erases the account (sign-in data, tokens, chat lines) and retires the club into a bot |
| POST | `/api/v1/auth/transfer-code`; `/auth/transfer` `{code, deviceId}` | move an account to another device with a one-time code (24 h) |
| GET/POST | `/api/v1/club/inbox`, `/club/inbox/read` | in-app inbox (matchday results, season end, accepted challenges, new league members); `GET /club` has `inboxUnread` |
| GET | `/api/v1/catalog/spots` | the five islands map's ten spots `[{id, island, cost, people?}]`: `stadium` (always open), `academy` 3 000, `hospital` 6 000, `mall` 12 000, `lighthouse` 25 000, `marina` 50 000 (main island, `island` = `main`); `android_stadium`, `stone_arena`, `beast_dome`, `shadow_ruins` (one per people's island, `cost` = the island's price; they open with the island) |
| POST/GET | `/api/v1/club` | create / full state: `club`, `stadium` (= the number of open spots, 1..10), `spots` (open spot ids), `land` = `{maskId: null, regions: ["main", open islands…], locked: [{id, people, zone, state, requiredStadium}], peoples}`, `sections`, `inboxUnread`, `energy` (rules.Energy: 30, +1 per 6 min; an Arena match 5, a friendly 3; `POST /club/energy/refill` fills it for 40 gems) |
| POST | `/api/v1/club/spots/{id}/unlock` | open a main-island spot for its coins, at once, any order; answers GET /club's body. 409 already open (the stadium always), 404 unknown id, 400 an island's spot (it opens with its island: `/club/zones/{people}/buy`), 402 not enough coins |
| GET/POST | `/api/v1/club/zones`; `/club/zones/{people}/buy` | the bridges to the peoples' islands (BEAST 4, STONE 5, ANDROID 7, SHADOW 9 = the stadium level each needs); an island opens its spot and its people's heroes |
| GET/POST | `/api/v1/club/heroes`; `/club/heroes/{id}/unlock` | the hero roster (rules.HeroCatalog, rules.Heroes: the start six, the story heroes by chapter or for gems, the peoples' heroes by their island; levels 1–10) and opening one for gems |
| GET/PUT | `/api/v1/club/heroes/lineup` | the Arena triple («Склад») |
| GET/POST | `/api/v1/club/heroes/training`; `/club/heroes/{id}/train`, `/train/claim` | hero training at the stadium's level (coins, 2 h, experience on claim) |
| GET/POST | `/api/v1/club/story`; `/club/story/{chapter}/claim` | the story «Іскра»: six chapters of goals fed by Arena results; claiming gives the chapter's hero, coins and gems |
| POST | `/api/v1/club/arena/start` `{stake?: 1\|2\|4, story?: N}`, `/club/arena/result` `{matchId, won, conceded?, heroes[], knockouts?, altSkills?}` | the Arena match (v4, 3 v 3, played on the device): the start takes 5 energy and hands out `matchId` (its token; `story` = the chapter it is the story match of, the club's current one only); the result names that token, is taken once, pays coins by the stake (10 paid matches a UTC day, `rules.Economy.ARENA_REWARDED_PER_DAY`), trophies ±25/18 (`rules.Arenas`), the win streak, hero experience and story progress, and feeds the daily tasks (PLAY_ARENA_3, WIN_ARENA_2, KO_5, ALT_SKILL_1), achievements, the alliance tournament and the running events; no draws. `/club/cards/*` are aliases for one client version |
| GET/POST | `/api/v1/live/chest`, `/live/chest/claim` | the weekly arena chest by the club's trophies (rules.Arenas) |
| GET/POST | `/api/v1/club/roads`, `/club/roads/{road}/{step}/claim`; GET `/club/collections` | the trophy road and the club-level road (coins, gems, cosmetics); cosmetic collections |
| GET | `/api/v1/leagues/mine` | public league (8 clubs, same timezone bucket) + private leagues, with tables |
| POST | `/api/v1/leagues/private` `{name}`, `/private/join` `{code}` | private leagues (code, max 20) |
| GET | `/api/v1/club/fixtures` | next scheduled + last 5 played |
| GET | `/api/v1/club/history`, `/clubs/{id}/history` | finished seasons (position, movement, prizes) and trophies |
| GET | `/api/v1/matches/{id}` | fixture + full match result (events for highlights) |
| POST | `/api/v1/friendlies` `{opponentClubId?}` | instant friendly (3 per day), vs bot if omitted |
| GET | `/api/v1/cups/current` | weekly cup: `state` UPCOMING (next `drawAt`, last week's result in `previous`) / ACTIVE / FINISHED, bracket `ties` (round 1–4, slot, score or section points, `shootout`, `winnerId`, `fixtureId` → `/matches/{id}`), `me` (alive, stage, prize), prize table |
| POST | `/api/v1/club/daily/claim` | 7-day login calendar + streak |
| GET | `/api/v1/club/pass`; POST `/club/pass/tasks/{task}/claim`, `/club/pass/claim` `{eliteSport?}` | season pass: 3 daily tasks (one Arena task a day + TRAIN_1 «потренувати героя», PLAY_FRIENDLY, WIN_MATCH, WIN_SECTIONS, SET_LINEUP), 30 levels, free/premium tracks; pass cosmetics (levels 10/15/20/25, free 30) and an elite athlete at premium 30 |
| GET | `/api/v1/catalog/products`; POST `/purchases/verify` `{store, productId, token}` | gold packs + premium pass. `GOOGLE`: Play purchase token (`purchases.products.get`, order id = idempotency key); `APPLE`: transaction id (App Store Server API, production then sandbox; optional x5c chain check against Apple Root CA G3); `DEV` with `IAP_DEV_MODE=true`, token `dev:<order>` |
| GET | `/api/v1/catalog/coin-packs`; POST `/club/shop/coins/{pack}` | coins for gold (25 → 3 000, 100 → 13 000, 400 → 60 000) |
| GET | `/api/v1/club/achievements`; POST `/club/achievements/{id}/claim` | tiered achievements (wins, sections, divisions, titles, the stadium level, streak, invites…): gold per tier, some tiers give cosmetics |
| GET | `/api/v1/ads/ssv` | AdMob server-side verification callback (signature checked with Google's keys, 403 when bad); no placement grants a reward since the double income and the free speed-up went with the city builder |
| POST | `/api/v1/store/apple/notifications`, `/api/v1/store/google/rtdn?token=...` | store refunds (no JWT). Apple: App Store Server Notifications V2 (`REFUND`/`REVOKE` take the purchase back, `REFUND_REVERSED` restores it; the JWS chain is checked against `APPSTORE_ROOT_CERT_PATH`, required outside dev mode). Google: Pub/Sub push of Real-time developer notifications, `voidedPurchaseNotification` by order id or token hash; `GOOGLE_PLAY_RTDN_TOKEN` guards the URL. Gold/coins are taken back as far as the club still has them, the premium pass and the product's item go too; the player gets an inbox message. Dev: `POST /api/v1/dev/store/refund {externalId, action}` |
| GET/POST | `/api/v1/leagues/{id}/messages` `?before=&after=` / `{text}` | private-league chat (members only, 2 s / 20 per 5 min, masked; system lines `join|Name`, `leave|…`, `restart|n|winner`, `kick|…`) |
| POST/DELETE | `/api/v1/messages/{id}/report`, `/messages/{id}`; POST `/clubs/{id}/block` | moderation: 3 reports hide a line; author or creator deletes; blocks hide a club's lines |
| DELETE | `/api/v1/leagues/private/{id}/members/{clubId}` | creator: remove a member |
| GET | `/api/v1/clubs/{id}/h2h`, `/clubs/{id}/grounds`, `/friends` | head-to-head record; another club's grounds (visit): `club`, `stadium`, `spots`, `land` as GET /club's; friends = alliance mates, private-league members, challenge opponents |
| GET/POST | `/api/v1/alliances?q=`, `/alliances`, `/alliances/{id}`, `/alliances/{id}/join`, `/alliances/tournament`; `/club/alliance` (GET/PATCH), `/leave`, `/requests/{clubId}/{accept\|reject}`, `/members/{clubId}/{action}`, `/messages`, `/tournament/claim`; `/clubs/{id}/like`, `/likes`, `/clubs/top-liked` | alliances (stadium 2+), roles, chat, the weekend tournament of Arena wins, likes |
| GET/POST | `/api/v1/events`, `/events/{id}/claim`; `/club/mail`, `/club/mail/{id}/claim` | live ops: calendar events with a goal and a reward; developer mail with gifts |
| POST/GET | `/api/v1/challenges`, `/challenges/{code}`, `/challenges/{code}/accept` | one-off friendly by link (7 days, first taker plays; both clubs rewarded) |
| GET | `/l/{code}`, `/c/{code}`, `/.well-known/assetlinks.json`, `/.well-known/apple-app-site-association` | invite landing pages (5 languages, store links, clipboard hand-off) and app-link association |
| GET | `/api/v1/config` | public client config: base URL, link templates, store URLs, minimum client version |
| POST | `/api/v1/analytics/events` `{platform, appVersion, events:[{name, props?, at?}]}` | product events from the app (≤ 50 per batch; names `[a-z][a-z0-9_]`, ≤ 12 short string props; queued events may be up to 3 days old) |
| GET | `/privacy`, `/terms`, `/delete-account` (`?lang=ru`) | store-required public pages, English/Russian (contact `SUPPORT_EMAIL`, publisher `LEGAL_COMPANY`) |
| GET/POST | `/metrics`, `/admin/metrics?days=`, `/admin/stats`, `/admin/clubs?q=`, `/admin/clubs/{id}`, `/admin/clubs/{id}/grant`, `/admin/scheduler/run` | ops and support with `X-Admin-Token: $ADMIN_TOKEN` — see `deploy/README.md` |
| POST/DELETE | `/api/v1/push-tokens` `{token, platform}` | FCM tokens; matchday result pushes via FCM HTTP v1 (`FCM_SERVICE_ACCOUNT_JSON`, `FCM_PROJECT_ID`) |
| POST | `/api/v1/dev/matchday/run` | dev only (`KTOR_DEVELOPMENT=true`): run the scheduler tick now |

Monetization (spec §6): gold («кристали») buys heroes, energy refills and coin packs; coins open the map's spots and pay hero training;
reputation and league places are never sold. Store products to create: `gold_small` (100), `gold_medium` (550), `gold_large` (1200), `gold_huge` (2600) — consumables,
`season_pass` — consumable bought once per season. AdMob SSV callback URL: `https://<host>/api/v1/ads/ssv`.

Sections: football only since 15 Oct 2026 (the basketball and tennis arenas went with the sport town). A matchday is one opponent across all sections either
club fields; a section only one club has is a walkover (3:0 / 20:0 / 2:0). Table points = sum of section points (3/1/0), so a three-sport
club can take 9 a matchday. `GET /club` returns `sections`; fixtures carry `homePoints/awayPoints` and per-section scores; `GET /matches/{id}`
returns `sections[]` with each sport's full result (`result` stays the football match).

Seasons & divisions: 7 divisions (1 Sunday League … 7 Legends League). After round 28 the league is closed (`status=FINISHED`):
final table → prizes by position × division, a `club_seasons` history row per member, trophies (title, promotion), top 2 promoted,
bottom 2 relegated, `clubs.season_no` + 1 (keys the season pass), and every human is placed in next season's league of their new
division (season starts the next day; freed bots of that division are reused). Matchday coin rewards grow 25% per division.

Cups: every week each timezone bucket plays knockout cups. Clubs active in the last 7 days are entered automatically and grouped
by strength (division, reputation) into 16-club brackets, bots of the group's division fill the last one; open draw on Thursday
12:00, round of 16 Thursday 18:00, quarter-finals Friday, semi-finals Saturday, final Sunday (bucket time, two hours before the league
matchday). A tie plays the sections both clubs have; more section points wins, level ties go to a penalty shootout (kick odds from the
starters' technique and mental; `shootout` in the result). Fatigue like a friendly; prizes by the round reached (300 / 600 / 1 200 /
2 500 / 5 000 coins × division multiplier, gold from the semi-final, reputation), a `CUP` trophy for the winner (achievement
`cup_winner`); inbox `CUP_DRAW` / `CUP_RESULT` and pushes. Rounds missed during downtime are played in order on the next pass.

Athletes («вихованці академії») only fill the eleven around the club's heroes in simulated matches (rules.HeroSquad); the
club does not manage them (the squad, academy, trainings, hospital, scouting, techniques and the market went on 20 Oct 2026).
At season end everyone ages a year — young players grow, veterans decline, 34+ may retire (replaced by a youth prospect;
retired rows stay for old highlights, inbox `RETIREMENTS`). League matchdays move morale (+5 win / +1 draw / −4 loss for starters, −2 for those left out; the
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

    TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/arenacity_test TEST_DATABASE_USER=$USER ./gradlew :server:test

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
