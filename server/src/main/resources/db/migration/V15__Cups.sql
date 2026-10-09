-- Weekly knockout cups: per timezone bucket and week, 16 clubs (active humans by strength, filled with bots), drawn on
-- Thursday 12:00, rounds Thursday–Sunday 18:00 bucket time. Fixtures of kind CUP live in fixtures (league_id NULL).
CREATE TABLE cups (
    id UUID PRIMARY KEY,
    tz_bucket INT NOT NULL,
    week_start DATE NOT NULL,
    tier INT NOT NULL,
    division INT NOT NULL,
    current_round INT NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    winner_club_id UUID REFERENCES clubs (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tz_bucket, week_start, tier)
);
CREATE INDEX idx_cups_status ON cups (status);

-- One cup per club and week; out_round = round the club lost in (NULL: still in, or champion of a finished cup).
CREATE TABLE cup_entries (
    week_start DATE NOT NULL,
    club_id UUID NOT NULL REFERENCES clubs (id),
    cup_id UUID NOT NULL REFERENCES cups (id) ON DELETE CASCADE,
    tz_bucket INT NOT NULL,
    seed INT NOT NULL,
    out_round INT,
    prize_coins BIGINT NOT NULL DEFAULT 0,
    prize_gold INT NOT NULL DEFAULT 0,
    prize_reputation INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (week_start, club_id)
);
CREATE INDEX idx_cup_entries_cup ON cup_entries (cup_id);
CREATE INDEX idx_cup_entries_club ON cup_entries (club_id, week_start);

-- Bracket: slot s of round r+1 is played by the winners of slots 2s and 2s+1 of round r.
CREATE TABLE cup_ties (
    cup_id UUID NOT NULL REFERENCES cups (id) ON DELETE CASCADE,
    round INT NOT NULL,
    slot INT NOT NULL,
    home_club_id UUID NOT NULL REFERENCES clubs (id),
    away_club_id UUID NOT NULL REFERENCES clubs (id),
    fixture_id UUID NOT NULL REFERENCES fixtures (id),
    winner_club_id UUID REFERENCES clubs (id),
    PRIMARY KEY (cup_id, round, slot)
);
