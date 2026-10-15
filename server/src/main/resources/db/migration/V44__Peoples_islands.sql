-- The peoples' islands (the owner, 15 October 2026: «приберём стадионы на тех островах, и там будет проживать определённый
-- народ: люди по центру, а звери, андроиды, тени и каменные — на соседних островах»; the game is football only). The side
-- islands hold no arenas any more: each is a people's home, under its dome until the club's bridge to it stands
-- (rules.Zones, rules.Peoples).
--
-- 1. The island arenas go, refunded in full: every BASKETBALL_ARENA, VOLLEYBALL_ARENA, TENNIS_ARENA and PADEL_ARENA is
--    deleted and the club gets back what its levels cost (every level up to the one it has, an upgrade under way
--    included: its price was paid when it started; the cost tables below as rules.BuildingCatalog had them on 14 October),
--    so no player loses what they put in. The gold spent speeding them up is not given back (it bought time, as V36).
--    Each is logged in rehome_log (mask_id 'peoples', kind REMOVED, its type, place, level and the coins refunded) so a
--    revert could put them back; an alliance help asked for one is closed.
-- 2. The zones are the bridges now and keep their rows, renamed to the island's people: BASKETBALL (the south island)
--    -> BEAST, VOLLEYBALL (east) -> STONE, TENNIS (north) -> ANDROID, PADEL (west) -> SHADOW; a bridge built or being built
--    stays as it is (a club that opened an island at the sport town's lower stadium keeps it). The column keeps its name.
-- 3. Hockey's zone (no island ever) goes; a club that had bought it gets its price (80 000) back, logged the same way
--    (type HOCKEY_ZONE, item_id = the club).
-- The land mask's regions were renamed with it (basketball -> beast, volleyball -> stone, tennis -> android, padel ->
-- shadow: a new mask id), so every club is settled once more at the next start (ClubService.settle; nothing moves, the
-- shapes are the same).

CREATE TEMP TABLE v44_cost (type VARCHAR(32) NOT NULL, level INT NOT NULL, cost BIGINT NOT NULL);
INSERT INTO v44_cost (type, level, cost) VALUES
    ('BASKETBALL_ARENA', 1, 7500), ('BASKETBALL_ARENA', 2, 14000), ('BASKETBALL_ARENA', 3, 25000), ('BASKETBALL_ARENA', 4, 47000),
    ('BASKETBALL_ARENA', 5, 86000), ('BASKETBALL_ARENA', 6, 160000), ('BASKETBALL_ARENA', 7, 290000), ('BASKETBALL_ARENA', 8, 540000),
    ('BASKETBALL_ARENA', 9, 760000), ('BASKETBALL_ARENA', 10, 1000000),
    ('VOLLEYBALL_ARENA', 1, 20000), ('VOLLEYBALL_ARENA', 2, 32000), ('VOLLEYBALL_ARENA', 3, 50000), ('VOLLEYBALL_ARENA', 4, 78000),
    ('VOLLEYBALL_ARENA', 5, 120000), ('VOLLEYBALL_ARENA', 6, 180000), ('VOLLEYBALL_ARENA', 7, 270000), ('VOLLEYBALL_ARENA', 8, 410000),
    ('VOLLEYBALL_ARENA', 9, 620000), ('VOLLEYBALL_ARENA', 10, 950000),
    ('TENNIS_ARENA', 1, 12000), ('TENNIS_ARENA', 2, 21000), ('TENNIS_ARENA', 3, 37000), ('TENNIS_ARENA', 4, 64000),
    ('TENNIS_ARENA', 5, 110000), ('TENNIS_ARENA', 6, 200000), ('TENNIS_ARENA', 7, 340000), ('TENNIS_ARENA', 8, 600000),
    ('TENNIS_ARENA', 9, 820000), ('TENNIS_ARENA', 10, 1100000),
    ('PADEL_ARENA', 1, 150000), ('PADEL_ARENA', 2, 210000), ('PADEL_ARENA', 3, 280000), ('PADEL_ARENA', 4, 370000),
    ('PADEL_ARENA', 5, 480000), ('PADEL_ARENA', 6, 610000), ('PADEL_ARENA', 7, 760000), ('PADEL_ARENA', 8, 930000),
    ('PADEL_ARENA', 9, 1120000), ('PADEL_ARENA', 10, 1350000);

CREATE TEMP TABLE v44_arena AS
SELECT b.id, b.club_id, b.type, b.level, b.grid_x, b.grid_y, b.rotation,
       (SELECT COALESCE(SUM(c.cost), 0) FROM v44_cost c WHERE c.type = b.type AND c.level <= b.level) AS coins
FROM buildings b
WHERE b.type IN ('BASKETBALL_ARENA', 'VOLLEYBALL_ARENA', 'TENNIS_ARENA', 'PADEL_ARENA');

INSERT INTO rehome_log (club_id, item_id, kind, type, old_x, old_y, old_rotation, new_x, new_y, new_rotation, gold, mask_id, at, level, coins)
SELECT a.club_id, a.id, 'REMOVED', a.type, a.grid_x, a.grid_y, a.rotation, NULL, NULL, a.rotation, 0, 'peoples', now(), a.level, a.coins
FROM v44_arena a;

-- hockey's zone: its price back to a club that bought it
INSERT INTO rehome_log (club_id, item_id, kind, type, old_x, old_y, old_rotation, new_x, new_y, new_rotation, gold, mask_id, at, level, coins)
SELECT z.club_id, z.club_id, 'REMOVED', 'HOCKEY_ZONE', 0, 0, 0, NULL, NULL, 0, 0, 'peoples', now(), 0, 80000
FROM club_zones z
WHERE z.sport = 'HOCKEY';

UPDATE clubs SET coins = clubs.coins + r.coins, updated_at = now()
FROM (SELECT club_id, SUM(coins) AS coins FROM rehome_log WHERE mask_id = 'peoples' AND kind = 'REMOVED' GROUP BY club_id) r
WHERE clubs.id = r.club_id AND r.coins > 0;

UPDATE alliance_helps SET closed = TRUE WHERE building_id IN (SELECT id FROM v44_arena);
DELETE FROM buildings WHERE id IN (SELECT id FROM v44_arena);

DELETE FROM club_zones WHERE sport = 'HOCKEY';
UPDATE club_zones SET sport = CASE sport
    WHEN 'BASKETBALL' THEN 'BEAST' WHEN 'VOLLEYBALL' THEN 'STONE' WHEN 'TENNIS' THEN 'ANDROID' WHEN 'PADEL' THEN 'SHADOW' END
WHERE sport IN ('BASKETBALL', 'VOLLEYBALL', 'TENNIS', 'PADEL');

DROP TABLE v44_arena;
DROP TABLE v44_cost;
