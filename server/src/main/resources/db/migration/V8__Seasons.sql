-- Season lifecycle: divisions with promotion/relegation, prizes, history and trophies.
ALTER TABLE clubs ADD COLUMN season_no INT NOT NULL DEFAULT 1;

CREATE TABLE club_seasons (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    league_id UUID NOT NULL REFERENCES leagues (id) ON DELETE CASCADE,
    season_no INT NOT NULL,
    division INT NOT NULL,
    position INT NOT NULL,
    played INT NOT NULL, won INT NOT NULL, drawn INT NOT NULL, lost INT NOT NULL,
    gf INT NOT NULL, ga INT NOT NULL, points INT NOT NULL,
    movement VARCHAR(10) NOT NULL,
    new_division INT NOT NULL,
    prize_coins BIGINT NOT NULL DEFAULT 0,
    prize_gold INT NOT NULL DEFAULT 0,
    prize_reputation INT NOT NULL DEFAULT 0,
    finished_at TIMESTAMPTZ NOT NULL,
    UNIQUE (club_id, league_id)
);
CREATE INDEX idx_club_seasons_club ON club_seasons (club_id, finished_at DESC);

CREATE TABLE trophies (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL,
    division INT NOT NULL,
    season_no INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trophies_club ON trophies (club_id);
