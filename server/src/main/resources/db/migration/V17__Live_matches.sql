-- Live matches: played on the device against another club's saved line-up (or a bot); the server stores the match
-- it handed out, checks the reported result for plausibility and applies fatigue, trophies and rewards.
ALTER TABLE clubs ADD COLUMN trophies INT NOT NULL DEFAULT 0;
ALTER TABLE clubs ADD COLUMN live_streak INT NOT NULL DEFAULT 0;

CREATE TABLE live_matches (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs(id),
    opponent_id UUID NOT NULL REFERENCES clubs(id),
    seed BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    starters_json TEXT NOT NULL,
    opp_starters_json TEXT NOT NULL,
    tempo DOUBLE PRECISION NOT NULL,
    my_overall INT NOT NULL,
    opp_overall INT NOT NULL,
    home_score INT,
    away_score INT,
    result_json TEXT,
    trophies_delta INT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);
CREATE INDEX live_matches_club_idx ON live_matches (club_id, status);
