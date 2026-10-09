-- The player's language (en, es, pt, ru, uk) for server texts sent outside a request (pushes).
ALTER TABLE users ADD COLUMN locale VARCHAR(8);
