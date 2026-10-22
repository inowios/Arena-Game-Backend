package com.arenacity.presentation.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Store-required public pages: privacy policy (/privacy), terms of service (/terms) and how to delete an account
 * (/delete-account, Google Play "data deletion" URL). English only (the owner, 22 Oct 2026). The contact address comes
 * from SUPPORT_EMAIL, the publisher from LEGAL_COMPANY.
 */
fun Route.legalRoutes() {
    get("/privacy") { respondLegal(call, LegalDoc.PRIVACY) }
    get("/terms") { respondLegal(call, LegalDoc.TERMS) }
    get("/delete-account") { respondLegal(call, LegalDoc.DELETION) }
}

private enum class LegalDoc { PRIVACY, TERMS, DELETION }

private const val UPDATED = "2026-10-09"

private suspend fun respondLegal(call: ApplicationCall, doc: LegalDoc) {
    val cfg = call.application.environment.config
    val email = cfg.propertyOrNull("links.supportEmail")?.getString()?.trim().orEmpty()
    val company = cfg.propertyOrNull("legal.company")?.getString()?.trim()?.ifEmpty { null } ?: "Inowios"
    val contact = if (email.isNotEmpty()) "<a href=\"mailto:${esc(email)}\">${esc(email)}</a>" else "through Settings in the game"
    val (title, body) = when (doc) {
        LegalDoc.PRIVACY -> privacyEn(esc(company), contact)
        LegalDoc.TERMS -> termsEn(esc(company), contact)
        LegalDoc.DELETION -> deletionEn(contact)
    }
    val nav = listOf("/privacy" to "Privacy", "/terms" to "Terms", "/delete-account" to "Delete account")
        .joinToString(" · ") { (href, label) -> "<a href=\"$href\">$label</a>" }
    val html = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Arena — $title</title>
<style>
body{margin:0;background:#0B0F14;color:#E6E9EE;font:16px/1.6 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}
main{max-width:760px;margin:0 auto;padding:40px 20px 64px}
h1{font-size:28px;margin:0 0 4px;color:#F2F4F7} h2{font-size:19px;margin:28px 0 8px;color:#35D07F}
a{color:#35D07F} p.meta{color:#8A94A6;font-size:14px;margin:0 0 24px} ul{padding-left:22px} li{margin:4px 0}
nav{font-size:14px;color:#8A94A6;margin-bottom:28px}
</style></head><body><main>
<nav>ARENA · $nav</nav>
<h1>$title</h1>
<p class="meta">Last updated: $UPDATED</p>
$body
</main></body></html>"""
    call.respondText(html, ContentType.Text.Html)
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun privacyEn(company: String, contact: String) = "Privacy Policy" to """
<p>Arena (“the game”) is published by $company (“we”). This policy explains what we collect when you play, why, and the choices you have. Questions: $contact.</p>
<h2>What we collect</h2>
<ul>
<li><b>Account.</b> A random identifier created on your device for guest play. If you sign in with Google or Apple, the account identifier and the e-mail address they share with us.</li>
<li><b>Game data.</b> Your club (name, emblem, colours, heroes, lineups, matches, leagues, trophies), the messages you write in private-league chats, challenges and invites you send, and your purchase history.</li>
<li><b>Technical data.</b> App version, platform, language, time zone, the days on which you play and, if you allow notifications, a push token. Our servers log IP addresses and requests for security; these logs are kept for 14 days.</li>
<li><b>Usage events.</b> Which features you use (for example tutorial steps, opening the store, watching a rewarded video) and technical errors in the app. They are linked to your pseudonymous player id, used to improve the game and deleted after 13 months, like the record of days you played.</li>
<li><b>Purchases.</b> Payments are handled by Google Play or the App Store. We receive the order id and product to deliver your items; we never see your payment details.</li>
<li><b>Ads.</b> Optional rewarded videos are served by Google AdMob, which may use your device’s advertising identifier as described in <a href="https://policies.google.com/technologies/ads">Google’s policy</a>. On iOS the identifier is only available if you allow tracking. You can reset or limit it in your device settings.</li>
</ul>
<h2>Why we use it</h2>
<ul>
<li>To run the game: save your club, play matchdays, leagues, cups and friendlies, keep progress across devices (contract).</li>
<li>To send notifications you can turn off at any time in the game or system settings (consent).</li>
<li>To prevent cheating and fraud, verify purchases and ad rewards, and keep the service secure (legitimate interest).</li>
<li>To understand how the game is played, in aggregate, and to answer support requests (legitimate interest).</li>
</ul>
<h2>Who sees it</h2>
<p>Other players see your club’s name, emblem, results and the messages you post. We share data only with providers that run the service for us: our hosting provider, Google (Sign-In, Play billing, Firebase Cloud Messaging, AdMob) and Apple (Sign in with Apple, App Store). We do not sell personal data.</p>
<h2>How long we keep it</h2>
<p>As long as your account exists. When you delete your account, your sign-in data, push tokens, usage events and the record of days you played are erased immediately; your club stays in past league tables as an anonymous computer-controlled club. Purchase records are kept as long as tax law requires.</p>
<h2>Your rights</h2>
<p>You can access, correct or delete your data and object to its use. Delete your account in the game: <b>Profile → Settings → Delete account</b> (see <a href="/delete-account">how</a>). For anything else, contact us. If you are in the EU/UK you may also complain to your data protection authority.</p>
<h2>Children</h2>
<p>The game is not directed at children under 13 (or the higher minimum age in your country). We do not knowingly collect data from them; if you believe a child has given us data, contact us and we will delete it.</p>
<h2>Security and changes</h2>
<p>Connections are encrypted (TLS) and access to data is restricted. If this policy changes we will update this page and the date above.</p>
"""

private fun termsEn(company: String, contact: String) = "Terms of Service" to """
<p>These terms are an agreement between you and $company about playing Arena. By playing you accept them. Contact: $contact.</p>
<h2>The game</h2>
<p>We grant you a personal, non-transferable licence to play the game for non-commercial purposes. We keep improving the game, so features, balance and content may change.</p>
<h2>Virtual items</h2>
<p>Gold, coins, cosmetics and the premium season pass are a licence to use content in the game; they have no monetary value and cannot be exchanged for money. Purchases are final except where the store’s refund policy or the law says otherwise. Gold never buys match results: matches depend on your squad, training and tactics.</p>
<h2>Fair play</h2>
<ul><li>Don’t cheat, use bots, exploit bugs, or interfere with the service or other players.</li>
<li>In chats, be respectful: no harassment, hate, spam or personal data of others. Messages can be reported, removed, and players can be blocked.</li>
<li>Don’t impersonate others or use offensive club names.</li></ul>
<p>We may suspend or close accounts that break these rules.</p>
<h2>Your account</h2>
<p>Keep your device or linked sign-in safe; use a transfer code to move your club to a new device. You can delete your account at any time in Settings.</p>
<h2>Liability</h2>
<p>The game is provided “as is”. To the extent the law allows, we are not liable for indirect losses or for interruptions of the service. Nothing in these terms limits rights you have under consumer law.</p>
<h2>Changes</h2>
<p>We may update these terms; the new version applies from the date above. If you don’t agree, stop playing and delete your account.</p>
"""

private fun deletionEn(contact: String) = "Delete your account" to """
<p>You can delete your Arena account and its personal data at any time.</p>
<h2>In the game</h2>
<ol><li>Tap your club emblem at the top of the screen to open your profile.</li><li>Tap <b>Settings</b>, then <b>Delete account</b>, and confirm with <b>Delete forever</b>.</li></ol>
<p>Deletion is immediate and cannot be undone.</p>
<h2>Without the app</h2>
<p>If you can no longer open the game, contact us $contact with your club name. To prove the club is yours, include a transfer code from <b>Settings → Move to another device</b> if you still have it, or the e-mail of the Google/Apple account you linked.</p>
<h2>What is deleted</h2>
<ul><li>Sign-in data (device id, Google/Apple account id and e-mail), push tokens, usage events and the record of days played — erased right away.</li>
<li>Your chat messages are removed; your club leaves private leagues and stays in past public-league tables as an anonymous computer-controlled club with a new name.</li>
<li>Purchase records are kept as long as tax law requires (order id, product, date).</li></ul>
"""
