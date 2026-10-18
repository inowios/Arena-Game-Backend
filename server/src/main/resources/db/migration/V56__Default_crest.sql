-- The club crests (the owner, 18 October 2026): 15 painted crests replace the drawn shape / pattern / icon emblem; every
-- player's club starts with crest 1 (emblem_json {"crest":1}) and picks another in its profile. Bots keep no crest: the
-- client gives them one from their colours.
UPDATE clubs SET emblem_json = '{"crest":1}' WHERE NOT is_bot AND (emblem_json IS NULL OR emblem_json NOT LIKE '%"crest"%');
