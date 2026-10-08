CREATE TABLE clubs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL,
    primary_color CHAR(7) NOT NULL,
    secondary_color CHAR(7) NOT NULL,
    emblem_json TEXT,
    level INT NOT NULL DEFAULT 1,
    reputation INT NOT NULL DEFAULT 0,
    coins BIGINT NOT NULL DEFAULT 2500,
    gold INT NOT NULL DEFAULT 50,
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    academy_next_at TIMESTAMPTZ,
    medical_next_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE buildings (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    level INT NOT NULL DEFAULT 1,
    grid_x INT NOT NULL,
    grid_y INT NOT NULL,
    rotation INT NOT NULL DEFAULT 0,
    state VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    upgrade_ends_at TIMESTAMPTZ,
    last_collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (club_id, grid_x, grid_y)
);
CREATE INDEX idx_buildings_club ON buildings (club_id);
