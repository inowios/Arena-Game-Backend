-- Techniques: the eight live-match moves as five-step trees per athlete, bought with skill points earned by experience.
ALTER TABLE athletes ADD COLUMN techniques TEXT NOT NULL DEFAULT '';
ALTER TABLE athletes ADD COLUMN skill_points INT NOT NULL DEFAULT 0;
ALTER TABLE athletes ADD COLUMN xp_total INT NOT NULL DEFAULT 0;
