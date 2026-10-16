-- The story campaign «Іскра», season 1 (docs/claude/HEROES-INSTEAD-OF-CARDS.md §12 in the mobile repo; rules.Story):
-- one row per club. chapter = the chapter the club works on (1..6; the ones before are claimed, 7 = the season is over),
-- progress = that chapter's goals as «c1.o1=1,c1.o2=3» (objective id = count). No row = chapter 1, nothing done yet.
CREATE TABLE club_story (
    club_id UUID PRIMARY KEY REFERENCES clubs(id),
    chapter INT NOT NULL DEFAULT 1 CHECK (chapter BETWEEN 1 AND 7),
    progress VARCHAR(255) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL
);
