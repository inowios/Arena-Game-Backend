package com.arenacity.domain.usecase.league

import com.arenacity.arena.ArenaSkills
import com.arenacity.arena.Engine
import com.arenacity.arena.HeroPick
import com.arenacity.arena.MatchEvent
import com.arenacity.arena.Runner
import com.arenacity.arena.SkillLibrary
import com.arenacity.arena.SmartAi
import com.arenacity.domain.model.MatchHero
import com.arenacity.domain.model.MatchLine
import com.arenacity.domain.model.MatchdayResult

/**
 * Plays a fixture as an Arena v4 match on the server (APP-AUDIT §2.15): the home triple is side 0, the away triple side 1,
 * SmartAi on both sides, everything seeded from the fixture ([seed]; [first] = the side that moves first, 0 home / 1 away:
 * the fixture's seed parity, so home does not always start). Win or loss only (the 40-turn limit gives the side with more
 * stamina the win, a tie the second mover). The result keeps the triples as they ended and a short log for the result card.
 */
object HeroMatch {
    const val HOME = "HOME"; const val AWAY = "AWAY"

    fun firstOf(seed: Long): Int = (seed and 1L).toInt()

    fun play(home: List<HeroPick>, away: List<HeroPick>, seed: Long, first: Int = firstOf(seed), lib: SkillLibrary = ArenaSkills.library): MatchdayResult {
        val s = Engine.newMatch(lib, home, away, seed.toULong(), null, first)
        val ev = ArrayList<MatchEvent>()
        Runner.play(s, SmartAi(seed.toULong() xor 0xA11CEUL), SmartAi(seed.toULong() xor 0xB0BUL), ev)
        check(s.finished) { "the match did not finish" }
        fun heroes(side: Int) = s.sides[side].fighters.filterNotNull().map { MatchHero(it.hero.id, it.level, it.stamina) }
        return MatchdayResult(3, homeWon = s.winner == 0, turns = s.turn, first = if (first == 0) HOME else AWAY, home = heroes(0), away = heroes(1), events = log(s.lib, s, ev))
    }

    /** The short log: a line per cast with the damage it dealt (the damage events that follow it in the same turn), the ongoing ticks, the knockouts, the end. */
    private fun log(lib: SkillLibrary, s: com.arenacity.arena.MatchState, ev: List<MatchEvent>): List<MatchLine> {
        val out = ArrayList<MatchLine>()
        fun side(i: Int) = if (i == 0) HOME else AWAY
        fun heroId(side: Int, slot: Int): String? = if (side in 0..1 && slot in 0..2) s.sides[side].fighters[slot]?.hero?.id else null
        var current: Int = -1
        for (e in ev) when (e.kind) {
            "cast" -> {
                val skillId = e.text?.substringBefore('|'); val skill = lib.skill(skillId)
                out += MatchLine(e.turn, side(e.side), "cast", heroId(e.side, e.slot), skill?.name ?: skillId, skillId, heroId(e.tside, e.tslot))
                current = out.size - 1
            }
            "damage" -> {
                val fromTick = e.text?.contains("|tick|") == true
                if (!fromTick && current >= 0 && out[current].turn == e.turn && out[current].side == side(e.side)) { val c = out[current]; out[current] = c.copy(damage = c.damage + e.value) }
            }
            "tick" -> if (e.text?.endsWith("|regen") != true) { val skill = lib.skill(e.text); out += MatchLine(e.turn, side(e.side), "tick", heroId(e.side, e.slot), skill?.name ?: e.text, e.text, heroId(e.tside, e.tslot), e.value) }
            "out" -> out += MatchLine(e.turn, side(e.side), "out", heroId(e.side, e.slot))
            "end" -> out += MatchLine(e.turn, side(e.side), "end", text = e.text)
        }
        return out
    }
}
