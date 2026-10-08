package com.matchdayclub.domain.usecase.league

import com.matchdayclub.core.push.FcmSender
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Fixture
import java.util.UUID

/** Builds and sends the "matchday played" push to the human club of each played fixture. */
class MatchdayNotifier(private val fcm: FcmSender) {
    fun message(f: Fixture, me: Club, opponent: Club): Pair<String, String> {
        val mine = if (f.homeClubId == me.id) f.homeScore else f.awayScore
        val theirs = if (f.homeClubId == me.id) f.awayScore else f.homeScore
        val verdict = when { mine!! > theirs!! -> "Victory!"; mine == theirs -> "Draw"; else -> "Defeat" }
        return "Matchday played: $verdict" to "${me.name} $mine–$theirs ${opponent.name}. Open the match highlights."
    }

    suspend fun notify(f: Fixture, home: Club, away: Club) {
        for ((me, opp) in listOf(home to away, away to home)) {
            if (me.isBot) continue
            val (title, body) = message(f, me, opp)
            fcm.sendToUsers(listOf(me.userId), title, body, mapOf("fixtureId" to f.id.toString(), "type" to "MATCHDAY"))
        }
    }
}
