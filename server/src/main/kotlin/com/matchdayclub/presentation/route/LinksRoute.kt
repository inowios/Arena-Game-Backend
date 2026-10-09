package com.matchdayclub.presentation.route

import com.matchdayclub.domain.model.LeagueKind
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.LeagueRepository
import com.matchdayclub.domain.repository.SocialRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Invite links: https://<base>/l/{CODE} (private league) and /c/{CODE} (challenge) open the app through Android App Links /
 * iOS Universal Links when installed; otherwise this landing page offers the store and copies "MDC:L:CODE" to the clipboard,
 * which the app reads on first launch (deferred deep link without third-party SDKs).
 */
fun Route.linkRoutes() {
    val leagues: LeagueRepository by inject()
    val clubs: ClubRepository by inject()
    val social: SocialRepository by inject()

    get("/l/{code}") {
        val code = call.parameters["code"]!!.uppercase().filter { it.isLetterOrDigit() }.take(10)
        val league = leagues.findByCode(code)?.takeIf { it.kind == LeagueKind.PRIVATE }
        val owner = league?.createdBy?.let { clubs.findById(it) }
        val t = LandingText.forCall(call)
        if (league == null) { call.respondText(page(call, t, t.notFound, null, "L", code), ContentType.Text.Html, HttpStatusCode.NotFound); return@get }
        call.respondText(page(call, t, t.league.format(esc(owner?.name ?: "A friend"), esc(league.name)), code, "L", code), ContentType.Text.Html)
    }
    get("/c/{code}") {
        val code = call.parameters["code"]!!.uppercase().filter { it.isLetterOrDigit() }.take(10)
        val c = social.challengeByCode(code)
        val challenger = c?.let { clubs.findById(it.challengerClubId) }
        val t = LandingText.forCall(call)
        if (c == null || challenger == null) { call.respondText(page(call, t, t.notFound, null, "C", code), ContentType.Text.Html, HttpStatusCode.NotFound); return@get }
        call.respondText(page(call, t, t.challenge.format(esc(challenger.name)), code, "C", code), ContentType.Text.Html)
    }
    get("/.well-known/assetlinks.json") {
        val cfg = call.application.environment.config
        val pkg = cfg.propertyOrNull("links.androidPackage")?.getString() ?: ""
        val certs = (cfg.propertyOrNull("links.androidCertSha256")?.getString() ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val json = """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"$pkg","sha256_cert_fingerprints":[${certs.joinToString(",") { "\"$it\"" }}]}}]"""
        call.respondText(json, ContentType.Application.Json)
    }
    get("/.well-known/apple-app-site-association") {
        val cfg = call.application.environment.config
        val app = "${cfg.propertyOrNull("links.iosTeamId")?.getString() ?: ""}.${cfg.propertyOrNull("oauth.apple.bundleId")?.getString() ?: ""}"
        call.respondText("""{"applinks":{"details":[{"appIDs":["$app"],"components":[{"/":"/l/*"},{"/":"/c/*"}]}]}}""", ContentType.Application.Json)
    }
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private fun page(call: ApplicationCall, t: LandingText, headline: String, code: String?, kind: String, rawCode: String): String {
    val cfg = call.application.environment.config
    val scheme = cfg.propertyOrNull("links.scheme")?.getString() ?: "matchdayclub"
    val play = cfg.propertyOrNull("links.playStoreUrl")?.getString() ?: ""
    val ios = cfg.propertyOrNull("links.appStoreUrl")?.getString() ?: ""
    val deep = "$scheme://${kind.lowercase()}/$rawCode"
    val copy = "MDC:$kind:$rawCode"
    return """<!doctype html><html lang="${t.lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Matchday Club</title><meta property="og:title" content="Matchday Club"><meta property="og:description" content="${headline.replace("\"", "&quot;")}">
<style>
body{margin:0;background:#0B0F14;color:#F2F4F7;font:16px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}
main{max-width:440px;margin:0 auto;padding:48px 20px;text-align:center}
h1{font-size:28px;margin:0 0 8px;color:#35D07F;letter-spacing:.06em}
p.lead{font-size:20px;margin:16px 0 24px}
.code{font:700 34px/1 ui-monospace,Menlo,monospace;letter-spacing:.18em;background:#151B23;border:1px solid #2A3441;border-radius:16px;padding:18px;margin:0 0 24px}
a.btn{display:block;padding:16px;border-radius:14px;margin:12px 0;font-weight:700;text-decoration:none;background:#35D07F;color:#06130B}
a.alt{background:#1E2630;color:#F2F4F7}
p.hint{color:#8A94A6;font-size:14px;margin-top:20px}
</style></head><body><main>
<h1>MATCHDAY CLUB</h1>
<p class="lead">$headline</p>
${if (code != null) "<div class=\"code\">$code</div>" else ""}
${if (code != null) "<a class=\"btn\" href=\"$deep\">${t.open}</a>" else ""}
${if (play.isNotBlank()) "<a class=\"btn alt\" href=\"$play\" onclick=\"cp()\">${t.play}</a>" else ""}
${if (ios.isNotBlank()) "<a class=\"btn alt\" href=\"$ios\" onclick=\"cp()\">${t.appStore}</a>" else ""}
<p class="hint">${t.hint}</p>
</main><script>function cp(){try{navigator.clipboard.writeText('$copy')}catch(e){}}</script></body></html>"""
}

private data class LandingText(val lang: String, val league: String, val challenge: String, val notFound: String, val open: String, val play: String, val appStore: String, val hint: String) {
    companion object {
        private val all = mapOf(
            "en" to LandingText("en", "%s invites you to the private league «%s».", "%s challenges you to a friendly!", "This invite was not found or has expired.", "Open in Matchday Club", "Get it on Google Play", "Download on the App Store", "New here? Install the game — the code is copied and applied on first launch."),
            "es" to LandingText("es", "%s te invita a la liga privada «%s».", "¡%s te reta a un amistoso!", "No encontramos esta invitación o ha caducado.", "Abrir en Matchday Club", "Disponible en Google Play", "Descargar en el App Store", "¿Nuevo? Instala el juego: el código se copia y se aplica al abrirlo."),
            "pt" to LandingText("pt", "%s convida você para a liga privada «%s».", "%s desafia você para um amistoso!", "Convite não encontrado ou expirado.", "Abrir no Matchday Club", "Disponível no Google Play", "Baixar na App Store", "Novo por aqui? Instale o jogo — o código é copiado e aplicado na primeira abertura."),
            "ru" to LandingText("ru", "%s приглашает вас в приватную лигу «%s».", "%s вызывает вас на товарищеский матч!", "Приглашение не найдено или истекло.", "Открыть в Matchday Club", "Скачать в Google Play", "Загрузить в App Store", "Нет игры? Установите её — код скопирован и применится при первом запуске."),
            "uk" to LandingText("uk", "%s запрошує вас до приватної ліги «%s».", "%s викликає вас на товариський матч!", "Запрошення не знайдено або воно застаріло.", "Відкрити в Matchday Club", "Завантажити з Google Play", "Завантажити в App Store", "Немає гри? Встановіть її — код скопійовано, він застосується під час першого запуску."),
        )
        fun forCall(call: ApplicationCall): LandingText {
            val header = call.request.header(HttpHeaders.AcceptLanguage) ?: ""
            val tag = header.split(",").map { it.substringBefore(";").trim().take(2).lowercase() }.firstOrNull { it in all } ?: "en"
            return all.getValue(tag)
        }
    }
}
