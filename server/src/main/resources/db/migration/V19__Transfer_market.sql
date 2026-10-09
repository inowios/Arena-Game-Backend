-- Transfer market: a club sees a dozen listings generated for the six-hour period (and its refresh count);
-- purchases are recorded so a listing is sold once.
ALTER TABLE clubs ADD COLUMN market_salt INT NOT NULL DEFAULT 0;
CREATE TABLE market_purchases (
    club_id UUID NOT NULL REFERENCES clubs(id),
    period BIGINT NOT NULL,
    salt INT NOT NULL,
    idx INT NOT NULL,
    athlete_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (club_id, period, salt, idx)
);
