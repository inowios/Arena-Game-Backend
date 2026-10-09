package com.matchdayclub.domain.usecase.league

import com.matchdayclub.common.i18n.Named
import com.matchdayclub.common.i18n.ServerI18n
import com.matchdayclub.core.push.FcmSender
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Fixture
import com.matchdayclub.domain.model.MatchdayResult

/** Builds and sends the "matchday played" push to the human club of each played fixture, in the player's language. */
class MatchdayNotifier(private val fcm: FcmSender, private val inbox: com.matchdayclub.domain.usecase.account.InboxService? = null) {
    fun message(f: Fixture, me: Club, opponent: Club, result: MatchdayResult? = null, lang: String = "en"): Pair<String, String> {
        val home = f.homeClubId == me.id
        val mine = (if (home) f.homePoints else f.awayPoints) ?: (if (home) f.homeScore else f.awayScore)!!
        val theirs = (if (home) f.awayPoints else f.homePoints) ?: (if (home) f.awayScore else f.homeScore)!!
        val verdict = Named(when { mine > theirs -> "push.victory"; mine == theirs -> "push.draw"; else -> "push.defeat" })
        val sections = result?.sections?.takeIf { it.size > 1 }?.joinToString(" · ") { s ->
            val my = if (home) s.homeScore else s.awayScore; val their = if (home) s.awayScore else s.homeScore
            "${ServerI18n.t("sport." + s.sport.uppercase(), lang)} $my–$their"
        }
        val myScore = if (home) f.homeScore else f.awayScore; val theirScore = if (home) f.awayScore else f.homeScore
        val body = if (sections != null) ServerI18n.t("push.matchday_sections", lang, me.name, opponent.name, sections)
                   else ServerI18n.t("push.matchday_score", lang, me.name, myScore, theirScore, opponent.name)
        return ServerI18n.t("push.matchday_title", lang, verdict) to body
    }

    suspend fun notify(f: Fixture, home: Club, away: Club, result: MatchdayResult? = null) {
        for ((me, opp) in listOf(home to away, away to home)) {
            if (me.isBot) continue
            val home = f.homeClubId == me.id
            val mine = (if (home) f.homePoints else f.awayPoints) ?: (if (home) f.homeScore else f.awayScore) ?: 0
            val theirs = (if (home) f.awayPoints else f.homePoints) ?: (if (home) f.awayScore else f.homeScore) ?: 0
            inbox?.add(me.id, "MATCHDAY", mapOf("fixtureId" to f.id.toString(), "opponent" to opp.name, "mine" to mine.toString(), "theirs" to theirs.toString(),
                "verdict" to when { mine > theirs -> "WIN"; mine == theirs -> "DRAW"; else -> "LOSS" }, "multi" to ((result?.sections?.size ?: 1) > 1).toString()))
            fcm.sendLocalized(listOf(me.userId), mapOf("fixtureId" to f.id.toString(), "type" to "MATCHDAY")) { lang -> message(f, me, opp, result, lang) }
        }
    }
}
