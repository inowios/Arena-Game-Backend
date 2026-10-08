ALTER TABLE clubs ADD COLUMN is_bot BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE clubs ADD COLUMN division INT NOT NULL DEFAULT 1;
ALTER TABLE clubs ADD COLUMN tz_bucket INT NOT NULL DEFAULT 0;
ALTER TABLE clubs ADD COLUMN friendlies_date DATE;
ALTER TABLE clubs ADD COLUMN friendlies_played INT NOT NULL DEFAULT 0;

CREATE TABLE leagues (
    id UUID PRIMARY KEY,
    kind VARCHAR(8) NOT NULL,
    name VARCHAR(40) NOT NULL,
    code VARCHAR(6) UNIQUE,
    division INT NOT NULL DEFAULT 1,
    tz_bucket INT NOT NULL DEFAULT 0,
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    matchday_hour INT NOT NULL DEFAULT 20,
    season_number INT NOT NULL DEFAULT 1,
    season_start DATE NOT NULL,
    current_round INT NOT NULL DEFAULT 0,
    created_by UUID REFERENCES clubs (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_leagues_public ON leagues (kind, division, tz_bucket);

CREATE TABLE league_members (
    league_id UUID NOT NULL REFERENCES leagues (id) ON DELETE CASCADE,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (league_id, club_id)
);
CREATE INDEX idx_league_members_club ON league_members (club_id);

CREATE TABLE fixtures (
    id UUID PRIMARY KEY,
    league_id UUID REFERENCES leagues (id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL,
    season_number INT NOT NULL DEFAULT 1,
    round INT NOT NULL DEFAULT 0,
    scheduled_at TIMESTAMPTZ NOT NULL,
    home_club_id UUID NOT NULL REFERENCES clubs (id),
    away_club_id UUID NOT NULL REFERENCES clubs (id),
    status VARCHAR(10) NOT NULL DEFAULT 'SCHEDULED',
    home_score INT,
    away_score INT,
    seed BIGINT NOT NULL,
    result_json TEXT,
    played_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fixtures_league_round ON fixtures (league_id, season_number, round);
CREATE INDEX idx_fixtures_home ON fixtures (home_club_id);
CREATE INDEX idx_fixtures_away ON fixtures (away_club_id);
