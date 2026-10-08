# Backend Part 1: Foundation, Auth, Club, Buildings, Athletes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A running Ktor server (`server` module) with guest + verified OAuth auth, club creation, buildings with server-side timers and income, athletes with academy/training/medcentre, and the football lineup/tactics endpoint — everything the Unity client needs before leagues exist.

**Architecture:** Mirrors `Arena_Backend` conventions (Clean Architecture: `core/`, `common/`, `data/`, `domain/`, `presentation/`), Exposed DSL + Flyway + Koin + JWT, `dbQuery` wrapper, `ApiResponse` envelope, `DomainException` → StatusPages. All game rules are pure Kotlin in `domain/rules/` (catalog, timers, income) so they are unit-testable without a DB. Timers are stored as absolute instants; the server never trusts client clocks or client-reported state. Tests: rules + use cases with in-memory fakes (no DB), plus one `testApplication` smoke test for routing.

**Tech Stack:** Kotlin 2.2.20, Ktor 3.2.3 (netty, auth-jwt, content-negotiation, status-pages, call-logging, cors, rate-limit, request-validation, client-cio), Exposed 0.61.0 (core/jdbc/java-time), HikariCP 6.2.1, PostgreSQL 42.7.7, Flyway 11.20.3, Koin 4.1.0, java-jwt (transitive), logback 1.5.18, kotlinx-datetime 0.6.2, JUnit 5 + kotlin.test.

## Global Constraints

