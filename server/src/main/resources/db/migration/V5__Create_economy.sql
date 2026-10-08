ALTER TABLE clubs ADD COLUMN login_day INT NOT NULL DEFAULT 0;
ALTER TABLE clubs ADD COLUMN login_last DATE;
ALTER TABLE clubs ADD COLUMN streak INT NOT NULL DEFAULT 0;
ALTER TABLE clubs ADD COLUMN double_income_until TIMESTAMPTZ;
ALTER TABLE clubs ADD COLUMN double_income_ad_at TIMESTAMPTZ;
ALTER TABLE clubs ADD COLUMN free_speedup_date DATE;

CREATE TABLE season_pass (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    season INT NOT NULL,
    xp INT NOT NULL DEFAULT 0,
    premium BOOLEAN NOT NULL DEFAULT FALSE,
    claimed_free INT NOT NULL DEFAULT 0,
    claimed_premium INT NOT NULL DEFAULT 0,
    PRIMARY KEY (club_id, season)
);

CREATE TABLE daily_tasks (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    day DATE NOT NULL,
    task VARCHAR(24) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    claimed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (club_id, day, task)
);

CREATE TABLE purchases (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id),
    store VARCHAR(8) NOT NULL,
    product_id VARCHAR(64) NOT NULL,
    external_id VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(12) NOT NULL,
    granted_gold INT NOT NULL DEFAULT 0,
    granted_coins BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_purchases_club ON purchases (club_id);
