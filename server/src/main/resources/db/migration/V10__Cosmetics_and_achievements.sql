-- Economy & monetization: cosmetics inventory and equipped looks, decor variants, achievement counters and claims,
-- verified rewarded-ad transactions (AdMob server-side verification).
ALTER TABLE clubs ADD COLUMN kit VARCHAR(40);
ALTER TABLE clubs ADD COLUMN emblem_frame VARCHAR(40);
ALTER TABLE clubs ADD COLUMN celebration VARCHAR(40);
ALTER TABLE buildings ADD COLUMN variant VARCHAR(40);

CREATE TABLE club_items (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    item_id VARCHAR(40) NOT NULL,
    source VARCHAR(16) NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, item_id)
);

CREATE TABLE club_counters (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    metric VARCHAR(24) NOT NULL,
    value BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (club_id, metric)
);

CREATE TABLE club_achievements (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    achievement VARCHAR(32) NOT NULL,
    tier INT NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, achievement)
);

CREATE TABLE ad_rewards (
    transaction_id VARCHAR(128) PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    placement VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Achievements start from what clubs already did: league wins and the current login streak.
INSERT INTO club_counters (club_id, metric, value)
SELECT w.club_id, 'MATCHES_WON', COUNT(*)
  FROM (SELECT home_club_id AS club_id FROM fixtures WHERE status = 'PLAYED' AND kind = 'LEAGUE' AND home_points > away_points
        UNION ALL
        SELECT away_club_id FROM fixtures WHERE status = 'PLAYED' AND kind = 'LEAGUE' AND away_points > home_points) w
  JOIN clubs c ON c.id = w.club_id AND c.is_bot = FALSE
 GROUP BY w.club_id;

INSERT INTO club_counters (club_id, metric, value)
SELECT id, 'BEST_STREAK', streak FROM clubs WHERE is_bot = FALSE AND streak > 0;
