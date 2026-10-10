-- Talent: the one move an athlete was born for; its steps cost a skill point less.
ALTER TABLE athletes ADD COLUMN talent VARCHAR(12);

-- Every outfield athlete that already exists gets one of his role's moves, picked by a hash of his id so the choice is stable.
UPDATE athletes SET talent = CASE position
    WHEN 'FWD' THEN (ARRAY['SHOOT', 'FEINT', 'CROSS', 'PASS', 'JUMP'])[1 + ('x' || substr(md5(id::text), 1, 2))::bit(8)::int % 5]
    WHEN 'MID' THEN (ARRAY['PASS', 'CROSS', 'FEINT', 'TACKLE', 'SHOOT'])[1 + ('x' || substr(md5(id::text), 1, 2))::bit(8)::int % 5]
    WHEN 'DEF' THEN (ARRAY['TACKLE', 'SLIDE', 'BLOCK', 'JUMP', 'PASS'])[1 + ('x' || substr(md5(id::text), 1, 2))::bit(8)::int % 5]
    END
WHERE position IN ('DEF', 'MID', 'FWD') AND talent IS NULL;
