-- The city builder is gone (17 October 2026, the five islands map: fixed map spots, V48). The game has no players yet,
-- so its tables and columns go outright (the old builder lives on in the branch saved/city-builder-levels).
DROP TABLE IF EXISTS alliance_help_by;
DROP TABLE IF EXISTS alliance_helps;
DROP TABLE IF EXISTS rehome_log;
DROP TABLE IF EXISTS club_layouts;
DROP TABLE IF EXISTS obstacles;
DROP TABLE IF EXISTS land_parcels;
DROP TABLE IF EXISTS buildings;

ALTER TABLE clubs
    DROP COLUMN IF EXISTS land_mask,
    DROP COLUMN IF EXISTS builder_slots,
    DROP COLUMN IF EXISTS double_income_until,
    DROP COLUMN IF EXISTS double_income_ad_at,
    DROP COLUMN IF EXISTS free_speedup_date,
    DROP COLUMN IF EXISTS obstacles_spawned_at;
