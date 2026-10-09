package com.matchdayclub.common

import com.matchdayclub.common.i18n.Named
import com.matchdayclub.common.i18n.ServerI18n
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.ValidationException
import java.io.File
import java.time.Duration
import kotlin.test.*

class ServerI18nTest {
    @Test
    fun `accept-language picks the best supported language`() {
        assertEquals("ru", ServerI18n.fromHeader("ru-RU,ru;q=0.9,en;q=0.8"))
        assertEquals("pt", ServerI18n.fromHeader("de-DE,pt-BR;q=0.8,en;q=0.5"))
        assertEquals("uk", ServerI18n.fromHeader("uk"))
        assertNull(ServerI18n.fromHeader("de-DE,fr;q=0.9"))
        assertNull(ServerI18n.fromHeader(null))
        assertEquals("es", ServerI18n.normalize("es_MX")); assertEquals("en", ServerI18n.normalize("zz"))
    }

    @Test
    fun `messages are translated with their arguments`() {
        assertEquals("Нужен уровень клуба 3", ServerI18n.t("Requires club level {0}", "ru", 3))
        assertEquals("Next academy graduate in 2h 5m", ServerI18n.t("Next academy graduate in {0}", "en", Duration.ofMinutes(125)))
        assertEquals("Медцентр занят ещё 45 мин", ServerI18n.t("The medical centre is busy for {0}", "ru", Duration.ofMinutes(45)))
        assertEquals("Сначала постройте площадку: Баскетбол", ServerI18n.t("Build the venue for {0} first", "ru", Named("sport.BASKETBALL")))
        assertEquals("Unlisted text {0}", ServerI18n.t("Unlisted text {0}", "es"))
        assertEquals("Fin de temporada: terminaste en el puesto 3", ServerI18n.t("push.season_title", "es", 3))
        assertEquals("12 500", ServerI18n.format(12_500L, "ru")); assertEquals("12,500", ServerI18n.format(12_500L, "en"))
    }

    @Test
    fun `exceptions keep English for logs and answer in the caller's language`() {
        val e = ConflictException("You already have {0} open challenges", 5)
        assertEquals("You already have 5 open challenges", e.message)
        assertEquals("У вас уже открыто вызовов: 5", e.localized("ru"))
        assertEquals("No tienes suficiente oro", com.matchdayclub.domain.exception.InsufficientFundsException("Not enough gold").localized("es"))
        assertEquals("Bad id", ValidationException("Bad id").localized("uk"))   // technical texts stay English
    }

    @Test
    fun `catalog rows are complete and keep their placeholders`() {
        assertTrue(ServerI18n.size > 100)
        val rows = File("src/main/resources/i18n/messages.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val ph = Regex("\\{\\d+}")
        for (line in rows) {
            val cols = line.split('\t')
            assertEquals(6, cols.size, "columns: $line")
            val ref = ph.findAll(cols[1]).map { it.value }.toSet()
            cols.drop(1).forEachIndexed { i, v ->
                assertTrue(v.isNotBlank(), "empty ${ServerI18n.LANGS[i]}: ${cols[0]}")
                assertEquals(ref, ph.findAll(v).map { it.value }.toSet(), "placeholders ${ServerI18n.LANGS[i]}: ${cols[0]}")
            }
        }
    }

    /** Every player-facing error thrown in the code has translations (technical ones are listed here on purpose). */
    @Test
    fun `every player-facing error message is in the catalog`() {
        val technical = setOf(
            "Bad id", "Unknown timezone", "Rotation must be 0/90/180/270", "Colors must be #RRGGBB", "Negative charge", "buildingId required",
            "Bad athlete id", "Bad athleteId", "Bad building id", "Bad buildingId", "Bad opponentClubId", "Unknown building type", "Invalid credentials",
            "deviceId must be 8..128 chars", "Receipt is for another app", "Receipt is for another product", "Malformed signed data",
            "Unexpected signature algorithm", "Signed data without certificate chain", "Empty certificate chain", "Untrusted certificate chain",
            "Bad signature", "Missing signature", "Missing key_id", "Bad signature encoding", "Unknown key_id",
            "App Store authentication failed", "App Store reply without transaction", "Invalid Apple token", "Unknown Apple key",
            "Invalid Google token", "Google token expired", "Google token audience mismatch", "Invalid refresh token", "Refresh token already used",
            "Refresh token expired or revoked", "User not found", "Bad slot",
        )
        val thrown = Regex("""(?:Validation|Conflict|NotFound|Authorization|Authentication|InsufficientFunds|Upstream)Exception\("([^"$]+)"""")
        val missing = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> thrown.findAll(f.readText()).map { it.groupValues[1] } }
            .filter { it !in technical && !ServerI18n.has(it) }.toSortedSet()
        assertTrue(missing.isEmpty(), "add to messages.tsv: $missing")
    }
}
