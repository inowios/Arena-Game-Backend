-- The first ticket office in front of the stadium (14 October 2026, the sport town's integration): a new club's ticket
-- office stood behind the stadium (land mask anchors: the stadium at 11,12, the ticket office at 9,9), and the sport
-- town's stadium (8x6, its stands and floodlights tall at the back; the camera looks from +x+z) hides it. New clubs get it
-- in front of the stadium's long side (land_mask.json anchor ticket 14,18). A club whose stadium and first ticket office
-- still stand where the seeding put them (the stadium at 11,12, the ticket office at 9,9 and not turned) gets it moved
-- there too, when those 5x2 cells hold no building and no obstacle; any other club keeps its town as the player laid it.
-- Each move is logged in rehome_log (kind BUILDING, mask_id 'ticket_front'), so a revert can put it back.

-- every type's footprint (rules.BuildingCatalog; turned 90 or 270 degrees: h x w); an unknown type counts as 8x8
CREATE TEMP TABLE v37_size (type VARCHAR(32) PRIMARY KEY, w INT NOT NULL, h INT NOT NULL);
INSERT INTO v37_size (type, w, h) VALUES
    ('PITCH', 8, 6), ('ACADEMY', 5, 4), ('TRAINING_CENTER', 6, 4), ('MEDICAL_CENTER', 4, 4), ('TICKET_OFFICE', 5, 2),
    ('DECOR', 1, 1), ('FENCE', 1, 1), ('HOUSE', 3, 3), ('ROAD', 1, 1), ('SHOPPING_CENTER', 5, 4),
    ('HALL_OF_FAME', 4, 5), ('BACKYARD_COURT', 3, 3), ('FAN_ZONE', 4, 4), ('MEDIA_CENTER', 4, 3),
    ('SPONSOR_OFFICE', 3, 3), ('HOTEL', 4, 4), ('BASKETBALL_ARENA', 8, 6), ('VOLLEYBALL_ARENA', 8, 6),
    ('TENNIS_ARENA', 8, 6), ('PADEL_ARENA', 8, 6), ('ATHLETES_RESIDENCE', 5, 3), ('YOUTH_SPORTS_SCHOOL', 4, 4),
    ('SPORTS_SCIENCE_LAB', 4, 4), ('SCHOOL', 4, 4), ('TRANSPORT_HUB', 4, 3), ('SCOUTING_OFFICE', 3, 3),
    ('RECOVERY_CENTER', 4, 4), ('ANALYTICS_CENTER', 3, 3), ('COACHING_HQ', 3, 3);

-- the ticket offices still at the seeding's spot behind a stadium still at its own, with the cells in front free
-- (x 14..18, y 18..19); an obstacle is 1x1, a shed 2x2 (rules.ObstacleKind)
CREATE TEMP TABLE v37_move AS
SELECT t.id, t.club_id, t.level, t.rotation
FROM buildings t
WHERE t.type = 'TICKET_OFFICE' AND t.grid_x = 9 AND t.grid_y = 9 AND t.rotation % 180 = 0
  AND EXISTS (SELECT 1 FROM buildings p WHERE p.club_id = t.club_id AND p.type = 'PITCH' AND p.grid_x = 11 AND p.grid_y = 12)
  AND NOT EXISTS (
      SELECT 1 FROM buildings o LEFT JOIN v37_size s ON s.type = o.type
      WHERE o.club_id = t.club_id AND o.id <> t.id
        AND o.grid_x < 14 + 5 AND o.grid_x + CASE WHEN o.rotation % 180 = 0 THEN COALESCE(s.w, 8) ELSE COALESCE(s.h, 8) END > 14
        AND o.grid_y < 18 + 2 AND o.grid_y + CASE WHEN o.rotation % 180 = 0 THEN COALESCE(s.h, 8) ELSE COALESCE(s.w, 8) END > 18)
  AND NOT EXISTS (
      SELECT 1 FROM obstacles x
      WHERE x.club_id = t.club_id
        AND x.grid_x < 14 + 5 AND x.grid_x + CASE WHEN x.kind = 'SHED' THEN 2 ELSE 1 END > 14
        AND x.grid_y < 18 + 2 AND x.grid_y + CASE WHEN x.kind = 'SHED' THEN 2 ELSE 1 END > 18);

INSERT INTO rehome_log (club_id, item_id, kind, type, old_x, old_y, old_rotation, new_x, new_y, new_rotation, gold, mask_id, at, level, coins)
SELECT m.club_id, m.id, 'BUILDING', 'TICKET_OFFICE', 9, 9, m.rotation, 14, 18, m.rotation, 0, 'ticket_front', now(), m.level, 0
FROM v37_move m;

UPDATE buildings SET grid_x = 14, grid_y = 18, updated_at = now() WHERE id IN (SELECT id FROM v37_move);

DROP TABLE v37_move;
DROP TABLE v37_size;
