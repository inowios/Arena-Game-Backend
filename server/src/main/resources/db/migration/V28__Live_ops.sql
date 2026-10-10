-- Live game: admin events (the calendar's recurring ones are computed), progress on event goals, developer mail with
-- gifts, daily offers bought.
CREATE TABLE game_events (
    id VARCHAR(64) PRIMARY KEY,
    key VARCHAR(32) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    build_time_pct INT NOT NULL DEFAULT 100,
    income_pct INT NOT NULL DEFAULT 100,
    obstacle_gold_pct INT NOT NULL DEFAULT 100,
    goal VARCHAR(32),
    goal_target INT NOT NULL DEFAULT 0,
    reward_coins BIGINT NOT NULL DEFAULT 0,
    reward_gold INT NOT NULL DEFAULT 0,
    reward_item VARCHAR(40)
);

CREATE TABLE event_progress (
    event_id VARCHAR(64) NOT NULL,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    count INT NOT NULL DEFAULT 0,
    claimed_at TIMESTAMPTZ,
    PRIMARY KEY (event_id, club_id)
);

CREATE TABLE dev_mail (
    id UUID PRIMARY KEY,
    title VARCHAR(80) NOT NULL,
    body VARCHAR(1000) NOT NULL DEFAULT '',
    coins BIGINT NOT NULL DEFAULT 0,
    gold INT NOT NULL DEFAULT 0,
    item VARCHAR(40),
    club_id UUID REFERENCES clubs (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_dev_mail_expires ON dev_mail (expires_at);

CREATE TABLE dev_mail_claims (
    mail_id UUID NOT NULL REFERENCES dev_mail (id) ON DELETE CASCADE,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (mail_id, club_id)
);

CREATE TABLE offer_purchases (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    day INT NOT NULL,
    offer_id VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, day, offer_id)
);
