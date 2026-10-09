-- Athlete careers: potential (ceiling of the attribute average reachable by training), retirement / release
-- (row kept for old match highlights), daily scouting signing marker.
ALTER TABLE athletes ADD COLUMN potential INT;
UPDATE athletes SET potential = LEAST(95, (attack + defense + physical + technique + mental) / 5
    + CASE WHEN age <= 19 THEN 16 WHEN age <= 21 THEN 12 WHEN age <= 24 THEN 7 WHEN age <= 28 THEN 3 ELSE 1 END
    + (abs(hashtext(id::text)) % 6));
ALTER TABLE athletes ALTER COLUMN potential SET NOT NULL;
ALTER TABLE athletes ALTER COLUMN potential SET DEFAULT 60;
ALTER TABLE athletes ADD COLUMN retired_at TIMESTAMPTZ;
CREATE INDEX idx_athletes_active ON athletes (club_id) WHERE retired_at IS NULL;

ALTER TABLE clubs ADD COLUMN scout_signed_date DATE;
