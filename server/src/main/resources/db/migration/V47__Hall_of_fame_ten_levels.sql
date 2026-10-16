-- The hall of fame has ten levels (16 October 2026, the owner's building system: every building ten levels; five before).
-- The new level 2k gives what the old level k gave (exhibits and atmosphere, rules.MuseumRules), so every hall of fame
-- built doubles its level: nobody loses what they had, and an upgrade under way finishes at the doubled level.

UPDATE buildings SET level = LEAST(level * 2, 10) WHERE type = 'HALL_OF_FAME';
