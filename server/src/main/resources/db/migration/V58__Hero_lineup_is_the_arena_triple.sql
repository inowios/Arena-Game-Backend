-- The Arena v4 match is 3 v 3 (docs/claude/ARENA-MATCH-V3.md, APP-AUDIT.md §2.8 in the mobile repo): the «Склад» is a
-- triple of heroes, no keeper, no bench. The old field/keeper/bench row becomes the triple's first three heroes.
ALTER TABLE club_hero_lineups RENAME COLUMN field TO heroes;
UPDATE club_hero_lineups SET heroes = array_to_string((string_to_array(heroes, ','))[1:3], ',');
ALTER TABLE club_hero_lineups DROP COLUMN keeper, DROP COLUMN bench;
