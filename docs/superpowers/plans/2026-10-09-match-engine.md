# Match Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pure-Kotlin, deterministic, seed-driven match simulator (`match-engine`) that turns two lineups + tactics + a sport profile into a scored event list, with a console balancer that proves the statistics are sane.

**Architecture:** A Gradle multi-project in `Arena-Game-Backend/` with a `match-engine` module that has zero Ktor/DB dependencies. Domain types (`Athlete`, `Lineup`, `Tactics`, `SportProfile`) feed `MatchSimulator.simulate(...)` which runs a fixed number of "possessions" using a `SplittableRandom(seed)`; each possession resolves into `MatchEvent`s. A `balance` CLI runs thousands of matches and prints score distributions. Later the Ktor backend depends on this module.

**Tech Stack:** Kotlin 2.0.21 (same as `Arena_Backend`), Gradle Kotlin DSL, kotlinx.serialization (event JSON for the client), kotlin.test + JUnit 5.

## Global Constraints

- Determinism: same inputs + same seed ⇒ byte-identical `MatchResult`. No `Math.random()`, no wall clock.
- No Ktor, Exposed, Koin or coroutine dependencies in `match-engine`.
- One engine for all sports; sport differences live only in `SportProfile` data.
- Event list must be playable by the client as highlights: every event has `minute`, `type`, `team`, optional `athleteId`, optional `detail`.
- Football targets (public-league baseline, equal teams): mean goals/match 2.4–3.0, draw share 22–30%, home win 40–48%, ≤ 2% matches with 7+ goals.
- Balance tests are statistical: they use fixed seeds and generous bounds so they never flake.
- Repo: `/Users/kushpe/StudioProjects/Arena/Arena-Game-Backend`. Backend code base will follow `Arena_Backend` conventions (`com.proplayarena`-style package → here `com.arenacity`).

---

## File structure

```
Arena-Game-Backend/
  settings.gradle.kts                include("match-engine")
  build.gradle.kts                   plugins versions only
  gradle/ gradlew ...                copied from Arena_Backend (wrapper 8.x)
  match-engine/
    build.gradle.kts
    src/main/kotlin/com/arenacity/engine/
      model/Athlete.kt               Athlete, Position, Attributes
      model/Lineup.kt                Lineup (starters, bench, captain), Tactics, Style, Tempo, Plan
      model/Sport.kt                 SportProfile + FOOTBALL profile constants
      model/MatchEvent.kt            MatchEvent, EventType, MatchResult, Score
      sim/Strength.kt                team strength aggregation from attributes × tactics
      sim/Possession.kt              one possession resolution (chance → shot → goal / save / miss)
      sim/MatchSimulator.kt          simulate(home, away, sport, seed): MatchResult
      balance/Balance.kt             main(): runs N matches, prints stats
    src/test/kotlin/com/arenacity/engine/
      StrengthTest.kt
      PossessionTest.kt
      MatchSimulatorTest.kt
      BalanceTest.kt
```

---

### Task 1: Gradle project and engine module skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `match-engine/build.gradle.kts`, `gradle.properties`, `.gitignore`; copy `gradlew`, `gradlew.bat`, `gradle/wrapper/*` from `../Arena_Backend`.
- Create: `match-engine/src/test/kotlin/com/arenacity/engine/SmokeTest.kt`

- [ ] **Step 1: Copy wrapper and write build files**

```bash
cd /Users/kushpe/StudioProjects/Arena/Arena-Game-Backend
cp -R ../Arena_Backend/gradle ../Arena_Backend/gradlew ../Arena_Backend/gradlew.bat .
```

`settings.gradle.kts`:
```kotlin
rootProject.name = "arena-game-backend"
include("match-engine")
```

`build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}
```

`match-engine/build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.arenacity"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.arenacity.engine.balance.BalanceKt") }

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
```

`gradle.properties`:
```
org.gradle.jvmargs=-Xmx2g
kotlin.code.style=official
org.gradle.caching=true
```

`.gitignore`:
```
.gradle/
build/
.idea/
*.iml
local.properties
.DS_Store
```

- [ ] **Step 2: Smoke test**

`match-engine/src/test/kotlin/com/arenacity/engine/SmokeTest.kt`:
```kotlin
package com.arenacity.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun `build works`() = assertEquals(4, 2 + 2)
}
```

- [ ] **Step 3: Run**

Run: `./gradlew :match-engine:test --console=plain -q` → Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 4: Commit**

```bash
git init -b main 2>/dev/null; git add -A && git commit -m "match-engine: gradle skeleton"
```

---

### Task 2: Domain model

**Files:**
- Create: `match-engine/src/main/kotlin/com/arenacity/engine/model/Athlete.kt`, `Lineup.kt`, `Sport.kt`, `MatchEvent.kt`
- Test: `match-engine/src/test/kotlin/com/arenacity/engine/ModelTest.kt`

