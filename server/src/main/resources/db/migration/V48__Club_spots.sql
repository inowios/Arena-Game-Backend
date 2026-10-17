-- The five islands map (17 October 2026): the free-form city builder went, the world is a fixed painting with ten
-- buildings ("spots", rules.MapSpots). The stadium is always open and each people's island spot opens with its zone
-- (club_zones); the main island's other five spots open for coins, one row each here. The club's stadium level is the
-- number of open spots.
--
-- The builder's tables and columns (buildings, obstacles, club_layouts, rehome_log, alliance_helps, clubs.land_mask,
-- clubs.builder_slots, …) stay for now: the code no longer reads them; dropping them is a later, separate step.
CREATE TABLE club_spots (
    club_id     UUID        NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    spot        VARCHAR(32) NOT NULL,
    unlocked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (club_id, spot)
);
