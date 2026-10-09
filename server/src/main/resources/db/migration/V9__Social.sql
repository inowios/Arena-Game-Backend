-- Social layer: private-league chat (with reports and blocks), private-league seasons, friendly challenges by link,
-- invite attribution (k-factor).
CREATE TABLE league_messages (
    id UUID PRIMARY KEY,
    league_id UUID NOT NULL REFERENCES leagues (id) ON DELETE CASCADE,
    club_id UUID REFERENCES clubs (id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL DEFAULT 'USER',
    text VARCHAR(400) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_league_messages_league ON league_messages (league_id, created_at DESC);

CREATE TABLE message_reports (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES league_messages (id) ON DELETE CASCADE,
    reporter_club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    reason VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, reporter_club_id)
);

CREATE TABLE club_blocks (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    blocked_club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, blocked_club_id)
);

CREATE TABLE private_league_seasons (
    id UUID PRIMARY KEY,
    league_id UUID NOT NULL REFERENCES leagues (id) ON DELETE CASCADE,
    season_number INT NOT NULL,
    winner_club_id UUID REFERENCES clubs (id) ON DELETE SET NULL,
    table_json TEXT NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    UNIQUE (league_id, season_number)
);

CREATE TABLE challenges (
    id UUID PRIMARY KEY,
    code VARCHAR(10) NOT NULL UNIQUE,
    challenger_club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    accepted_by_club_id UUID REFERENCES clubs (id) ON DELETE SET NULL,
    fixture_id UUID REFERENCES fixtures (id) ON DELETE SET NULL,
    status VARCHAR(10) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_challenges_challenger ON challenges (challenger_club_id);

CREATE TABLE invites (
    id UUID PRIMARY KEY,
    inviter_club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    invitee_club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    kind VARCHAR(10) NOT NULL,
    code VARCHAR(10) NOT NULL,
    new_player BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (invitee_club_id, kind, code)
);
CREATE INDEX idx_invites_inviter ON invites (inviter_club_id);
