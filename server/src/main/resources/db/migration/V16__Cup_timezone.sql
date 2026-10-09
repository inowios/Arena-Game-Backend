-- A cup's local time zone (the most common one among its human entrants): rounds are at 18:00 there, daylight saving and
-- half-hour offsets included. NULL (cups drawn before this column) = the bucket's whole-hour offset.
ALTER TABLE cups ADD COLUMN timezone VARCHAR(64);
