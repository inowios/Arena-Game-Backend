package com.arenacity.domain.rules

/** The city's (club's) name is a name, not a username (the owner, 20 Oct 2026): letters of any alphabet, digits, spaces,
 * apostrophes and hyphens, 2..24 characters after normalization, at least one letter. Spaces are collapsed and the first
 * letter capitalized. The client applies the same rule (CreateClubController.CityNames). */
object ClubNames {
    const val MIN = 2
    const val MAX = 24

    fun normalize(raw: String): String {
        val t = raw.trim().replace(Regex("\\s+"), " ").replace('\u2019', '\'')
        if (t.isEmpty()) return t
        return if (t[0].isLetter()) t[0].uppercaseChar() + t.substring(1) else t
    }

    /** Null when fine, else the reason (English; the client shows its own text). */
    fun problem(name: String): String? {
        if (name.length < MIN) return "The name is too short (at least $MIN letters)"
        if (name.length > MAX) return "The name is too long (up to $MAX characters)"
        var letter = false
        for (c in name) {
            if (c.isLetter()) { letter = true; continue }
            if (c.isDigit() || c == ' ' || c == '\'' || c == '-') continue
            return "Only letters, digits, spaces, apostrophes and hyphens"
        }
        return if (letter) null else "The name needs at least one letter"
    }
}
