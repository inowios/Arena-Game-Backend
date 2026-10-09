-- The weekly arena chest: the ISO week (year*100 + week) it was last claimed in.
ALTER TABLE clubs ADD COLUMN arena_chest_week INT NOT NULL DEFAULT 0;
