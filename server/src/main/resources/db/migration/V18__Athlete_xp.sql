-- Match experience: starters earn XP in live matches; every hundred points raises one attribute (within potential).
ALTER TABLE athletes ADD COLUMN xp INT NOT NULL DEFAULT 0;
