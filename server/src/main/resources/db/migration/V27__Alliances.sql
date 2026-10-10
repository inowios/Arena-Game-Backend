-- Alliances (clubs band together): members with roles, join requests, help on constructions, the weekend tournament
-- of arena wins and weekly likes on clubs (visits 2.0). The chat reuses league_messages with the alliance id as the
-- channel, so that table's league foreign key goes (a channel is a private league or an alliance).
CREATE TABLE alliances (
    id UUID PRIMARY KEY,
    name VARCHAR(24) NOT NULL UNIQUE,
    tag VARCHAR(5) NOT NULL,
    description VARCHAR(160) NOT NULL DEFAULT '',
    emblem_json TEXT,
    open BOOLEAN NOT NULL DEFAULT TRUE,
    min_trophies INT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE alliance_members (
    alliance_id UUID NOT NULL REFERENCES alliances (id) ON DELETE CASCADE,
    club_id UUID NOT NULL UNIQUE REFERENCES clubs (id) ON DELETE CASCADE,
    role VARCHAR(12) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    helps_given INT NOT NULL DEFAULT 0,
    PRIMARY KEY (alliance_id, club_id)
);

CREATE TABLE alliance_requests (
    alliance_id UUID NOT NULL REFERENCES alliances (id) ON DELETE CASCADE,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    message VARCHAR(120) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (alliance_id, club_id)
);

CREATE TABLE alliance_helps (
    id UUID PRIMARY KEY,
    alliance_id UUID NOT NULL REFERENCES alliances (id) ON DELETE CASCADE,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    building_id UUID NOT NULL,
    building_type VARCHAR(32) NOT NULL,
    level INT NOT NULL,
    helps INT NOT NULL DEFAULT 0,
    max_helps INT NOT NULL,
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alliance_helps_open ON alliance_helps (alliance_id) WHERE NOT closed;

CREATE TABLE alliance_help_by (
    help_id UUID NOT NULL REFERENCES alliance_helps (id) ON DELETE CASCADE,
    helper_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (help_id, helper_id)
);
CREATE INDEX idx_alliance_help_by_helper ON alliance_help_by (helper_id, created_at);

CREATE TABLE alliance_weekend (
    week INT NOT NULL,
    alliance_id UUID NOT NULL REFERENCES alliances (id) ON DELETE CASCADE,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    wins INT NOT NULL DEFAULT 0,
    PRIMARY KEY (week, alliance_id, club_id)
);

CREATE TABLE alliance_weekend_claims (
    week INT NOT NULL,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (week, club_id)
);

CREATE TABLE club_likes (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    liker_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    week INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, liker_id, week)
);
CREATE INDEX idx_club_likes_week ON club_likes (week, club_id);

ALTER TABLE league_messages DROP CONSTRAINT IF EXISTS league_messages_league_id_fkey;
