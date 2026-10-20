-- The Arena match result is tied to its start (docs/claude/APP-AUDIT.md §2.13 in the mobile repo, 20 October 2026):
-- POST /club/arena/start hands out a match (its token = id, the stake, the story chapter it is played for) and
-- POST /club/arena/result takes the result of that match once. The club counts the matches that paid coins today
-- (rules.Economy.ARENA_REWARDED_PER_DAY) in arena_day / arena_rewarded.
CREATE TABLE arena_matches (
    id          UUID PRIMARY KEY,
    club_id     UUID        NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    stake       INT         NOT NULL,
    story       INT         NULL,
    status      VARCHAR(12) NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NULL,
    won         BOOLEAN     NULL
);
CREATE INDEX arena_matches_club_open ON arena_matches (club_id) WHERE status = 'OPEN';

ALTER TABLE clubs
    ADD COLUMN arena_day DATE NULL,
    ADD COLUMN arena_rewarded INT NOT NULL DEFAULT 0;
