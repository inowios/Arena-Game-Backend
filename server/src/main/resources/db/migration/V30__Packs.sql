-- Card packs (plan §3b): every opening is recorded, so the pity guarantees and the free daily pack are counted from history.
CREATE TABLE pack_opens (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs(id),
    kind VARCHAR(12) NOT NULL,
    price_coins BIGINT NOT NULL DEFAULT 0,
    price_gold INT NOT NULL DEFAULT 0,
    tiers VARCHAR(64) NOT NULL,
    athlete_ids TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX pack_opens_club_idx ON pack_opens(club_id, created_at DESC);
