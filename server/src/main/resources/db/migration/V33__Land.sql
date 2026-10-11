-- The land round the club (the eras map, 13 October): a field of 6x6 parcels of 12 cells round the plot (the plot is
-- the centre 2x2 parcels, cells 3..26 as before; the field runs from cell -21 to 50). One row per bought parcel; the
-- four home parcels need none.
CREATE TABLE land_parcels (
    club_id UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    col INT NOT NULL,
    row INT NOT NULL,
    bought_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (club_id, col, row)
);
-- the old drawn fences go (the owner: no old fence in the hand-drawn world); roads are drawn instead
DELETE FROM buildings WHERE type = 'FENCE';
