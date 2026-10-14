-- The footprints grew (the owner, 14 October 2026, pass 2: the buildings were too small when zoomed out): the stadium
-- 8x6 -> 12x9, the town's types about x1.45 (the academy 5x4 -> 7x6, the house 3x3 -> 4x4, ...), the island arenas
-- 8x6 -> 11x8 (BuildingCatalog.specs), and the land reaches the painted meadow's edge (land_mask 71ee7376, 2747 main
-- cells). The new mask's id alone re-settles every club, but the sizes are what moves the buildings, so the clubs are
-- marked here as well: the start-up sweep (ClubService.settle, clubs.land_mask NULL) keeps the stadium's place first,
-- then the biggest, the oldest first; whatever now overlaps a neighbour or stands off the land goes to the nearest free
-- spot (an arena onto its own island while that is open), a road or fence tile under a grown building goes and is
-- refunded, and a building the land has no room for stays parked off the map, listed for the player to place by hand.
-- Every move, removal and refund is logged in rehome_log under the mask's id. The showcase towns (tz_bucket -1) are
-- rebuilt from their layout at start-up and are left out.

UPDATE clubs SET land_mask = NULL WHERE tz_bucket IS DISTINCT FROM -1;
