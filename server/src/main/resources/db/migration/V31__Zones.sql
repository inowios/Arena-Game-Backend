-- The stadium zones of the club's rectangle (CITY-BUILD-PLAN stage A): one row per zone the club has bought; a zone
-- is restoring until restore_ends_at, then open. Zones never bought have no row.
CREATE TABLE club_zones (
    club_id UUID NOT NULL REFERENCES clubs(id),
    sport VARCHAR(16) NOT NULL,
    state VARCHAR(12) NOT NULL,
    bought_at TIMESTAMPTZ NOT NULL,
    restore_ends_at TIMESTAMPTZ NULL,
    PRIMARY KEY (club_id, sport)
);