**Interfaces (produced, used by every later task):**

```kotlin
enum class Position { GK, DEF, MID, FWD }                      // football roles; other sports map onto these
@Serializable data class Attributes(val attack: Int, val defense: Int, val physical: Int, val technique: Int, val mental: Int) // 1..99
@Serializable data class Athlete(val id: String, val name: String, val position: Position, val attributes: Attributes,
                                 val fatigue: Int = 0 /*0..100*/, val injured: Boolean = false, val morale: Int = 50 /*0..100*/) {
    val overall: Int  // weighted by position, see Task 2 code
}
enum class Style { ATTACK, BALANCED, DEFEND }
enum class Tempo { HIGH, LOW }
enum class Plan { NONE, PRESSING, COUNTER, POSSESSION, LONG_BALL }
@Serializable data class Tactics(val style: Style = Style.BALANCED, val tempo: Tempo = Tempo.LOW, val plan: Plan = Plan.NONE)
@Serializable data class Lineup(val teamId: String, val starters: List<Athlete>, val bench: List<Athlete> = emptyList(), val tactics: Tactics = Tactics())
@Serializable data class SportProfile(val id: String, val startersCount: Int, val possessions: Int, val baseChanceRate: Double,
                                      val baseConversion: Double, val scoreName: String, val durationMinutes: Int,
                                      val homeAdvantage: Double, val maxSubs: Int)
val FOOTBALL = SportProfile("football", 11, 120, 0.22, 0.11, "goal", 90, 0.06, 5)
enum class EventType { KICKOFF, CHANCE, SHOT, GOAL, SAVE, MISS, INJURY, SUBSTITUTION, HALFTIME, FULLTIME, YELLOW_CARD }
@Serializable data class MatchEvent(val minute: Int, val type: EventType, val team: String, val athleteId: String? = null, val detail: String? = null)
@Serializable data class Score(val home: Int, val away: Int)
@Serializable data class MatchResult(val sport: String, val seed: Long, val homeTeam: String, val awayTeam: String, val score: Score, val events: List<MatchEvent>,
                                     val homePossession: Int /*percent*/, val homeShots: Int, val awayShots: Int)
```

- [ ] **Step 1: Failing test**

`ModelTest.kt`:
```kotlin
package com.arenacity.engine

import com.arenacity.engine.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTest {
    private fun a(pos: Position, v: Int) = Athlete("id-$pos-$v", "n", pos, Attributes(v, v, v, v, v))

    @Test
    fun `overall weights position`() {
        val gk = Athlete("g", "g", Position.GK, Attributes(attack = 10, defense = 80, physical = 60, technique = 50, mental = 70))
        val fw = Athlete("f", "f", Position.FWD, Attributes(attack = 80, defense = 10, physical = 60, technique = 50, mental = 70))
        assertTrue(gk.overall > 55 && fw.overall > 55, "specialists are rated by their role: gk=${gk.overall} fw=${fw.overall}")
        assertEquals(a(Position.MID, 70).overall, 70)
    }

    @Test
    fun `result serializes to json and back`() {
        val r = MatchResult("football", 1L, "h", "a", Score(2, 1), listOf(MatchEvent(12, EventType.GOAL, "h", "x")), 55, 10, 7)
        val json = Json.encodeToString(r)
        assertEquals(r, Json.decodeFromString<MatchResult>(json))
    }

    @Test
    fun `football profile`() {
        assertEquals(11, FOOTBALL.startersCount)
        assertEquals(90, FOOTBALL.durationMinutes)
    }
}
```

- [ ] **Step 2: Run to verify failure** → `./gradlew :match-engine:test -q` fails to compile (`Unresolved reference`).

- [ ] **Step 3: Implement**

`model/Athlete.kt`:
```kotlin
package com.arenacity.engine.model

import kotlinx.serialization.Serializable

enum class Position { GK, DEF, MID, FWD }

@Serializable
data class Attributes(val attack: Int, val defense: Int, val physical: Int, val technique: Int, val mental: Int) {
    init { require(listOf(attack, defense, physical, technique, mental).all { it in 1..99 }) { "attributes must be 1..99" } }
}

@Serializable
data class Athlete(
    val id: String,
    val name: String,
    val position: Position,
    val attributes: Attributes,
    val fatigue: Int = 0,
    val injured: Boolean = false,
    val morale: Int = 50,
) {
    /** Role-weighted rating, 1..99. Weights per position sum to 1. */
    val overall: Int
        get() {
            val a = attributes
            val w = when (position) {
                Position.GK -> doubleArrayOf(0.05, 0.45, 0.15, 0.15, 0.20)
                Position.DEF -> doubleArrayOf(0.10, 0.40, 0.20, 0.15, 0.15)
                Position.MID -> doubleArrayOf(0.20, 0.20, 0.20, 0.20, 0.20)
                Position.FWD -> doubleArrayOf(0.45, 0.05, 0.15, 0.20, 0.15)
            }
            val v = a.attack * w[0] + a.defense * w[1] + a.physical * w[2] + a.technique * w[3] + a.mental * w[4]
            return v.toInt().coerceIn(1, 99)
        }

    /** Effective rating after fatigue and morale. */
    val effective: Double
        get() = overall * (1.0 - fatigue / 100.0 * 0.35) * (0.9 + morale / 100.0 * 0.2)
}
```

