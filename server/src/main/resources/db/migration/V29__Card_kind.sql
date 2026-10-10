-- The card kind a player is printed on (plan §3b): CLASSIC, TOURNAMENT, PREMIUM, GOLD, LEGEND, ARENA.
ALTER TABLE athletes ADD COLUMN card_kind VARCHAR(12) NOT NULL DEFAULT 'CLASSIC';
