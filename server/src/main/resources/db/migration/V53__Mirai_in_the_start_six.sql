-- The start six change (the owner, 18 October 2026): Mirai Shion (p004) instead of Shizuku Isonami (p010), who now comes
-- with chapter 2 of the story (rules.Heroes.START, rules.Story). A club that already got the old six keeps Shizuku and
-- gets Mirai too, as a start hero.
INSERT INTO club_heroes (club_id, hero_id, level, xp, xp_today, source, unlocked_at)
SELECT DISTINCT club_id, 'p004', 1, 0, 0, 'START', NOW() FROM club_heroes WHERE hero_id = 'p010' AND source = 'START'
ON CONFLICT (club_id, hero_id) DO NOTHING;