`model/Lineup.kt`:
```kotlin
package com.arenacity.engine.model

import kotlinx.serialization.Serializable

enum class Style { ATTACK, BALANCED, DEFEND }
enum class Tempo { HIGH, LOW }
enum class Plan { NONE, PRESSING, COUNTER, POSSESSION, LONG_BALL }

@Serializable
data class Tactics(val style: Style = Style.BALANCED, val tempo: Tempo = Tempo.LOW, val plan: Plan = Plan.NONE)

@Serializable
data class Lineup(
    val teamId: String,
    val starters: List<Athlete>,
    val bench: List<Athlete> = emptyList(),
    val tactics: Tactics = Tactics(),
)
```

`model/Sport.kt`:
```kotlin
package com.arenacity.engine.model

import kotlinx.serialization.Serializable

/**
 * Everything that differs between sports. The simulator itself is sport-agnostic.
 * @param possessions number of possession cycles per match
 * @param baseChanceRate probability a possession produces a scoring chance for equal teams
 * @param baseConversion probability a chance becomes a score for equal teams
 */
@Serializable
data class SportProfile(
    val id: String,
    val startersCount: Int,
    val possessions: Int,
    val baseChanceRate: Double,
    val baseConversion: Double,
    val scoreName: String,
    val durationMinutes: Int,
    val homeAdvantage: Double,
    val maxSubs: Int,
)

val FOOTBALL = SportProfile(
    id = "football", startersCount = 11, possessions = 120, baseChanceRate = 0.22, baseConversion = 0.11,
    scoreName = "goal", durationMinutes = 90, homeAdvantage = 0.06, maxSubs = 5,
)
```

`model/MatchEvent.kt`:
```kotlin
package com.arenacity.engine.model

import kotlinx.serialization.Serializable

enum class EventType { KICKOFF, CHANCE, SHOT, GOAL, SAVE, MISS, INJURY, SUBSTITUTION, HALFTIME, FULLTIME, YELLOW_CARD }

@Serializable
data class MatchEvent(val minute: Int, val type: EventType, val team: String, val athleteId: String? = null, val detail: String? = null)

@Serializable
data class Score(val home: Int, val away: Int)

@Serializable
data class MatchResult(
    val sport: String,
    val seed: Long,
    val homeTeam: String,
    val awayTeam: String,
    val score: Score,
    val events: List<MatchEvent>,
    val homePossession: Int,
    val homeShots: Int,
    val awayShots: Int,
)
```

- [ ] **Step 4: Run tests** → all pass.
- [ ] **Step 5: Commit** `git add -A && git commit -m "match-engine: domain model"`

---

### Task 3: Team strength

**Files:**
- Create: `sim/Strength.kt`; Test: `StrengthTest.kt`

**Interfaces:**
```kotlin
data class TeamStrength(val attack: Double, val defense: Double, val midfield: Double, val keeper: Double, val tempoCost: Double)
object Strength {
    fun of(lineup: Lineup, sport: SportProfile, isHome: Boolean): TeamStrength
}
```
Rules: strength from the first `sport.startersCount` non-injured starters' `effective` ratings grouped by position (attack = FWD mean ×0.6 + MID mean ×0.4; defense = DEF mean ×0.6 + MID ×0.2 + GK ×0.2; midfield = MID mean; keeper = best GK or 30 if none). Style: ATTACK +8% attack −8% defense; DEFEND inverse. Plan: PRESSING +5% midfield, +fatigue cost; COUNTER +6% attack when style DEFEND; POSSESSION +6% midfield; LONG_BALL +4% attack −3% midfield. Tempo HIGH ×1.04 all, `tempoCost = 1.5` (fatigue per possession unit), LOW `tempoCost = 1.0`. Home: ×(1 + homeAdvantage) on attack and midfield. Missing players (fewer than `startersCount` fit) scale every component by `fit / startersCount`.

- [ ] **Step 1: Failing test**

