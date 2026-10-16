-- The buildings grew and two merged (16 October 2026).
--
-- 1. The owner's building system (docs/claude/BUILDINGS-SYSTEM.md in the mobile repo, §7): the recovery centre is part of
--    the medical centre now and the coaching HQ part of the tactics room (ANALYTICS_CENTER). Every RECOVERY_CENTER and
--    COACHING_HQ is deleted and refunded in full, as V44 did for the island arenas: the club gets back what its levels cost
--    (every level up to the one it has; the cost tables as rules.BuildingCatalog had them, the curves of the buildings
--    with no mechanic yet opening at stadium 5 and 8), logged in rehome_log (mask_id 'merge', kind REMOVED); an alliance
--    help asked for one is closed. The gold spent speeding them up is not given back (it bought time).
-- 2. The owner: next to the 16x16 stadium the buildings are far too small — more cells, wider, taller. Every other
--    building is a tile bigger each way (BuildingCatalog.specs); the land mask is the same, so the clubs are marked for
--    the start-up sweep (ClubService.settle): the stadium keeps its place first, then the biggest, the oldest first; one
--    that now overlaps goes to the nearest free tile (or waits parked, listed in edit mode); a road or fence tile under
--    one goes and is refunded. The showcase towns (tz_bucket -1) are rebuilt from their layout at start-up.

CREATE TEMP TABLE v46_cost (type VARCHAR(32) NOT NULL, level INT NOT NULL, cost BIGINT NOT NULL);
INSERT INTO v46_cost (type, level, cost) VALUES
    ('RECOVERY_CENTER', 1, 15000), ('RECOVERY_CENTER', 2, 23000), ('RECOVERY_CENTER', 3, 35000), ('RECOVERY_CENTER', 4, 53000),
    ('RECOVERY_CENTER', 5, 82000), ('RECOVERY_CENTER', 6, 120000), ('RECOVERY_CENTER', 7, 190000), ('RECOVERY_CENTER', 8, 290000),
    ('RECOVERY_CENTER', 9, 450000), ('RECOVERY_CENTER', 10, 680000),
    ('COACHING_HQ', 1, 60000), ('COACHING_HQ', 2, 81000), ('COACHING_HQ', 3, 110000), ('COACHING_HQ', 4, 150000),
    ('COACHING_HQ', 5, 200000), ('COACHING_HQ', 6, 270000), ('COACHING_HQ', 7, 370000), ('COACHING_HQ', 8, 500000),
    ('COACHING_HQ', 9, 680000), ('COACHING_HQ', 10, 920000);

CREATE TEMP TABLE v46_gone AS
SELECT b.id, b.club_id, b.type, b.level, b.grid_x, b.grid_y, b.rotation,
       (SELECT COALESCE(SUM(c.cost), 0) FROM v46_cost c WHERE c.type = b.type AND c.level <= b.level) AS coins
FROM buildings b
WHERE b.type IN ('RECOVERY_CENTER', 'COACHING_HQ');

INSERT INTO rehome_log (club_id, item_id, kind, type, old_x, old_y, old_rotation, new_x, new_y, new_rotation, gold, mask_id, at, level, coins)
SELECT g.club_id, g.id, 'REMOVED', g.type, g.grid_x, g.grid_y, g.rotation, NULL, NULL, g.rotation, 0, 'merge', now(), g.level, g.coins
FROM v46_gone g;

UPDATE clubs SET coins = clubs.coins + r.coins, updated_at = now()
FROM (SELECT club_id, SUM(coins) AS coins FROM v46_gone GROUP BY club_id) r
WHERE clubs.id = r.club_id AND r.coins > 0;

UPDATE alliance_helps SET closed = TRUE WHERE building_id IN (SELECT id FROM v46_gone);
DELETE FROM buildings WHERE id IN (SELECT id FROM v46_gone);

UPDATE clubs SET land_mask = NULL WHERE tz_bucket IS DISTINCT FROM -1;
