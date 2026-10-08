# Backend Part 2: Seasons, Leagues, Matchday, Results, Friendlies — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clubs are placed into public leagues of 8 by timezone and division, a 28-round fixture list is generated per season, an hourly scheduler plays every league whose local time reached the matchday hour, results (events + score) are stored and visible, standings and reputation update, private leagues aggregate members' public results, and friendlies can be played on demand.

**Architecture:** Same layering as Part 1. New domain rules in `domain/rules/Schedule.kt` (round-robin fixture generation, 7 opponents × 4 = 28 rounds) and `domain/rules/Standings.kt` (points, tiebreaks, reputation deltas). `MatchdayRunner` is a use case that runs one league's due round inside a per-league advisory lock (`pg_try_advisory_xact_lock`) so overlapping scheduler ticks are safe. The scheduler is a Ktor `launch { while(true) }` loop (hourly, plus on startup) matching `Arena_Backend`. Bot clubs fill empty public-league slots so a brand-new player always has 7 opponents.

**Tech Stack:** as Part 1 + `match-engine`.

## Global Constraints

- Public league: 8 clubs, same `timezone` group (zone id → UTC offset bucket of ±1h, i.e. offset in hours rounded), same division (1 = lowest at launch). Matchday hour: 20:00 local (`matchday_hour = 20`).
- Season: 28 rounds; a new season is created when a league finishes; `season_number` increments; standings reset; top 2 promote / bottom 2 relegate once divisions > 1 exist (data model supports it now, promotion logic deferred).
- Fixtures are generated once per league per season with a fixed seed so they are reproducible. Each round pairs all 8 clubs (4 matches). Round r is played on `season_start_date + r days` (one round per day).
- A round is due when the league's local date-time ≥ (round date at `matchday_hour`). Matches of a due round are simulated with seed = `hash(fixture id)`; lineups taken via `GetLineup` (stored or default).
- Results immutable once `status = PLAYED`. `MatchResult` JSON (match-engine model) stored in `fixtures.result_json`.
- Reputation: win +12, draw +5, loss +1; club level recomputed by `ClubLevels.levelFor`. Coins reward: win 300, draw 150, loss 60 (× division multiplier 1.0 for now).
- Bot clubs: `users.auth_provider = 'BOT'`, clubs flagged `is_bot = true`, squads quality 45–58, default lineup. Bots never collect income or train.
- Private leagues: code = 6 chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, max 20 members, created by a club, standings computed from members' public fixtures within the current season window.
- Friendlies: at most 3 per club per local day; opponent = any club by id (or a bot if omitted); simulated immediately with seed = random, stored as a fixture with `kind = FRIENDLY`, `league_id = null`; no reputation; coins +40, athletes fatigue +8 for starters.

---

## Schema (V4__Create_leagues_and_fixtures.sql)

```sql
ALTER TABLE clubs ADD COLUMN is_bot BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE clubs ADD COLUMN division INT NOT NULL DEFAULT 1;
ALTER TABLE clubs ADD COLUMN tz_bucket INT NOT NULL DEFAULT 0;              -- UTC offset hours, rounded
ALTER TABLE clubs ADD COLUMN friendlies_date DATE, ADD COLUMN friendlies_played INT NOT NULL DEFAULT 0;

CREATE TABLE leagues (
  id UUID PRIMARY KEY,
  kind VARCHAR(8) NOT NULL,                -- PUBLIC | PRIVATE
  name VARCHAR(40) NOT NULL,
  code VARCHAR(6) UNIQUE,                  -- private only
  division INT NOT NULL DEFAULT 1,
  tz_bucket INT NOT NULL DEFAULT 0,
  timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
  matchday_hour INT NOT NULL DEFAULT 20,
  season_number INT NOT NULL DEFAULT 1,
  season_start DATE NOT NULL,
  current_round INT NOT NULL DEFAULT 0,    -- last played round
  created_by UUID REFERENCES clubs(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_leagues_public ON leagues (kind, division, tz_bucket);

CREATE TABLE league_members (
  league_id UUID NOT NULL REFERENCES leagues(id) ON DELETE CASCADE,
  club_id UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (league_id, club_id)
);
CREATE INDEX idx_league_members_club ON league_members (club_id);

CREATE TABLE fixtures (
  id UUID PRIMARY KEY,
  league_id UUID REFERENCES leagues(id) ON DELETE CASCADE,   -- null for friendlies
  kind VARCHAR(10) NOT NULL,                                 -- LEAGUE | FRIENDLY
  season_number INT NOT NULL DEFAULT 1,
  round INT NOT NULL DEFAULT 0,
  scheduled_at TIMESTAMPTZ NOT NULL,
  home_club_id UUID NOT NULL REFERENCES clubs(id),
  away_club_id UUID NOT NULL REFERENCES clubs(id),
  status VARCHAR(10) NOT NULL DEFAULT 'SCHEDULED',           -- SCHEDULED | PLAYED
  home_score INT, away_score INT,
  seed BIGINT NOT NULL,
  result_json TEXT,
  played_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fixtures_league_round ON fixtures (league_id, season_number, round);
CREATE INDEX idx_fixtures_club ON fixtures (home_club_id, away_club_id);
```

