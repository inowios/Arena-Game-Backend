-- Heroes instead of cards (approved 16 October 2026; docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo): a club
-- owns each hero once, for good. hero_id = the catalog id (rules.HeroCatalog, "p001"…); level 1..10 and xp = experience
-- into the current level (rules.Heroes); xp_day / xp_today = the UTC day of the last match experience and how much that
-- day (the daily cap). source: START (the six every club gets) | STORY | CRYSTALS (bought for gold) | MISSION.
-- Nothing is backfilled: a club gets its start six on first access (usecase.athlete.HeroUseCases.ensureStart).
CREATE TABLE club_heroes (
    club_id UUID NOT NULL REFERENCES clubs(id),
    hero_id VARCHAR(8) NOT NULL,
    level INT NOT NULL DEFAULT 1 CHECK (level BETWEEN 1 AND 10),
    xp INT NOT NULL DEFAULT 0 CHECK (xp >= 0),
    xp_day DATE NULL,
    xp_today INT NOT NULL DEFAULT 0,
    source VARCHAR(12) NOT NULL,
    unlocked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (club_id, hero_id)
);
