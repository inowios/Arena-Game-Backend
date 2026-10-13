-- The land migration deletes the road and fence tiles left off the land mask and refunds what their levels cost
-- (docs/art/PERSPECTIVE-MAP.md §5.3 step 4 in the mobile repo; moved tile by tile, a boulevard the new shore cut came
-- back as stray tiles at the meadow's edge): rehome_log kind REMOVED, new NULL, with the tile's level and the coins given
-- back, so a revert can put it back and take them again.
ALTER TABLE rehome_log ADD COLUMN level INT NOT NULL DEFAULT 0;
ALTER TABLE rehome_log ADD COLUMN coins BIGINT NOT NULL DEFAULT 0;
