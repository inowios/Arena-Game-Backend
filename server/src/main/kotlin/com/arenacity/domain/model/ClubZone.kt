package com.arenacity.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The peoples of the archipelago that live on the side islands (the owner, 15 October 2026: «приберём стадионы на тех
 * островах, и там будет проживать определённый народ: люди по центру, а звери, андроиды, тени и каменные — на соседних
 * островах»; the lore: docs/art/ANIME-STORY.md in the mobile repo — after the Eclipse the peoples left for their islands
 * and the arenas were sealed under domes). Humans live on the main island and are not here. Each people's island opens
 * with its bridge (rules.Zones), at the stadium of its era (docs/claude/CARD-GAME-ROSTER-PROGRESSION.md §5: the beastfolk
 * and the stone people era 2, the androids era 3, the shadows era 4).
 *
 * [legacy]: the sport whose arena the island held before (the sport town, 14 October 2026): the island south of the main
 * one was basketball's, the east volleyball's, the north tennis's, the west padel's. The database rows were renamed
 * (V44__Peoples_islands.sql); [parse] still reads the old names (an old client, an old land mask).
 */
enum class People(val legacy: String) {
    BEAST("BASKETBALL"), STONE("VOLLEYBALL"), ANDROID("TENNIS"), SHADOW("PADEL");

    /** The id of the people's island and region (the land mask, world.json): "beast", "stone", "android", "shadow". */
    val island: String get() = name.lowercase()

    companion object {
        /** A people by its name or its island's old sport (case ignored); null for anything else (hockey, football). */
        fun parse(s: String?): People? {
            val u = s?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == u || it.legacy == u }
        }
    }
}

/** LOCKED: no bridge yet; RESTORING: the bridge is being built (until [ClubZone.restoreEndsAt]); OPEN: the bridge stands. */
enum class ZoneState { LOCKED, RESTORING, OPEN }

/**
 * A people's island the club has started a bridge to: the bridge is built until [restoreEndsAt], then the island is open
 * — its dome goes and its people has returned (the people's cards drop from packs: rules.Peoples).
 */
data class ClubZone(val clubId: UUID, val people: People, val state: ZoneState, val boughtAt: Instant, val restoreEndsAt: Instant?) {
    fun stateAt(now: Instant): ZoneState = if (state == ZoneState.RESTORING && restoreEndsAt != null && !now.isBefore(restoreEndsAt)) ZoneState.OPEN else state
}
