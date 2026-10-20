package com.arenacity.domain.rules

import kotlin.random.Random

/** The club's name is the player's username (the owner, 20 Oct 2026: «теперь будет юзернейм игрока, а не города»):
 * letters of any alphabet, digits, underscores, hyphens and dots, no spaces, 3..16 characters, at least one letter;
 * unique without regard to case (ClubRepository.nameTaken). A new player gets a generated one ([generate]) and changes
 * it later in the profile. The client applies the same rule (PlayerNames). */
object ClubNames {
    const val MIN = 3
    const val MAX = 16

    /** Trimmed, inner whitespace turned into underscores; the case is the player's. */
    fun normalize(raw: String): String = raw.trim().replace(Regex("\\s+"), "_")

    /** Null when fine, else the reason (English; the client shows its own text). */
    fun problem(name: String): String? {
        if (name.length < MIN) return "The name is too short (at least $MIN characters)"
        if (name.length > MAX) return "The name is too long (up to $MAX characters)"
        var letter = false
        for (c in name) {
            if (c.isLetter()) { letter = true; continue }
            if (c.isDigit() || c == '_' || c == '-' || c == '.') continue
            return "Only letters, digits, _ - and ."
        }
        return if (letter) null else "The name needs at least one letter"
    }

    private val WORDS = listOf("Striker", "Keeper", "Winger", "Captain", "Libero", "Playmaker", "Rookie", "Kicker", "Dribbler", "Sweeper")

    /** A default username such as «Striker_4821»: a football word and four digits, [taken] tried until a free one. */
    suspend fun generate(taken: suspend (String) -> Boolean, random: Random = Random.Default): String {
        repeat(50) {
            val name = WORDS[random.nextInt(WORDS.size)] + "_" + random.nextInt(1000, 10000)
            if (!taken(name)) return name
        }
        return "Player_" + random.nextInt(100000, 1000000)
    }
}
