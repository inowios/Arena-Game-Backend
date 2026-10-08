CREATE TABLE athletes (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    sport VARCHAR(16) NOT NULL,
    position VARCHAR(8) NOT NULL,
    attack INT NOT NULL, defense INT NOT NULL, physical INT NOT NULL, technique INT NOT NULL, mental INT NOT NULL,
    age INT NOT NULL,
    rarity VARCHAR(8) NOT NULL,
    fatigue INT NOT NULL DEFAULT 0,
    fatigue_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    injured_until TIMESTAMPTZ,
    morale INT NOT NULL DEFAULT 50,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_athletes_club ON athletes (club_id);

CREATE TABLE trainings (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    athlete_id UUID NOT NULL UNIQUE REFERENCES athletes (id) ON DELETE CASCADE,
    attribute VARCHAR(12) NOT NULL,
    delta INT NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trainings_club ON trainings (club_id);

CREATE TABLE lineups (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    sport VARCHAR(16) NOT NULL,
    starters_json TEXT NOT NULL,
    bench_json TEXT NOT NULL,
    tactics_json TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, sport)
);
