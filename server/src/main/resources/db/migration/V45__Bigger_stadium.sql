-- The stadium grew (16 October 2026, the owner: the stadium is the colossal centrepiece of the city, make it bigger;
-- its level-10 picture is the stadium-city): PITCH 12x12 -> 16x16 cells (BuildingCatalog.specs; the biggest that keeps a
-- full stadium-10 town within 80 % of the main island). The land mask is the same, so the clubs are marked here: the
-- start-up sweep (ClubService.settle, clubs.land_mask NULL) keeps the stadium's place first — a stadium that no longer
-- fits where it stood (by the shore) moves the least it can, up to 4 cells either way — then the biggest, the oldest
-- first; a building the bigger stadium now covers goes to the nearest free tile (with a lawn cell round it where there
-- is room, else parked and listed in edit mode for the player to place by hand), a road or fence tile under it goes and
-- is refunded at what its levels cost. Every move, removal and refund is logged in rehome_log under the mask's id. The
-- showcase towns (tz_bucket -1) are rebuilt from their layout at start-up (Showcase: the ring follows the stadium's size)
-- and are left out.

UPDATE clubs SET land_mask = NULL WHERE tz_bucket IS DISTINCT FROM -1;
