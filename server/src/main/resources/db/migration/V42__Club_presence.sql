-- Presence (14 October 2026): the alliance's members list shows who is online (a green dot) and when the others were
-- last seen («12 хв тому»). [last_seen_at] is written on the club owner's authenticated requests, at most once a minute
-- per user (club.PresenceTracker); a member is online when seen within the last 5 minutes (rules in club.Presence).
-- NULL = not seen since this column came (shown as offline, no time).
-- (IF NOT EXISTS: the migration tests add this column to an older schema so today's code can read it, it/TestDb.)
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NULL;
