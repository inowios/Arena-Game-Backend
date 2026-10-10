-- Trophy road and club-level road: the steps a club has claimed (each pays once).
CREATE TABLE club_road_claims (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    road VARCHAR(16) NOT NULL,
    step INT NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, road, step)
);