- Package `com.matchdayclub`. Module `server` depends on `match-engine`.
- Every DB access goes through `dbQuery { }` (supervisorScope + newSuspendedTransaction(IO)); `DatabaseConfig { defaultMaxAttempts = 1 }`.
- Responses: `ApiResponse<T>(code, data, errors, requestId)`; errors only via `DomainException` subclasses.
- JWT HS256, `sub` = userId, access 12h, refresh opaque UUID stored as SHA-256 hash, rotation on refresh.
- OAuth ID tokens MUST be verified server-side (Google `tokeninfo`, Apple JWKS). Guest login by `deviceId` (UUID from client) with later linking.
- All timers: `ends_at TIMESTAMPTZ`; "is done" = `now >= ends_at` on the server.
- Clients send commands, never state (`collect building 12`, not `I have 500 coins`).
- Flyway migrations `V{n}__Snake_case.sql`, UUID PKs, `created_at/updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- Local DB: PostgreSQL 16 on localhost (`brew services`), database `matchdayclub`, user `postgres`/`postgres` (create in Task 1).
- Spec §3 numbers: academy produces an athlete every 8 h; currencies coins / reputation / gold; club level grows with reputation.

---

## File structure

```
server/
  build.gradle.kts
  src/main/resources/application.conf, logback.xml, db/migration/V1..V6
  src/main/kotlin/com/matchdayclub/
    Application.kt                         main + module()
    core/plugins/{Koin,Database,Migrations,Security,Serialization,StatusPages,Monitoring,RequestId,CORS,RateLimiting,Routing}.kt
    core/di/AppModule.kt
    core/auth/{JwtUtil.kt, GoogleTokenVerifier.kt, AppleTokenVerifier.kt, IdTokenVerifier.kt}
    common/response/ApiResponse.kt
    common/extension/DatabaseExtensions.kt
    common/time/Clock.kt                   interface Clock { fun now(): Instant }  (injectable; tests use FixedClock)
    domain/exception/DomainException.kt
    domain/model/{User, RefreshToken, Club, Building, Athlete, Training, Lineup}.kt
    domain/rules/{BuildingCatalog, ClubLevels, AthleteFactory, TrainingRules}.kt
    domain/repository/{UserRepository, RefreshTokenRepository, ClubRepository, BuildingRepository, AthleteRepository, TrainingRepository, LineupRepository}.kt
    domain/usecase/auth/{GuestLoginUseCase, OAuthLoginUseCase, RefreshTokenUseCase}.kt
    domain/usecase/club/{CreateClubUseCase, GetClubUseCase, PlaceBuildingUseCase, UpgradeBuildingUseCase, CollectIncomeUseCase, SpeedUpUseCase}.kt
    domain/usecase/athlete/{ClaimAcademyUseCase, StartTrainingUseCase, CompleteTrainingUseCase, HealAthleteUseCase, SetLineupUseCase}.kt
    data/database/{BaseTable, UserTable, RefreshTokenTable, ClubTable, BuildingTable, AthleteTable, TrainingTable, LineupTable}.kt
    data/repository/*Impl.kt
    presentation/dto/{AuthDto, ClubDto, AthleteDto, LineupDto}.kt
    presentation/mapper/{ClubMapper, AthleteMapper}.kt
    presentation/route/{AuthRoute, MeRoute, ClubRoute, BuildingRoute, AthleteRoute, LineupRoute}.kt
  src/test/kotlin/com/matchdayclub/
    testutil/{Fakes.kt, FixedClock.kt, Fixtures.kt}
    domain/rules/{BuildingCatalogTest, ClubLevelsTest, AthleteFactoryTest, TrainingRulesTest}.kt
    domain/usecase/{GuestLoginUseCaseTest, RefreshTokenUseCaseTest, CreateClubUseCaseTest, BuildingUseCasesTest, AthleteUseCasesTest, SetLineupUseCaseTest}.kt
    ApplicationSmokeTest.kt
```

---

## Game rules (domain/rules) — the contract

**BuildingCatalog** (`BuildingType` enum: `PITCH, BASKETBALL_COURT, TENNIS_COURT, ACADEMY, TRAINING_CENTER, MEDICAL_CENTER, TICKET_OFFICE, CLUB_SHOP, CAFE, PARKING, DECOR`). Per type and level 1..3: `cost` coins, `buildSeconds`, `incomePerHour` (income buildings only), `capacity`/effects, `requiredClubLevel`, footprint `(w, h)` cells.

| Type | L1 cost / time / effect | L2 | L3 | req. club level (L1/L2/L3) |
|---|---|---|---|---|
| PITCH | 0 / 0 / athlete cap 60, seats 500 | 2 000 / 2h / cap 75, seats 2 000 | 12 000 / 12h / cap 90, seats 8 000 | 1 / 3 / 6 |
| ACADEMY | 500 / 10min / quality 40–55 every 8h | 3 000 / 4h / 50–65 | 15 000 / 16h / 60–78 | 1 / 3 / 6 |
| TRAINING_CENTER | 800 / 20min / 1 slot, +1 attr/1h | 4 000 / 6h / 2 slots, +2/1h | 18 000 / 20h / 3 slots, +3/1h | 1 / 3 / 6 |
| MEDICAL_CENTER | 600 / 15min / heal 1 athlete/4h | 3 500 / 5h / 2/3h | 16 000 / 18h / 3/2h | 2 / 4 / 7 |
| TICKET_OFFICE | 300 / 5min / 120 coins/h, cap 6h | 2 000 / 3h / 300/h, cap 8h | 9 000 / 10h / 700/h, cap 12h | 1 / 2 / 5 |
| CLUB_SHOP | 1 000 / 30min / 200/h, cap 6h | 5 000 / 6h / 500/h, cap 8h | 20 000 / 20h / 1 100/h, cap 12h | 2 / 4 / 7 |
| CAFE | 700 / 20min / 150/h, cap 6h | 3 500 / 5h / 380/h, cap 8h | 14 000 / 16h / 850/h, cap 12h | 2 / 3 / 6 |
| PARKING | 1 500 / 45min / 250/h, cap 8h | 7 000 / 8h / 600/h, cap 10h | 25 000 / 24h / 1 300/h, cap 14h | 3 / 5 / 8 |
| DECOR | 200 / 1min / cosmetic | – | – | 1 |
| BASKETBALL_COURT, TENNIS_COURT | locked until post-release (catalog present, `enabled = false`) | | | |

Income = `incomePerHour * min(hoursSinceCollect, capHours)`; reputation multiplier `1 + reputationTier * 0.05` (tier = clubLevel - 1). Speed-up cost in gold = `ceil(remainingSeconds / 600.0)` (1 gold per 10 min), min 1.

**ClubLevels**: level thresholds by reputation: `[0, 100, 300, 700, 1500, 3000, 5500, 9000, 14000, 20000]` for levels 1..10; grid size by level: L1 12×12, +2 per level to 30×30. Max buildings of a type per club: income buildings 1 each, ACADEMY 1, TRAINING_CENTER 1, MEDICAL_CENTER 1, PITCH 1, DECOR unlimited.

**AthleteFactory** (deterministic from a seed): `create(seed, sport, qualityRange, position?)` — random name from a 60-name pool per locale-neutral list, age 17–21, attributes around quality with position bias (+10 on the role attribute, −8 on the opposite), rarity: quality ≥ 70 ELITE, ≥ 58 RARE, else COMMON. Starter squad for a new club: 14 footballers (2 GK, 5 DEF, 5 MID, 2 FWD) of quality 42–55 from seed = club id hash.

**TrainingRules**: a training raises one attribute by `delta(level)` after `durationSeconds = 3600`; athlete fatigue +15 per training; cannot train if fatigue > 80 or injured; attribute cap 99 and cap by PITCH level (`athleteCap`). Fatigue recovers 4 per hour passively (computed on read: `fatigue - 4 * hoursSince(lastFatigueAt)`), medcentre heal sets `injured_until = null` and fatigue 0.

**Lineup rules** (football): exactly 11 starters, exactly 1 GK, ≤ 7 bench, all athlete ids belong to the club and are not duplicated; injured athletes allowed in lineup but engine auto-subs. Tactics enum values from match-engine.

---

### Task 1: Server module, config, database, plugins, health

**Files:** `settings.gradle.kts` (add `server`), `build.gradle.kts` (bump Kotlin to 2.2.20, add ktor plugin alias), `gradle/libs.versions.toml`, `server/build.gradle.kts`, `server/src/main/resources/{application.conf,logback.xml}`, `server/src/main/kotlin/com/matchdayclub/Application.kt`, `core/plugins/*.kt`, `common/response/ApiResponse.kt`, `common/extension/DatabaseExtensions.kt`, `common/time/Clock.kt`, `domain/exception/DomainException.kt`, `data/database/BaseTable.kt`, `V1__Create_users_and_refresh_tokens.sql`, test `ApplicationSmokeTest.kt`.

- [ ] Create local DB: `createdb -U "$USER" matchdayclub 2>/dev/null || true` (local Postgres trusts the mac user); `application.conf` default `jdbcUrl = "jdbc:postgresql://localhost:5432/matchdayclub"`, `user = ${USER}`… use env `DATABASE_USER` default `kushpe`, password empty.
- [ ] Copy verbatim from the Arena_Backend map: `ApiResponse`, `dbQuery`, `DomainException` set, `BaseTable` (but with `timestampWithTimeZone`), `configureDatabase` (Hikari + Flyway `classpath:db/migration`), `configureSecurity` (jwt "auth-jwt"), `configureSerialization`, `configureStatusPages`, `configureMonitoring` (CallLogging), `configureRequestId`, `configureCORS` (anyHost in dev), `configureRateLimiting` (AUTH 20/min, API 600/min).
- [ ] `Clock` interface + `SystemClock` (Koin single) so use cases are testable.
- [ ] `V1`: `users(id uuid pk, auth_provider varchar(10) not null, provider_id varchar(255), email varchar(255), display_name varchar(80), avatar_url text, is_active bool default true, created_at, updated_at, unique(auth_provider, provider_id))`; `refresh_tokens(id uuid pk, user_id uuid fk cascade, token_hash varchar(64) unique, expires_at timestamptz, revoked_at timestamptz null, created_at)`; `push_tokens(id, user_id, token text unique, platform varchar(10), created_at, updated_at)`.
- [ ] `Routing`: `GET /health` (SELECT 1), `route("/api/v1")` with rate limits; feature routes added in later tasks.
- [ ] Test `ApplicationSmokeTest`: `testApplication { application { configureSerialization(); configureStatusPages(); routing { get("/ping") { call.respond("pong".toSuccessResponse()) } } } }` → 200 and body contains `"data":"pong"`. Plus a `DomainException` → 404 JSON test.
- [ ] Run `./gradlew :server:test` and `./gradlew :server:run` in background → `curl localhost:8080/health` returns 200 with `{"code":200,"data":"ok"}`. Commit: `server: ktor skeleton with db, jwt, envelope, health`.

### Task 2: Auth — guest, OAuth with verification, refresh

**Files:** `core/auth/{JwtUtil,IdTokenVerifier,GoogleTokenVerifier,AppleTokenVerifier}.kt`, `domain/model/{User,RefreshToken}.kt`, `domain/repository/{UserRepository,RefreshTokenRepository}.kt`, `data/database/{UserTable,RefreshTokenTable}.kt`, `data/repository/*Impl.kt`, `domain/usecase/auth/*.kt`, `presentation/dto/AuthDto.kt`, `presentation/route/AuthRoute.kt`, `MeRoute.kt`, tests `GuestLoginUseCaseTest`, `OAuthLoginUseCaseTest`, `RefreshTokenUseCaseTest`, `testutil/Fakes.kt`.

**API:**
- `POST /api/v1/auth/guest {deviceId}` → `AuthResponse(accessToken, refreshToken, user, isNewUser)`; provider `GUEST`, providerId = deviceId.
- `POST /api/v1/auth/oauth/google {idToken}` / `.../apple {idToken}` → verifies token → finds by (provider, sub) → else by email → else creates. Header `Authorization: Bearer <guest jwt>` optional: if present and the guest user has no OAuth link, the guest account is **upgraded in place** (provider/providerId/email replaced) so the club is kept.
- `POST /auth/refresh {refreshToken}` rotation (copy `rotate` semantics), `POST /auth/logout {refreshToken}`.
- `GET /api/v1/me` → `{user, hasClub}`.

**IdTokenVerifier** interface `suspend fun verify(idToken: String): VerifiedIdentity(providerId, email?, name?)`; Google impl calls `https://oauth2.googleapis.com/tokeninfo?id_token=` with Ktor client and checks `aud` ∈ configured client ids (`oauth.google.clientIds`, comma-separated) and `exp`; Apple impl fetches JWKS `https://appleid.apple.com/auth/keys` (cached 1h), verifies RS256 signature with java-jwt + `RSAKeyProvider`, `iss == https://appleid.apple.com`, `aud == oauth.apple.bundleId`. Tests use a `FakeIdTokenVerifier`.

- [ ] Tests first (fakes), then implement, then route-level manual check with curl: guest login returns tokens; `/me` with bearer works; refresh rotates; reused old refresh → 401.
- [ ] Commit: `server: guest and verified oauth auth, refresh rotation`.

### Task 3: Club, buildings, income, timers

**Files:** `domain/rules/{BuildingCatalog,ClubLevels}.kt` + tests; `domain/model/{Club,Building}.kt`; repositories + tables (`V2__Create_clubs_and_buildings.sql`); use cases `CreateClub`, `GetClub`, `PlaceBuilding`, `UpgradeBuilding`, `CollectIncome`, `SpeedUp`; `ClubRoute`, `BuildingRoute`; DTOs; `BuildingUseCasesTest`, `CreateClubUseCaseTest`.

**Schema V2:** `clubs(id, user_id unique fk, name varchar(40), primary_color char(7), secondary_color char(7), emblem_json text, level int default 1, reputation int default 0, coins bigint default 2500, gold int default 50, timezone varchar(64), created_at, updated_at)`; `buildings(id, club_id fk, type varchar(32), level int, grid_x int, grid_y int, rotation int default 0, state varchar(16) default 'IDLE', upgrade_ends_at timestamptz null, last_collected_at timestamptz, created_at, updated_at, unique(club_id, grid_x, grid_y))`.

**API:**
- `POST /club {name, primaryColor, secondaryColor, timezone}` → creates club with PITCH L1 at (2,2) + TICKET_OFFICE L1 at (8,2), starter squad (Task 4 wires athletes), 2 500 coins, 50 gold. 409 if exists.
- `GET /club` → `ClubDto{club, buildings[], grid{size}, catalog versions?}`; each building has `state`, `upgradeEndsAt`, `pendingIncome`, `incomeCapReachedAt`.
- `POST /club/buildings {type, x, y, rotation}` → validates catalog (`requiredClubLevel`, count limit, footprint inside grid and not overlapping), charges coins, state `UPGRADING` with `upgrade_ends_at = now + buildSeconds` (L1 build), level 1.
- `POST /club/buildings/{id}/upgrade` → next level, charges, timer.
- `POST /club/buildings/{id}/collect` → income since `last_collected_at` capped; adds coins; sets `last_collected_at = now`. Also finalises a finished `UPGRADING` state lazily (any read/collect of a building whose timer passed flips it to IDLE — "finalise on read").
- `POST /club/buildings/{id}/speedup` → charges gold by rule, completes timer.
- `DELETE /club/buildings/{id}` → only DECOR.

- [ ] Rule tests: catalog lookups, income cap, speed-up cost, level thresholds, grid size, overlap detection (`BuildingCatalog.footprint`, `Grid.overlaps`).
- [ ] Use case tests with `FakeClubRepository`/`FakeBuildingRepository` and `FixedClock`: place → charged + timer; collect before timer done → 409 `ConflictException("Building is under construction")`; collect after cap → capped amount; speed-up cost; insufficient coins → `ValidationException`.
- [ ] Commit: `server: clubs, buildings, income and timers`.

### Task 4: Athletes — starter squad, academy, training, medcentre

**Files:** `domain/rules/{AthleteFactory,TrainingRules}.kt` + tests; `domain/model/{Athlete,Training}.kt`; `V3__Create_athletes_and_trainings.sql`; repos; use cases `ClaimAcademy`, `StartTraining`, `CompleteTraining`, `HealAthlete`; `AthleteRoute`; `AthleteUseCasesTest`.

**Schema V3:** `athletes(id, club_id fk, name varchar(60), sport varchar(16), position varchar(8), attack int, defense int, physical int, technique int, mental int, age int, rarity varchar(8), fatigue int default 0, fatigue_at timestamptz, injured_until timestamptz null, morale int default 50, created_at, updated_at)`; `trainings(id, club_id, athlete_id fk, attribute varchar(12), slot int, ends_at timestamptz, created_at)`; `clubs` gets `academy_next_at timestamptz`, `medical_next_at timestamptz`.

**API:**
- `GET /club/athletes` → list with computed `fatigue` (passive recovery applied), `injured`, `overall`.
- `POST /club/academy/claim` → if `now >= academy_next_at`: create athlete via factory with quality by ACADEMY level, set `academy_next_at = now + 8h`; else 409 with `nextAt`.
- `POST /club/trainings {athleteId, attribute}` → slot available by TRAINING_CENTER level, rules; `ends_at = now + 1h`.
- `POST /club/trainings/{id}/complete` → if done: apply delta, delete training. (Also finalise-on-read in `GET /club/athletes`.)
- `POST /club/athletes/{id}/heal` → MEDICAL_CENTER cooldown, clears injury & fatigue.

- [ ] Tests: factory determinism (same seed ⇒ same athlete), rarity thresholds, starter squad shape (14, 2 GK); training rules (fatigue block, cap); use cases with fakes and FixedClock (claim before time → Conflict; slot limit; complete early → Conflict).
- [ ] Commit: `server: athletes, academy, training, medical centre`.

### Task 5: Lineup & tactics (football)

**Files:** `domain/model/Lineup.kt` (uses `com.matchdayclub.engine.model.Tactics`), `V4__Create_lineups.sql` (`lineups(club_id, sport, starters uuid[], bench uuid[], tactics_json text, updated_at, pk(club_id, sport))`), `SetLineupUseCase` + validation rules, `LineupRoute` (`GET/PUT /club/lineup/{sport}`), `LineupMapper` to engine `Lineup` (`toEngineLineup(athletes)`), tests.

- [ ] `PUT` validates per Lineup rules; `GET` returns current or an auto-generated default (best 11 by overall with 1 GK) and `isDefault = true`.
- [ ] `EngineBridge.toEngineLineup(club, athletes, lineup): com.matchdayclub.engine.model.Lineup` with fatigue/injury flags — unit test that an injured athlete maps to `injured = true`.
- [ ] Commit: `server: football lineup and tactics`.

### Task 6: Wiring, smoke run, docs

- [ ] `AppModule.kt` registers everything; `Routing.kt` mounts all routes; `README.md` gets the API table and `make`-style commands (`./gradlew :server:run`, env vars).
- [ ] End-to-end curl script `scripts/smoke.sh`: guest login → create club → get club → place TICKET_OFFICE → speed up → collect → claim academy (expect 409 before 8h) → set lineup → GET lineup. Must exit 0.
- [ ] Commit: `server: wiring, smoke script, docs`.

## Self-review

- Spec §3 (постройки, спортсмены, валюты, связка роста через репутацию) → Tasks 3–4; §8 (гостевой вход + OAuth, античит через команды, таймеры по серверному времени) → Tasks 1–3; §4 lineup/tactics → Task 5. Leagues/matchday/economy purchases are Part 2/3 plans.
- Names consistent: `dbQuery`, `ApiResponse.toSuccessResponse`, `Clock`, `BuildingCatalog`, `ClubLevels`, `AthleteFactory`, `TrainingRules`, `EngineBridge`.
