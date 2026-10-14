-- The sport town (the owner, 14 October 2026; art/sprites/town_l1/buildings.json in the mobile repo): the old buildings
-- become the new set (rules.BuildingType). Kept as they are: PITCH, ACADEMY, TRAINING_CENTER, MEDICAL_CENTER,
-- TICKET_OFFICE, HOUSE, DECOR, FENCE, ROAD. Per club:
--   CLUB_SHOP, CAFE, PARKING -> SHOPPING_CENTER: the two of the highest level stay (at their level), the rest go;
--   MUSEUM -> HALL_OF_FAME; PARK -> BACKYARD_COURT: the four of the highest level stay, the rest go;
--   BASKETBALL_COURT -> BASKETBALL_ARENA, TENNIS_COURT -> TENNIS_ARENA (their sections stay open);
--   APARTMENT, OFFICE: go.
-- One that goes is refunded at what its levels cost (the old tables below, as rules.BuildingCatalog had them), the coins
-- added to the club; every change is logged in rehome_log (mask_id 'sport_town'): RETYPED (type = the old type, where it
-- stands, its level), REMOVED (its level and the coins refunded), so a revert can put the town back. An alliance help
-- asked for a building that went is closed; one for a building that changed type names the new type.
-- The footprints changed (a shopping centre is 5x4, the arenas 8x6 and stand on their own islands only), so the clubs
-- touched are settled again at the next start (clubs.land_mask NULL: the start-up sweep, ClubService.settle): what now
-- overlaps another building or stands where it may not moves to the nearest free spot, an arena onto its island if the
-- club's zone opened it, else it waits parked off the land until the player places it.

CREATE TEMP TABLE v36_cost (type VARCHAR(32) NOT NULL, level INT NOT NULL, cost BIGINT NOT NULL);
INSERT INTO v36_cost (type, level, cost) VALUES
    ('CLUB_SHOP', 1, 3000), ('CLUB_SHOP', 2, 5500), ('CLUB_SHOP', 3, 10000), ('CLUB_SHOP', 4, 19000), ('CLUB_SHOP', 5, 34000),
    ('CLUB_SHOP', 6, 63000), ('CLUB_SHOP', 7, 120000), ('CLUB_SHOP', 8, 210000), ('CLUB_SHOP', 9, 390000), ('CLUB_SHOP', 10, 720000),
    ('CAFE', 1, 2100), ('CAFE', 2, 4000), ('CAFE', 3, 7500), ('CAFE', 4, 14000), ('CAFE', 5, 27000),
    ('CAFE', 6, 51000), ('CAFE', 7, 97000), ('CAFE', 8, 180000), ('CAFE', 9, 350000), ('CAFE', 10, 660000),
    ('PARKING', 1, 4500), ('PARKING', 2, 8000), ('PARKING', 3, 14000), ('PARKING', 4, 25000), ('PARKING', 5, 44000),
    ('PARKING', 6, 79000), ('PARKING', 7, 140000), ('PARKING', 8, 250000), ('PARKING', 9, 440000), ('PARKING', 10, 780000),
    ('MUSEUM', 1, 9000), ('MUSEUM', 2, 25000), ('MUSEUM', 3, 70000), ('MUSEUM', 4, 180000), ('MUSEUM', 5, 450000),
    ('PARK', 1, 5000), ('PARK', 2, 12000), ('PARK', 3, 30000), ('PARK', 4, 70000), ('PARK', 5, 150000),
    ('PARK', 6, 300000), ('PARK', 7, 600000), ('PARK', 8, 900000),
    ('BASKETBALL_COURT', 1, 7500), ('BASKETBALL_COURT', 2, 14000), ('BASKETBALL_COURT', 3, 25000), ('BASKETBALL_COURT', 4, 47000),
    ('BASKETBALL_COURT', 5, 86000), ('BASKETBALL_COURT', 6, 160000), ('BASKETBALL_COURT', 7, 290000), ('BASKETBALL_COURT', 8, 540000),
    ('TENNIS_COURT', 1, 12000), ('TENNIS_COURT', 2, 21000), ('TENNIS_COURT', 3, 37000), ('TENNIS_COURT', 4, 64000),
    ('TENNIS_COURT', 5, 110000), ('TENNIS_COURT', 6, 200000), ('TENNIS_COURT', 7, 340000), ('TENNIS_COURT', 8, 600000),
    ('APARTMENT', 1, 9000), ('APARTMENT', 2, 15000), ('APARTMENT', 3, 25000), ('APARTMENT', 4, 42000), ('APARTMENT', 5, 70000),
    ('APARTMENT', 6, 115000), ('APARTMENT', 7, 190000), ('APARTMENT', 8, 300000), ('APARTMENT', 9, 470000), ('APARTMENT', 10, 720000),
    ('OFFICE', 1, 16000), ('OFFICE', 2, 27000), ('OFFICE', 3, 45000), ('OFFICE', 4, 75000), ('OFFICE', 5, 120000),
    ('OFFICE', 6, 190000), ('OFFICE', 7, 300000), ('OFFICE', 8, 460000), ('OFFICE', 9, 700000), ('OFFICE', 10, 1000000);

