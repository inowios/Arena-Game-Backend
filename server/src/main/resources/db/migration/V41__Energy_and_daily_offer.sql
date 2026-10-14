-- Energy and the daily pack offer (the owner, 14 October 2026: «энергия» and the «−50%» card made real).
--
-- Energy: at most 30, +1 every 6 minutes. Nothing ticks on the server: [energy] is the amount at [energy_at] and the
-- current amount is computed on read (rules.Energy); NULL energy_at = counted as full. A card match «Три зони» costs 5,
-- a friendly 3, league matchday matches nothing; gems refill it to full.
--
-- The daily offer: one discounted pack a UTC day (rules.DailyOffers); [offer_day] = the UTC day the club last bought it
-- (once a day).
-- (IF NOT EXISTS: the migration tests add these columns to an older schema so today's code can read it, it/TestDb.)
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS energy INT NOT NULL DEFAULT 30;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS energy_at TIMESTAMPTZ NULL;
ALTER TABLE clubs ADD COLUMN IF NOT EXISTS offer_day DATE NULL;
