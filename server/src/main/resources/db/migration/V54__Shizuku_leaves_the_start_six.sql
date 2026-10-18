-- With V53 a club that had the old start six got Mirai as well. The game has no players yet, so the six are made exactly
-- the new ones: a start Shizuku (p010) that never played or trained leaves the club (she comes with story chapter 2 now),
-- and a saved lineup takes Mirai (p004) in her place.
UPDATE club_hero_lineups SET field = array_to_string(array_replace(string_to_array(field, ','), 'p010', 'p004'), ','),
                             bench = array_to_string(array_replace(string_to_array(bench, ','), 'p010', 'p004'), ',')
WHERE club_id IN (SELECT club_id FROM club_heroes WHERE hero_id = 'p010' AND source = 'START' AND level = 1 AND xp = 0 AND training_ends_at IS NULL)
  AND (field LIKE '%p010%' OR bench LIKE '%p010%');
DELETE FROM club_heroes WHERE hero_id = 'p010' AND source = 'START' AND level = 1 AND xp = 0 AND training_ends_at IS NULL;
