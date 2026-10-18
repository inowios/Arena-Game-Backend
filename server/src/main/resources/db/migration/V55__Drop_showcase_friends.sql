-- The two showcase friends (bot clubs outside every league, tz_bucket -1) showed what a built city could become; the
-- city builder is gone (the owner, 18 October 2026): they leave with everything that names them, and their bot users.
DO $$
DECLARE ids UUID[]; owners UUID[]; t RECORD;
BEGIN
    SELECT array_agg(id), array_agg(user_id) INTO ids, owners FROM clubs WHERE tz_bucket = -1 AND is_bot;
    IF ids IS NULL THEN RETURN; END IF;
    FOR t IN SELECT table_name FROM information_schema.columns
             WHERE table_schema = 'public' AND column_name = 'club_id' AND table_name <> 'clubs' LOOP
        EXECUTE format('DELETE FROM %I WHERE club_id = ANY($1)', t.table_name) USING ids;
    END LOOP;
    DELETE FROM clubs WHERE id = ANY(ids);
    DELETE FROM users WHERE id = ANY(owners) AND auth_provider = 'BOT';
END $$;