```kotlin
package com.arenacity.engine

import com.arenacity.engine.model.*
import com.arenacity.engine.sim.Strength
import kotlin.test.Test
import kotlin.test.assertTrue

class StrengthTest {
    private fun squad(v: Int, team: String = "t") = Lineup(team, Position.values().flatMap { p ->
        val n = when (p) { Position.GK -> 1; Position.DEF -> 4; Position.MID -> 4; Position.FWD -> 2 }
        (1..n).map { Athlete("$team-$p-$it", "n", p, Attributes(v, v, v, v, v)) }
    })

    @Test
    fun `better players give higher strength`() {
        val weak = Strength.of(squad(50), FOOTBALL, false)
        val strong = Strength.of(squad(80), FOOTBALL, false)
        assertTrue(strong.attack > weak.attack && strong.defense > weak.defense && strong.keeper > weak.keeper)
    }

    @Test
    fun `attacking style trades defense for attack`() {
        val base = Strength.of(squad(70), FOOTBALL, false)
        val att = Strength.of(squad(70).copy(tactics = Tactics(style = Style.ATTACK)), FOOTBALL, false)
        assertTrue(att.attack > base.attack && att.defense < base.defense)
    }

    @Test
    fun `home advantage applies`() {
        val away = Strength.of(squad(70), FOOTBALL, false)
        val home = Strength.of(squad(70), FOOTBALL, true)
        assertTrue(home.attack > away.attack)
    }

    @Test
    fun `missing players weaken the team`() {
        val full = Strength.of(squad(70), FOOTBALL, false)
        val short = Strength.of(squad(70).let { it.copy(starters = it.starters.drop(3)) }, FOOTBALL, false)
        assertTrue(short.attack < full.attack * 0.8)
    }
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

`sim/Strength.kt`:
```kotlin
package com.arenacity.engine.sim

import com.arenacity.engine.model.*

data class TeamStrength(val attack: Double, val defense: Double, val midfield: Double, val keeper: Double, val tempoCost: Double)

object Strength {
    fun of(lineup: Lineup, sport: SportProfile, isHome: Boolean): TeamStrength {
        val fit = lineup.starters.filter { !it.injured }.take(sport.startersCount)
        val coverage = fit.size.toDouble() / sport.startersCount
        fun mean(p: Position, fallback: Double) = fit.filter { it.position == p }.map { it.effective }.let { if (it.isEmpty()) fallback else it.average() }
        val fwd = mean(Position.FWD, 35.0)
        val mid = mean(Position.MID, 35.0)
        val def = mean(Position.DEF, 35.0)
        val gk = fit.filter { it.position == Position.GK }.maxOfOrNull { it.effective } ?: 30.0

        var attack = fwd * 0.6 + mid * 0.4
        var defense = def * 0.6 + mid * 0.2 + gk * 0.2
        var midfield = mid
        val t = lineup.tactics
        when (t.style) {
            Style.ATTACK -> { attack *= 1.08; defense *= 0.92 }
            Style.DEFEND -> { attack *= 0.92; defense *= 1.08 }
            Style.BALANCED -> Unit
        }
        when (t.plan) {
            Plan.PRESSING -> midfield *= 1.05
            Plan.COUNTER -> if (t.style == Style.DEFEND) attack *= 1.06
            Plan.POSSESSION -> midfield *= 1.06
            Plan.LONG_BALL -> { attack *= 1.04; midfield *= 0.97 }
            Plan.NONE -> Unit
        }
        val tempoMul = if (t.tempo == Tempo.HIGH) 1.04 else 1.0
        val tempoCost = (if (t.tempo == Tempo.HIGH) 1.5 else 1.0) * (if (t.plan == Plan.PRESSING) 1.2 else 1.0)
        val homeMul = if (isHome) 1.0 + sport.homeAdvantage else 1.0
        return TeamStrength(
            attack = attack * tempoMul * homeMul * coverage,
            defense = defense * tempoMul * coverage,
            midfield = midfield * tempoMul * homeMul * coverage,
            keeper = gk * coverage,
            tempoCost = tempoCost,
        )
    }
}
```

- [ ] **Step 4: Run tests** → pass. **Step 5: Commit** `git commit -am "match-engine: team strength"`

---

### Task 4: Possession resolution

**Files:**
- Create: `sim/Possession.kt`; Test: `PossessionTest.kt`

**Interfaces:**
```kotlin
class Rng(seed: Long) { fun next(): Double /*[0,1)*/; fun pick<T>(list: List<T>): T }   // wraps java.util.SplittableRandom
sealed interface PossessionOutcome { object Nothing; data class Chance(val shooterId: String, val scored: Boolean, val saved: Boolean) }
object Possession {
    /** Who has the ball: compares midfields. Returns true if home wins the possession. */
    fun homeWins(home: TeamStrength, away: TeamStrength, rng: Rng): Boolean
    /** Resolve an attack by `att` against `def`. */
    fun attack(att: TeamStrength, def: TeamStrength, attackers: List<Athlete>, sport: SportProfile, rng: Rng): PossessionOutcome
}
```
Formulas: `pHome = home.midfield / (home.midfield + away.midfield)`. Chance probability `pc = sport.baseChanceRate * ratio(att.attack, def.defense)` where `ratio(a, d) = (a / d).pow(1.6)` clamped to `[0.25, 4.0]`. Shooter chosen weighted by `attributes.attack` among FWD (weight 3), MID (1), DEF (0.2). Conversion `pg = sport.baseConversion * ratio(shooter.effective, def.keeper).coerceIn(0.4, 2.5)`; if not scored, saved with probability 0.55 else missed.

- [ ] **Step 1: Failing test**

```kotlin
package com.arenacity.engine

