-- The land as a mask (the owner's picture, 13 October; docs/art/PERSPECTIVE-MAP.md §5 in the mobile repo): the buildable
-- cells follow the painted meadow (resources/land/land_mask.json, its sha1 id). clubs.land_mask = the mask the club's
-- buildings were last moved onto; NULL = never, so the start-up sweep settles every club once (ClubService.settle).
ALTER TABLE clubs ADD COLUMN land_mask VARCHAR(40);
-- Every move of that migration, so a revert can put a town back: a building from old to new (new NULL = it stayed
-- parked for lack of room), an obstacle taken off the land (new NULL, the gold it held given to the club).
CREATE TABLE rehome_log (
    id BIGSERIAL PRIMARY KEY,
    club_id UUID NOT NULL REFERENCES clubs(id) ON DELETE CASCADE,
    item_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    type VARCHAR(32) NOT NULL,
    old_x INT NOT NULL,
    old_y INT NOT NULL,
    old_rotation INT NOT NULL,
    new_x INT NULL,
    new_y INT NULL,
    new_rotation INT NOT NULL,
    gold INT NOT NULL DEFAULT 0,
    mask_id VARCHAR(40) NOT NULL,
    at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_rehome_log_club ON rehome_log (club_id);
-- land_parcels (V33) stays, unread, so the main branch's old map keeps its rows; a later migration drops it.
