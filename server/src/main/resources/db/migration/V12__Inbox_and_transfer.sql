-- In-app inbox (what pushes announced, kept for players without notifications) and device transfer codes.
CREATE TABLE inbox (
    id UUID PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    kind VARCHAR(24) NOT NULL,
    data_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ
);
CREATE INDEX idx_inbox_club ON inbox (club_id, created_at DESC);

CREATE TABLE transfer_codes (
    code VARCHAR(12) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_transfer_codes_user ON transfer_codes (user_id);
