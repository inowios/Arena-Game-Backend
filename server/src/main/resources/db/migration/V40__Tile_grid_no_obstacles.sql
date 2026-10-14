-- The build grid's tile is 2x2 cells (the owner, 14 October 2026, pass 3: «сетку сделай не такую мелкую»; «многие
-- здания визуально выходят за рамки своей зоны»): every footprint is a whole number of 20 m tiles, its proportions taken
-- from its own drawing (BuildingCatalog.specs: the stadium 12x12, the ticket office 4x4 as drawn, the island arenas
-- 10x10, 10x8 and 12x8, DECOR, FENCE and ROAD one tile each), and every building stands on a tile (its top-left cell
-- even both ways; the API keeps cells, the server checks it: Grid.aligned). The land mask is the same (c9c2cb41), only its
-- anchors moved onto tiles (the stadium 11,12 -> 10,10, the first ticket office 3,22 -> 4,22).
--
-- Every club settled once more for that: the start-up sweep (ClubService.settle, clubs.land_mask NULL) keeps the stadium's
-- place first when it is on a tile and fits, then the biggest, the oldest first; whatever is off a tile, overlaps a
-- neighbour or stands off the land goes to the nearest free tile (an arena onto its own island while that is open); a
-- road or fence tile that cannot stay is removed and refunded (a drawn road of 10 m cells keeps every other cell, each
-- now a 20 m tile); a building the land has no room for stays parked, listed in edit mode for the player to place by
-- hand. Every move, removal and refund is logged in rehome_log under the mask's id and per club in the server log. The
-- showcase towns (tz_bucket -1) are rebuilt from their layout at start-up and are left out.
--
-- And no obstacles for now (the owner: «прибери камни и т.д., которые случайно появляются — уберём пока этот
-- функционал»; Obstacles.ENABLED = false): every club's rocks, bushes, stumps, rubbish and sheds go, whatever their
-- state, with nothing refunded (the owner's decision: a clearing's coins and the gold one held are not paid back), and
-- the spawn clock is reset so they would start afresh if they are ever turned on again.

DELETE FROM obstacles;
UPDATE clubs SET obstacles_spawned_at = NULL;
UPDATE clubs SET land_mask = NULL WHERE tz_bucket IS DISTINCT FROM -1;
