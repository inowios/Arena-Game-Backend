package com.arenacity.domain.usecase.club

import com.arenacity.common.time.Clock
import com.arenacity.domain.repository.ClubRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Who is online: the club's owner made an authenticated request within [ONLINE_SECONDS] (the alliance's members list). */
object Presence {
    /** [com.arenacity.domain.model.Club.lastSeenAt] is written at most this often per user. */
    const val WRITE_EVERY_SECONDS = 60L
    /** Seen within this long: online. */
    const val ONLINE_SECONDS = 300L

    fun online(lastSeenAt: Instant?, now: Instant): Boolean =
        lastSeenAt != null && !lastSeenAt.isBefore(now.minusSeconds(ONLINE_SECONDS)) && !lastSeenAt.isAfter(now.plusSeconds(WRITE_EVERY_SECONDS))
}

/**
 * Marks the user's club as seen on their authenticated requests (Security's JWT validation): one write a minute at most
 * per user (the in-memory map keeps the rest off the database); never fails the request.
 */
class PresenceTracker(private val clubs: ClubRepository, private val clock: Clock) {
    private val written = ConcurrentHashMap<UUID, Instant>()
    private val log = LoggerFactory.getLogger("PresenceTracker")

    /** True when this call wrote the time. */
    suspend fun seen(userId: UUID): Boolean {
        val now = clock.now()
        val last = written[userId]
        if (last != null && last.isAfter(now.minusSeconds(Presence.WRITE_EVERY_SECONDS))) return false
        return try {
            // no club yet (sign-in, then «create club»): nothing written, so the next request tries again
            if (!clubs.touchSeen(userId, now)) return false
            if (written.size > 500_000) written.clear()
            written[userId] = now
            true
        } catch (e: Exception) {
            log.debug("presence not recorded", e)
            false
        }
    }
}
