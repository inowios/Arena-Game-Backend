# Backend Part 3: Daily Rewards, Season Pass, Purchases, Push — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The retention and monetisation layer from spec §6: 7-day login calendar + matchday streak, a 4-week season pass with daily tasks and free/paid tracks, gold/coin purchases with server-side receipt verification (pluggable; dev verifier locally), rewarded-ad grants, and FCM push after each matchday.

**Architecture:** Rules in `domain/rules/Economy.kt` (reward tables, task definitions, pass levels). Task progress is recorded by existing use cases through a small `ProgressTracker` interface (events: `INCOME_COLLECTED`, `TRAINING_STARTED`, `MATCH_WON`, `MATCH_PLAYED`, `FRIENDLY_PLAYED`, `LINEUP_SAVED`), so the economy module never reaches into other modules. Purchases go through `ReceiptVerifier` (interface) with `GooglePlayVerifier`/`AppStoreVerifier` real implementations behind config and a `DevReceiptVerifier` enabled only with `iap.devMode=true`. Push uses the FCM HTTP v1 pattern from `Arena_Backend` (service-account JWT, no Firebase SDK). The matchday runner calls `MatchdayNotifier` after each league round.

## Global Constraints
- Never grant gold/coins from client-reported data; every grant is computed server-side from stored state.
- Receipts are idempotent: `purchases.external_id` unique; replay returns the original result.
- Season pass: 30 levels, 100 XP per level, season = the public league's 28-day season (pass id = `season_number`).
- Daily tasks (reset at league-local midnight, 3 per day): `COLLECT_3` (collect income 3×, 40 XP), `TRAIN_2` (start 2 trainings, 40 XP), `PLAY_FRIENDLY` (1 friendly, 30 XP), `WIN_MATCH` (win the matchday match, 60 XP), `SET_LINEUP` (save lineup, 20 XP). Each day picks 3 by `(dayNumber + clubHash) mod`.
- Login calendar: day 1..7 rewards `[150c, 200c, 300c, 2g, 400c, 3g, 800c + 5g]`, resets if a day is skipped. Matchday streak: +1 per day the club had a lineup saved before kick-off... simplified: +1 per matchday result viewed? No — streak = consecutive days with a login; shown in profile.
- Gold catalogue: `gold_small` 100 G, `gold_medium` 550 G, `gold_large` 1200 G; `coins_small` 5 000 c, `coins_large` 30 000 c; `season_pass` premium track. Prices live in the stores, not here.
- Rewarded ad grant: `POST /ads/reward {placement}` with placements `DOUBLE_INCOME` (next collect ×2, once per 4h) and `FREE_SPEEDUP` (one speed-up ≤ 30 min, once per day). Verification of the ad view is client-side for MVP (documented risk), rate-limited server-side.
- Push: on matchday round played → to every human member with push tokens: "Matchday played: {score} vs {opponent}". Tokens registered at `POST /push-tokens {token, platform}`.

## Schema (V5__Create_economy.sql)
```sql
ALTER TABLE clubs ADD COLUMN login_day INT NOT NULL DEFAULT 0, ADD COLUMN login_last DATE, ADD COLUMN streak INT NOT NULL DEFAULT 0,
  ADD COLUMN double_income_until TIMESTAMPTZ, ADD COLUMN free_speedup_date DATE, ADD COLUMN double_income_ad_date TIMESTAMPTZ;
CREATE TABLE season_pass (club_id UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE, season INT NOT NULL, xp INT NOT NULL DEFAULT 0,
  premium BOOLEAN NOT NULL DEFAULT FALSE, claimed_free INT NOT NULL DEFAULT 0, claimed_premium INT NOT NULL DEFAULT 0, PRIMARY KEY (club_id, season));
CREATE TABLE daily_tasks (club_id UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE, day DATE NOT NULL, task VARCHAR(24) NOT NULL,
  progress INT NOT NULL DEFAULT 0, claimed BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY (club_id, day, task));
CREATE TABLE purchases (id UUID PRIMARY KEY, club_id UUID NOT NULL REFERENCES clubs(id), store VARCHAR(8) NOT NULL, product_id VARCHAR(64) NOT NULL,
  external_id VARCHAR(255) NOT NULL UNIQUE, status VARCHAR(12) NOT NULL, granted_gold INT NOT NULL DEFAULT 0, granted_coins BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());
```

## API
```
POST /club/daily/claim            → {day, reward, streak}            (409 if already claimed today)
GET  /club/pass                   → {season, xp, level, premium, tasks:[{task, progress, target, xp, claimed}], rewards:[{level, free, premium, claimedFree, claimedPremium}]}
POST /club/pass/tasks/{task}/claim
POST /club/pass/claim/{level}     → grants free (and premium if owned) reward for a reached level
POST /purchases/verify {store, productId, token} → {status, gold, coins}   (dev mode: token "dev:<anything>" accepted)
POST /ads/reward {placement}
POST /push-tokens {token, platform}; DELETE /push-tokens {token}
```

## Tasks
1. Rules + migration + repositories (`EconomyRepository`: pass, tasks, purchases; club fields) + `ProgressTracker` wiring into collect/training/friendly/lineup/matchday use cases. Tests on rules (reward tables, task picking, level math).
2. Daily reward + pass use cases + routes. Tests with fakes: claim once per day, streak reset after a skipped day, task progress and claim, level rewards claim ordering.
3. Purchases: `ReceiptVerifier`, `DevReceiptVerifier`, Google/Apple verifiers (HTTP, config: `iap.google.serviceAccountJson`, `iap.apple.sharedSecret`/App Store Server API — implement Google via `purchases.products.get` with a service-account token; Apple via `verifyReceipt` legacy endpoint for MVP), idempotency test, route.
4. Ads reward route + rules (cooldowns) + tests.
5. Push: `PushTokenRepository`, `FcmSender` (HTTP v1 + service-account JWT, no-op without config), `MatchdayNotifier` hooked into `MatchdayRunner.runLeague` after a round; route for tokens. Unit test for message building.
6. Smoke extension + README + commit.
