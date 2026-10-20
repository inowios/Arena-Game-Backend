-- 20 October 2026 (docs/claude/APP-AUDIT.md §1 in the mobile repo): the systems the heroes and the Arena v4 match replaced
-- go from the schema too — card packs and the day's pack offer (pack_opens), the store's daily offers (offer_purchases),
-- the transfer market (market_purchases) and the old live match played with athletes (live_matches; the arena trophies
-- and the weekly chest stay on clubs: trophies, live_streak, arena_chest_week). The clubs' columns of the academy, the
-- hospital, scouting and the market go with their routes. The game has no players yet, so nothing is migrated.
DROP TABLE IF EXISTS pack_opens;
DROP TABLE IF EXISTS offer_purchases;
DROP TABLE IF EXISTS market_purchases;
DROP TABLE IF EXISTS live_matches;

ALTER TABLE clubs
    DROP COLUMN IF EXISTS offer_day,
    DROP COLUMN IF EXISTS market_salt,
    DROP COLUMN IF EXISTS scout_signed_date,
    DROP COLUMN IF EXISTS academy_next_at,
    DROP COLUMN IF EXISTS medical_next_at;
