package com.matchdayclub.domain.usecase.league

import com.matchdayclub.core.push.FcmSender
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Fixture
import com.matchdayclub.domain.model.MatchdayResult

/** Builds and sends the "matchday played" push to the human club of each played fixture. */
class MatchdayNotifier(private val fcm: FcmSender) {
    fun message(f: Fixture, me: Club, opponent: Club, result: MatchdayResult? = null): Pair<String, String> {
        val home = f.homeClubId == me.id
        val mine = (if (home) f.homePoints else f.awayPoints) ?: (if (home) f.homeScore else f.awayScore)!!
        val theirs = (if (home) f.awayPoints else f.homePoints) ?: (if (home) f.awayScore else f.homeScore)!!
        val verdict = when { mine > theirs -> "Victory!"; mine == theirs -> "Draw"; else -> "Defeat" }
        val sections = result?.sections?.takeIf { it.size > 1 }?.joinToString(" · ") { s ->
            val my = if (home) s.homeScore else s.awayScore; val their = if (home) s.awayScore else s.homeScore
            "${s.sport.lowercase().replaceFirstChar { it.uppercase() }} $my–$their"
        }
        val myScore = if (home) f.homeScore else f.awayScore; val theirScore = if (home) f.awayScore else f.homeScore
        val body = if (sections != null) "${me.name} vs ${opponent.name}: $sections. Open the highlights."
                   else "${me.name} $myScore–$theirScore ${opponent.name}. Open the match highlights."
        return "Matchday played: $verdict" to body
    }

    suspend fun notify(f: Fixture, home: Club, away: Club, result: MatchdayResult? = null) {
        for ((me, opp) in listOf(home to away, away to home)) {
            if (me.isBot) continue
            val (title, body) = message(f, me, opp, result)
            fcm.sendToUsers(listOf(me.userId), title, body, mapOf("fixtureId" to f.id.toString(), "type" to "MATCHDAY"))
        }
    }
}
