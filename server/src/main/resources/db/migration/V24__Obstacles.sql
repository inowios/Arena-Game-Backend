-- Obstacles on the club plot (rocks, bushes, stumps, rubbish, an old shed): cleared by a builder for coins, they hold
-- gold; a new one turns up every few hours while there is room. obstacles_spawned_at = when the last one turned up.
ALTER TABLE clubs ADD COLUMN obstacles_spawned_at TIMESTAMPTZ;

CREATE TABLE obstacles (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL,
    grid_x INT NOT NULL,
    grid_y INT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    clear_ends_at TIMESTAMPTZ,
    reward_gold INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_obstacles_club ON obstacles (club_id);
