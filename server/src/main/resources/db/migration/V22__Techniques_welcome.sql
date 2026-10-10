-- Two skill points for every outfield athlete that existed before the technique trees, so the first steps can be tried at once.
UPDATE athletes SET skill_points = skill_points + 2 WHERE position IN ('DEF', 'MID', 'FWD') AND retired_at IS NULL;
