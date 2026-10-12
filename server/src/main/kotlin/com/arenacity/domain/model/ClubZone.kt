package com.arenacity.domain.model

import java.time.Instant
import java.util.UUID

/** The five stadium zones round the club's plot, one per sport; padel, volleyball and hockey have no match engine yet. */
enum class ZoneSport { BASKETBALL, TENNIS, PADEL, VOLLEYBALL, HOCKEY }

enum class ZoneState { LOCKED, RESTORING, OPEN }

/** A zone the club has bought: the derelict arena is restored until [restoreEndsAt], then the zone is open. */
data class ClubZone(val clubId: UUID, val sport: ZoneSport, val state: ZoneState, val boughtAt: Instant, val restoreEndsAt: Instant?) {
    fun stateAt(now: Instant): ZoneState = if (state == ZoneState.RESTORING && restoreEndsAt != null && !now.isBefore(restoreEndsAt)) ZoneState.OPEN else state
}