## Domain contracts

```kotlin
object Schedule {
    /** Berger round-robin for 8 ids: 7 rounds; repeated 4× with alternating home/away → 28 rounds of 4 pairs. */
    fun rounds(clubIds: List<UUID>, seed: Long): List<List<Pair<UUID, UUID>>>    // size 28, each 4 pairs (home, away)
    fun roundInstant(seasonStart: LocalDate, round: Int, zone: ZoneId, hour: Int): Instant   // round 1 → seasonStart + 0 days
}
data class Row(val clubId, played, won, drawn, lost, gf, ga, points) { val gd }
object Standings {
    fun table(fixtures: List<Fixture>, memberIds: List<UUID>): List<Row>   // sorted: points, gd, gf, name later
    fun reputationDelta(outcome: Outcome): Int = WIN 12 / DRAW 5 / LOSS 1
    fun coinsReward(outcome): Long = 300 / 150 / 60
}
```

Use cases:
- `EnsurePublicLeagueMembership(clubId)`: called on club creation (hook `ClubInitializer`). Finds a PUBLIC league in the club's `tz_bucket`/division with < 8 members **whose season has not started playing** (`current_round == 0`) → joins; else creates a league (season_start = tomorrow local date), joins, and fills with bots up to 8 **when the season starts** (see runner). Simplification: on creation, immediately fill to 8 with bots so fixtures can be generated; a later real player replaces a bot only in a *new* league (bots stay for the season).
- `GenerateFixtures(leagueId)`: when a league has 8 members and no fixtures for the season → 28 rounds.
- `MatchdayRunner.runDueRounds(now)`: for each league with fixtures where next round instant ≤ now: lock, load lineups via `LineupProvider` (GetLineup logic by club id, not user id), simulate, store results, apply rewards (coins, reputation, level), set `current_round`. If round 28 done → `startNewSeason` (season_number+1, season_start = next day, regenerate fixtures).
- `GetMyLeagues(userId)`: public league + private leagues with standings; `GetLeagueTable(leagueId)`, `GetFixtures(leagueId, season)`; `GetMatch(fixtureId)` (result JSON); `GetClubFixtures(clubId)` (next and last 5).
- `CreatePrivateLeague(userId, name)`, `JoinPrivateLeague(userId, code)`, `LeavePrivateLeague`.
- `PlayFriendly(userId, opponentClubId?)`.

API:
```
GET  /leagues/mine                     → { public: LeagueDto+table, private: [LeagueDto+table] }
GET  /leagues/{id}                     → league + table + next round
GET  /leagues/{id}/fixtures?round=     → fixtures (all rounds if omitted)
POST /leagues/private  {name}          → league with code
POST /leagues/private/join {code}
DELETE /leagues/private/{id}/leave
GET  /matches/{id}                     → fixture + result events
GET  /club/fixtures                    → next scheduled + last 5 played
POST /friendlies {opponentClubId?}     → played fixture
GET  /clubs/{id}                       → public club profile (name, colors, level, squad overall, stadium level)
```

Tests (fakes, FixedClock): Schedule (28 rounds, each club plays 28 games, 14 home/14 away, no self-pairs, deterministic), Standings (ordering + tiebreak), MatchdayRunner (due detection by timezone, plays exactly one round per tick, rewards applied, season rollover at 28, idempotent when run twice), private league code uniqueness and 20-cap, friendlies daily cap.

Tasks: (1) migration + models + repos; (2) Schedule/Standings rules + tests; (3) league membership + bots + fixture generation; (4) MatchdayRunner + scheduler plugin + rewards; (5) private leagues; (6) friendlies + public club profile; (7) routes + smoke extension + README; commit after each.
