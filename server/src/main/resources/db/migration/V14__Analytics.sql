-- Days on which a user made an authenticated request (UTC calendar days): D1/D7 retention, DAU/WAU/MAU, cup entry.
CREATE TABLE user_days (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day DATE NOT NULL,
    PRIMARY KEY (user_id, day)
);
CREATE INDEX idx_user_days_day ON user_days (day);

-- Product events: reported by the app in batches (tutorial funnel, store, ads, shares) and written by the server
-- (club created, account deleted, support grants). props is a small JSON object of strings.
CREATE TABLE analytics_events (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID,
    name VARCHAR(40) NOT NULL,
    props TEXT,
    platform VARCHAR(10),
    app_version VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_analytics_events_name_time ON analytics_events (name, created_at);
CREATE INDEX idx_analytics_events_user ON analytics_events (user_id, created_at);
