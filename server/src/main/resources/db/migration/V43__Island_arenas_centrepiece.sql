-- The island arenas grew (14 October 2026, pass 4: they looked small on their islands; each is now its island's clear
-- centrepiece, as big as the island allows with a lawn cell round it and no bigger than the stadium, BuildingCatalog.specs):
-- the basketball arena 10x10 -> 12x12, the volleyball arena 10x10 -> 12x12, the tennis arena 12x8 -> 12x10, the padel
-- arena 10x8 -> 10x12. The land mask is the same, so the clubs are marked here: the start-up sweep (ClubService.settle,
-- clubs.land_mask NULL) keeps the stadium's place first, then the biggest, the oldest first; an arena that now overlaps
-- or leaves its island goes to the nearest free tile on its own island while that is open (else parked, listed in edit
-- mode for the player to place by hand), a road or fence tile under it goes and is refunded. Every move, removal and
-- refund is logged in rehome_log under the mask's id. The showcase towns (tz_bucket -1) are rebuilt from their layout at
-- start-up and are left out.

UPDATE clubs SET land_mask = NULL WHERE tz_bucket IS DISTINCT FROM -1;
