-- Heroes instead of cards, part 2 (docs/claude/HEROES-INSTEAD-OF-CARDS.md §11 in the mobile repo).
-- 1. Training in the training centre: a hero in a session has training_ends_at (NULL = not training) and the
--    experience it will give, training_xp (rules.Heroes.TRAINING_XP by the centre's level); claimed after the timer.
ALTER TABLE club_heroes
    ADD COLUMN training_ends_at TIMESTAMPTZ NULL,
    ADD COLUMN training_xp INT NOT NULL DEFAULT 0 CHECK (training_xp >= 0);

-- 2. The «Склад» lineup of the card match «Стрілка»: up to 5 field heroes and 4 on the bench (comma-separated catalog
--    ids, in order) and the keeper (NULL = an academy pupil in goal). No row = the match picks the best heroes itself.
CREATE TABLE club_hero_lineups (
    club_id UUID PRIMARY KEY REFERENCES clubs(id),
    field VARCHAR(64) NOT NULL DEFAULT '',
    keeper VARCHAR(8) NULL,
    bench VARCHAR(64) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL
);
