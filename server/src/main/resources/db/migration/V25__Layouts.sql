-- Saved club layouts (edit mode): a few named arrangements per club. data = "building_id,x,y,rotation;…".
CREATE TABLE club_layouts (
    club_id UUID NOT NULL REFERENCES clubs (id) ON DELETE CASCADE,
    slot INT NOT NULL,
    name VARCHAR(32) NOT NULL,
    data TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, slot)
);
