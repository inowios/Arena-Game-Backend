-- Multi-sport matchdays: a fixture is played across all sections; points = sum of section points (3/1/0).
ALTER TABLE fixtures ADD COLUMN home_points INT;
ALTER TABLE fixtures ADD COLUMN away_points INT;
UPDATE fixtures SET home_points = CASE WHEN home_score > away_score THEN 3 WHEN home_score = away_score THEN 1 ELSE 0 END,
                    away_points = CASE WHEN away_score > home_score THEN 3 WHEN home_score = away_score THEN 1 ELSE 0 END
 WHERE status = 'PLAYED' AND home_score IS NOT NULL;
CREATE INDEX idx_athletes_club_sport ON athletes (club_id, sport);
-- Season lifecycle: a public league is FINISHED once its season is closed (members move on to next season's leagues).
ALTER TABLE leagues ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE';
CREATE INDEX idx_leagues_status ON leagues (kind, status);
