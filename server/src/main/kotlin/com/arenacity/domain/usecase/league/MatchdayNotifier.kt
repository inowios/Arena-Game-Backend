package com.arenacity.domain.usecase.league

import com.arenacity.common.i18n.Named
import com.arenacity.common.i18n.ServerI18n
import com.arenacity.core.push.FcmSender
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.Fixture
import com.arenacity.domain.model.MatchdayResult

/** Builds and sends the "matchday played" push to the human club of each played fixture, in the player's language. */
class MatchdayNotifier(private val fcm: FcmSender, private val inbox: com.arenacity.domain.usecase.account.InboxService? = null) {
    fun message(f: Fixture, me: Club, opponent: Club, result: MatchdayResult? = null, lang: String = "en"): Pair<String, String> {
        val home = f.homeClubId == me.id
        val mine = (if (home) f.homeScore else f.awayScore) ?: 0
        val theirs = (if (home) f.awayScore else f.homeScore) ?: 0
        val verdict = Named(when { mine > theirs -> "push.victory"; mine == theirs -> "push.draw"; else -> "push.defeat" })
        val body = ServerI18n.t("push.matchday_score", lang, me.name, mine, theirs, opponent.name)
        return ServerI18n.t("push.matchday_title", lang, verdict) to body
    }

    suspend fun notify(f: Fixture, home: Club, away: Club, result: MatchdayResult? = null) {
        for ((me, opp) in listOf(home to away, away to home)) {
            if (me.isBot) continue
            val mine = (if (f.homeClubId == me.id) f.homeScore else f.awayScore) ?: 0
            val theirs = (if (f.homeClubId == me.id) f.awayScore else f.homeScore) ?: 0
            inbox?.add(me.id, "MATCHDAY", mapOf("fixtureId" to f.id.toString(), "opponent" to opp.name, "mine" to mine.toString(), "theirs" to theirs.toString(),
                "verdict" to when { mine > theirs -> "WIN"; mine == theirs -> "DRAW"; else -> "LOSS" }, "multi" to "false"))
            fcm.sendLocalized(listOf(me.userId), mapOf("fixtureId" to f.id.toString(), "type" to "MATCHDAY")) { lang -> message(f, me, opp, result, lang) }
        }
    }
}
