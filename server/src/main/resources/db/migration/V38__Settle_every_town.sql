-- Every town settled once more (the sport town's review, 14 October 2026): V36 settled again only the clubs that had an
-- old building type, but a town laid out before the footprints grew (12 October: the academy 5x4, the training centre
-- 6x4, the stadium 8x6) can hold buildings that overlap one another, or an obstacle under one, without any old type —
-- stadium-1 towns from before then. The start-up sweep (ClubService.settle, clubs.land_mask NULL) moves only what overlaps
-- or stands where it may not, to the nearest free spot (an arena onto its island when that is open, else parked), so a
-- town that is already clean stays exactly as it is; every move is logged in rehome_log. The two showcase towns (bot
-- clubs in the bucket no league draws from, tz_bucket -1) are rebuilt from their layout at start-up and are left out.
--
-- Not made good here: V36 refunded what the levels of a building it removed had cost (APARTMENT, OFFICE, the shops and
-- parks over the new counts) but not the income still stored in it, up to its storage cap (an APARTMENT at level 10:
-- 14 h x 1 650 = 23 100 coins), and its row is gone; on a server where V36 has not run yet it runs as it is (its checksum
-- is fixed now that it is applied in development).

UPDATE clubs SET land_mask = NULL WHERE tz_bucket IS DISTINCT FROM -1;