-- every old building's fate: its new type (NULL = it goes), kept = it stays as that type
CREATE TEMP TABLE v36_fate AS
SELECT b.id, b.club_id, b.type, b.level, b.grid_x, b.grid_y, b.rotation, g.new_type,
       g.new_type IS NOT NULL AND ROW_NUMBER() OVER (PARTITION BY b.club_id, g.new_type ORDER BY b.level DESC, b.created_at, b.id) <= g.keep AS kept
FROM buildings b
JOIN (VALUES ('CLUB_SHOP', 'SHOPPING_CENTER', 2), ('CAFE', 'SHOPPING_CENTER', 2), ('PARKING', 'SHOPPING_CENTER', 2),
             ('MUSEUM', 'HALL_OF_FAME', 1), ('PARK', 'BACKYARD_COURT', 4),
             ('BASKETBALL_COURT', 'BASKETBALL_ARENA', 1), ('TENNIS_COURT', 'TENNIS_ARENA', 1),
             ('APARTMENT', NULL, 0), ('OFFICE', NULL, 0)) AS g (old_type, new_type, keep)
  ON g.old_type = b.type;

INSERT INTO rehome_log (club_id, item_id, kind, type, old_x, old_y, old_rotation, new_x, new_y, new_rotation, gold, mask_id, at, level, coins)
SELECT f.club_id, f.id, CASE WHEN f.kept THEN 'RETYPED' ELSE 'REMOVED' END, f.type, f.grid_x, f.grid_y, f.rotation,
       CASE WHEN f.kept THEN f.grid_x END, CASE WHEN f.kept THEN f.grid_y END, f.rotation, 0, 'sport_town', now(), f.level,
       CASE WHEN f.kept THEN 0 ELSE (SELECT COALESCE(SUM(c.cost), 0) FROM v36_cost c WHERE c.type = f.type AND c.level <= f.level) END
FROM v36_fate f;

UPDATE clubs SET coins = clubs.coins + r.coins, updated_at = now()
FROM (SELECT club_id, SUM(coins) AS coins FROM rehome_log WHERE mask_id = 'sport_town' AND kind = 'REMOVED' GROUP BY club_id) r
WHERE clubs.id = r.club_id AND r.coins > 0;

UPDATE alliance_helps SET closed = TRUE WHERE building_id IN (SELECT id FROM v36_fate WHERE NOT kept);
UPDATE alliance_helps h SET building_type = f.new_type FROM v36_fate f WHERE h.building_id = f.id AND f.kept;

DELETE FROM buildings WHERE id IN (SELECT id FROM v36_fate WHERE NOT kept);
UPDATE buildings b SET type = f.new_type, updated_at = now() FROM v36_fate f WHERE b.id = f.id AND f.kept;

-- the clubs touched move again at the next start (footprints grew; the arenas go onto their islands)
UPDATE clubs SET land_mask = NULL WHERE id IN (SELECT DISTINCT club_id FROM v36_fate);

DROP TABLE v36_fate;
DROP TABLE v36_cost;