import com.arenacity.engine.model.*
import com.arenacity.engine.sim.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PossessionTest {
    private val strong = TeamStrength(85.0, 85.0, 85.0, 85.0, 1.0)
    private val weak = TeamStrength(50.0, 50.0, 50.0, 50.0, 1.0)
    private val fwds = (1..3).map { Athlete("f$it", "n", Position.FWD, Attributes(80, 20, 60, 60, 60)) }

    @Test
    fun `rng is deterministic`() {
        val a = Rng(42).let { r -> List(5) { r.next() } }
        val b = Rng(42).let { r -> List(5) { r.next() } }
        assertEquals(a, b)
    }

    @Test
    fun `stronger midfield wins more possessions`() {
        val rng = Rng(1)
        val wins = (1..5000).count { Possession.homeWins(strong, weak, rng) }
        assertTrue(wins in 2900..3500, "wins=$wins")
    }

    @Test
    fun `stronger attack creates more chances`() {
        val rng = Rng(2)
        val vsWeak = (1..5000).count { Possession.attack(strong, weak, fwds, FOOTBALL, rng) is PossessionOutcome.Chance }
        val vsStrong = (1..5000).count { Possession.attack(weak, strong, fwds, FOOTBALL, rng) is PossessionOutcome.Chance }
        assertTrue(vsWeak > vsStrong * 2, "vsWeak=$vsWeak vsStrong=$vsStrong")
    }

    @Test
    fun `chances convert at a plausible rate`() {
        val rng = Rng(3)
        val chances = (1..20000).mapNotNull { Possession.attack(weak, weak, fwds, FOOTBALL, rng) as? PossessionOutcome.Chance }
        val rate = chances.count { it.scored }.toDouble() / chances.size
        assertTrue(rate in 0.08..0.22, "rate=$rate")
    }
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

`sim/Possession.kt`:
```kotlin
package com.arenacity.engine.sim

import com.arenacity.engine.model.*
import java.util.SplittableRandom
import kotlin.math.pow

class Rng(seed: Long) {
    private val r = SplittableRandom(seed)
    fun next(): Double = r.nextDouble()
    fun <T> pick(list: List<T>): T = list[r.nextInt(list.size)]
    fun <T> weighted(items: List<T>, weight: (T) -> Double): T {
        val total = items.sumOf(weight)
        var x = next() * total
        for (i in items) { x -= weight(i); if (x <= 0) return i }
        return items.last()
    }
}

sealed interface PossessionOutcome {
    data object Nothing : PossessionOutcome
    data class Chance(val shooterId: String, val scored: Boolean, val saved: Boolean) : PossessionOutcome
}

object Possession {
    private fun ratio(a: Double, d: Double, lo: Double = 0.25, hi: Double = 4.0) = (a / d).pow(1.6).coerceIn(lo, hi)

    fun homeWins(home: TeamStrength, away: TeamStrength, rng: Rng): Boolean =
        rng.next() < home.midfield / (home.midfield + away.midfield)

    fun attack(att: TeamStrength, def: TeamStrength, attackers: List<Athlete>, sport: SportProfile, rng: Rng): PossessionOutcome {
        val pc = (sport.baseChanceRate * ratio(att.attack, def.defense)).coerceIn(0.0, 0.95)
        if (rng.next() >= pc) return PossessionOutcome.Nothing
        val pool = attackers.filter { !it.injured && it.position != Position.GK }
        if (pool.isEmpty()) return PossessionOutcome.Nothing
        val shooter = rng.weighted(pool) { a ->
            val role = when (a.position) { Position.FWD -> 3.0; Position.MID -> 1.0; Position.DEF -> 0.2; Position.GK -> 0.0 }
            role * a.attributes.attack
        }
        val pg = (sport.baseConversion * ratio(shooter.effective, def.keeper, 0.4, 2.5)).coerceIn(0.0, 0.9)
        val scored = rng.next() < pg
        val saved = !scored && rng.next() < 0.55
        return PossessionOutcome.Chance(shooter.id, scored, saved)
    }
}
```

- [ ] **Step 4: Run tests** → pass (adjust bounds only if the maths is wrong, not to hide a bug). **Step 5: Commit** `git commit -am "match-engine: possession resolution"`

---

### Task 5: Match simulator

**Files:**
- Create: `sim/MatchSimulator.kt`; Test: `MatchSimulatorTest.kt`

**Interfaces:**
```kotlin
object MatchSimulator {
    fun simulate(home: Lineup, away: Lineup, sport: SportProfile = FOOTBALL, seed: Long): MatchResult
}
```
Algorithm:
1. `rng = Rng(seed)`; strengths via `Strength.of`.
2. Events: `KICKOFF` at minute 0. For `i in 0 until sport.possessions`: minute = `(i * sport.durationMinutes) / sport.possessions`; insert `HALFTIME` once minute ≥ duration/2. Possession winner by `homeWins`; outcome by `attack`. Emit `CHANCE` then `GOAL` / `SAVE` / `MISS` with `athleteId`. Count shots and possessions per team.
3. Fatigue: each possession adds `tempoCost * 0.15` fatigue to the attacking team's current players (tracked in a mutable copy); strengths are recomputed every 20 possessions from the mutable squads so high tempo fades late.
4. Injury: per possession 0.0015 chance for a random non-injured starter of the team with the ball; emit `INJURY`, mark injured; if bench has a same-position (else any) fit player and subs < `maxSubs`, emit `SUBSTITUTION` and swap.
5. `FULLTIME` at `durationMinutes`. Possession percent = home possessions / total.

- [ ] **Step 1: Failing test**

```kotlin
package com.arenacity.engine

import com.arenacity.engine.model.*
import com.arenacity.engine.sim.MatchSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchSimulatorTest {
    private fun squad(v: Int, team: String, bench: Int = 3) = Lineup(team,
        starters = listOf(Position.GK to 1, Position.DEF to 4, Position.MID to 4, Position.FWD to 2).flatMap { (p, n) -> (1..n).map { Athlete("$team-$p-$it", "n", p, Attributes(v, v, v, v, v)) } },
        bench = (1..bench).map { Athlete("$team-bench-$it", "n", Position.MID, Attributes(v, v, v, v, v)) })

    @Test
    fun `deterministic for the same seed`() {
        val a = MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, 123L)
        val b = MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, 123L)
        assertEquals(a, b)
        val c = MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, 124L)
        assertTrue(a != c)
    }

    @Test
    fun `events are ordered and bracketed`() {
        val r = MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, 7L)
        assertEquals(EventType.KICKOFF, r.events.first().type)
        assertEquals(EventType.FULLTIME, r.events.last().type)
        assertEquals(1, r.events.count { it.type == EventType.HALFTIME })
        assertTrue(r.events.zipWithNext().all { (x, y) -> x.minute <= y.minute })
        assertEquals(r.score.home, r.events.count { it.type == EventType.GOAL && it.team == "h" })
        assertEquals(r.score.away, r.events.count { it.type == EventType.GOAL && it.team == "a" })
    }

    @Test
    fun `much stronger team wins most matches`() {
        val wins = (1..300).count { MatchSimulator.simulate(squad(85, "h"), squad(55, "a"), FOOTBALL, it.toLong()).let { r -> r.score.home > r.score.away } }
        assertTrue(wins > 240, "wins=$wins")
    }

    @Test
    fun `injured starter gets substituted`() {
        val results = (1..400).map { MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, it.toLong()) }
        val withInjury = results.filter { r -> r.events.any { it.type == EventType.INJURY } }
        assertTrue(withInjury.isNotEmpty())
        assertTrue(withInjury.all { r -> r.events.any { it.type == EventType.SUBSTITUTION } })
    }
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

`sim/MatchSimulator.kt`:
```kotlin
package com.arenacity.engine.sim

import com.arenacity.engine.model.*

object MatchSimulator {
    private class Side(val lineup: Lineup, val isHome: Boolean) {
        val starters = lineup.starters.toMutableList()
        val bench = lineup.bench.toMutableList()
        var subs = 0
        var possessions = 0
        var shots = 0
        var goals = 0
        fun strength(sport: SportProfile) = Strength.of(lineup.copy(starters = starters), sport, isHome)
    }

    fun simulate(home: Lineup, away: Lineup, sport: SportProfile = FOOTBALL, seed: Long): MatchResult {
        val rng = Rng(seed)
        val h = Side(home, true)
        val a = Side(away, false)
        var hs = h.strength(sport)
        var `as` = a.strength(sport)
        val events = mutableListOf(MatchEvent(0, EventType.KICKOFF, home.teamId))
        var halftime = false

        for (i in 0 until sport.possessions) {
            val minute = (i * sport.durationMinutes) / sport.possessions
            if (!halftime && minute >= sport.durationMinutes / 2) {
                events += MatchEvent(sport.durationMinutes / 2, EventType.HALFTIME, home.teamId)
                halftime = true
            }
            if (i > 0 && i % 20 == 0) { hs = h.strength(sport); `as` = a.strength(sport) }

            val homeBall = Possession.homeWins(hs, `as`, rng)
            val (att, def, attS, defS) = if (homeBall) listOf(h, a, hs, `as`) else listOf(a, h, `as`, hs)
            att as Side; def as Side; attS as TeamStrength; defS as TeamStrength
            att.possessions++
            fatigue(att, attS.tempoCost * 0.15)
            injuries(att, minute, rng, sport, events)

            when (val o = Possession.attack(attS, defS, att.starters, sport, rng)) {
                PossessionOutcome.Nothing -> Unit
                is PossessionOutcome.Chance -> {
                    att.shots++
                    events += MatchEvent(minute, EventType.CHANCE, att.lineup.teamId, o.shooterId)
                    when {
                        o.scored -> { att.goals++; events += MatchEvent(minute, EventType.GOAL, att.lineup.teamId, o.shooterId, "${att.goals}-${def.goals}") }
                        o.saved -> events += MatchEvent(minute, EventType.SAVE, def.lineup.teamId, def.starters.firstOrNull { it.position == Position.GK }?.id)
                        else -> events += MatchEvent(minute, EventType.MISS, att.lineup.teamId, o.shooterId)
                    }
                }
            }
        }
        events += MatchEvent(sport.durationMinutes, EventType.FULLTIME, home.teamId, detail = "${h.goals}-${a.goals}")
        val total = (h.possessions + a.possessions).coerceAtLeast(1)
        return MatchResult(sport.id, seed, home.teamId, away.teamId, Score(h.goals, a.goals), events, h.possessions * 100 / total, h.shots, a.shots)
    }

    private fun fatigue(side: Side, amount: Double) {
        for (i in side.starters.indices) {
            val p = side.starters[i]
            side.starters[i] = p.copy(fatigue = (p.fatigue + amount).toInt().coerceIn(0, 100))
        }
    }

    private fun injuries(side: Side, minute: Int, rng: Rng, sport: SportProfile, events: MutableList<MatchEvent>) {
        if (rng.next() >= 0.0015) return
        val fit = side.starters.filter { !it.injured }
        if (fit.isEmpty()) return
        val victim = rng.pick(fit)
        val idx = side.starters.indexOf(victim)
        side.starters[idx] = victim.copy(injured = true)
        events += MatchEvent(minute, EventType.INJURY, side.lineup.teamId, victim.id)
        if (side.subs >= sport.maxSubs) return
        val sub = side.bench.firstOrNull { !it.injured && it.position == victim.position } ?: side.bench.firstOrNull { !it.injured } ?: return
        side.bench.remove(sub)
        side.starters[idx] = sub
        side.subs++
        events += MatchEvent(minute, EventType.SUBSTITUTION, side.lineup.teamId, sub.id, "for ${victim.id}")
    }
}
```

- [ ] **Step 4: Run tests** → pass. **Step 5: Commit** `git commit -am "match-engine: simulator"`

---

### Task 6: Balance CLI and statistical tests

**Files:**
- Create: `balance/Balance.kt`; Test: `BalanceTest.kt`

**Interfaces:**
```kotlin
data class Stats(val matches: Int, val meanGoals: Double, val drawShare: Double, val homeWinShare: Double, val awayWinShare: Double, val highScoring: Double, val meanShotsHome: Double, val meanShotsAway: Double)
object Balance {
    fun squad(rating: Int, team: String, seed: Long): Lineup   // ratings jittered ±6 per attribute
    fun run(homeRating: Int, awayRating: Int, n: Int, sport: SportProfile = FOOTBALL, tactics: Tactics = Tactics()): Stats
}
fun main(args: Array<String>)   // prints a table for (70 vs 70), (80 vs 60), (60 vs 80), (70 vs 70 ATTACK/HIGH)
```

- [ ] **Step 1: Failing test**

```kotlin
package com.arenacity.engine

import com.arenacity.engine.balance.Balance
import kotlin.test.Test
import kotlin.test.assertTrue

class BalanceTest {
    @Test
    fun `equal teams produce football-like results`() {
        val s = Balance.run(70, 70, 2000)
        assertTrue(s.meanGoals in 2.4..3.0, "meanGoals=${s.meanGoals}")
        assertTrue(s.drawShare in 0.22..0.30, "draws=${s.drawShare}")
        assertTrue(s.homeWinShare in 0.40..0.48, "homeWins=${s.homeWinShare}")
        assertTrue(s.highScoring <= 0.02, "7+ goals share=${s.highScoring}")
    }

    @Test
    fun `20 rating points is a clear but not absolute edge`() {
        val s = Balance.run(80, 60, 2000)
        assertTrue(s.homeWinShare in 0.70..0.90, "homeWins=${s.homeWinShare}")
        assertTrue(s.awayWinShare >= 0.03, "awayWins=${s.awayWinShare}")
    }
}
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**

`balance/Balance.kt`:
```kotlin
package com.arenacity.engine.balance

import com.arenacity.engine.model.*
import com.arenacity.engine.sim.MatchSimulator
import com.arenacity.engine.sim.Rng

data class Stats(
    val matches: Int, val meanGoals: Double, val drawShare: Double, val homeWinShare: Double, val awayWinShare: Double,
    val highScoring: Double, val meanShotsHome: Double, val meanShotsAway: Double,
)

object Balance {
    private val shape = listOf(Position.GK to 1, Position.DEF to 4, Position.MID to 4, Position.FWD to 2)

    fun squad(rating: Int, team: String, seed: Long): Lineup {
        val rng = Rng(seed)
        fun v() = (rating + (rng.next() * 12 - 6)).toInt().coerceIn(1, 99)
        val starters = shape.flatMap { (p, n) -> (1..n).map { Athlete("$team-$p-$it", "$team $p $it", p, Attributes(v(), v(), v(), v(), v())) } }
        val bench = listOf(Position.GK, Position.DEF, Position.MID, Position.MID, Position.FWD).mapIndexed { i, p -> Athlete("$team-b$i", "$team bench $i", p, Attributes(v(), v(), v(), v(), v())) }
        return Lineup(team, starters, bench)
    }

    fun run(homeRating: Int, awayRating: Int, n: Int, sport: SportProfile = FOOTBALL, tactics: Tactics = Tactics()): Stats {
        var goals = 0; var draws = 0; var hw = 0; var aw = 0; var high = 0; var sh = 0; var sa = 0
        for (i in 1..n) {
            val r = MatchSimulator.simulate(squad(homeRating, "H", i.toLong()).copy(tactics = tactics), squad(awayRating, "A", 100_000L + i), sport, i.toLong())
            val t = r.score.home + r.score.away
            goals += t; sh += r.homeShots; sa += r.awayShots
            if (t >= 7) high++
            when { r.score.home > r.score.away -> hw++; r.score.home < r.score.away -> aw++; else -> draws++ }
        }
        return Stats(n, goals.toDouble() / n, draws.toDouble() / n, hw.toDouble() / n, aw.toDouble() / n, high.toDouble() / n, sh.toDouble() / n, sa.toDouble() / n)
    }
}

fun main(args: Array<String>) {
    val n = args.firstOrNull()?.toIntOrNull() ?: 3000
    println("scenario            | goals | draw  | homeW | awayW | 7+    | shotsH | shotsA")
    for ((label, s) in listOf(
        "70 vs 70" to Balance.run(70, 70, n),
        "80 vs 60" to Balance.run(80, 60, n),
        "60 vs 80" to Balance.run(60, 80, n),
        "70 vs 70 ATT/HIGH" to Balance.run(70, 70, n, tactics = Tactics(Style.ATTACK, Tempo.HIGH)),
        "70 vs 70 DEF/COUNTER" to Balance.run(70, 70, n, tactics = Tactics(Style.DEFEND, Tempo.LOW, Plan.COUNTER)),
    )) {
        println("%-19s | %.2f  | %.2f  | %.2f  | %.2f  | %.3f | %.1f   | %.1f".format(label, s.meanGoals, s.drawShare, s.homeWinShare, s.awayWinShare, s.highScoring, s.meanShotsHome, s.meanShotsAway))
    }
}
```

- [ ] **Step 4: Tune**

Run: `./gradlew :match-engine:run --args=3000 -q` and read the table. Adjust only `FOOTBALL.baseChanceRate`, `FOOTBALL.baseConversion`, `homeAdvantage` and the `ratio` exponent until the 70-vs-70 row meets Global Constraints. Then run `./gradlew :match-engine:test` → all pass.

- [ ] **Step 5: Commit** `git commit -am "match-engine: balance CLI and statistical tests"`

---

### Task 7: Documentation

- [ ] `Arena-Game-Backend/README.md`: what the repo is, module list, how to run tests and the balance table, the determinism rule, how a new sport is added (a new `SportProfile` + a balance row + a `BalanceTest` case).
- [ ] Commit.

---

## Self-review

- Spec §4 (симулятор: детерминированный, seed, один движок, таблица параметров на вид спорта, события с таймкодом, тактика 3 настройки, автозамены, травмы) → Tasks 2–5. Spec §8 testing (детерминизм + статистика баланса) → Task 6. Usage by backend → later plan.
- No placeholders. Types consistent: `Rng`, `TeamStrength`, `PossessionOutcome`, `MatchResult` names match across tasks.
