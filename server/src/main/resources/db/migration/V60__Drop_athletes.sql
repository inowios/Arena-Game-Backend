-- The athletes («вихованці академії»), their trainings and the eleven-a-side lineups went with the Arena league matches
-- (APP-AUDIT §2.15, 20 October 2026): every fixture is an Arena v4 match of the clubs' hero triples, played by the server.
-- Old fixtures keep their result_json (read as a result without heroes); nothing reads these tables any more.
DROP TABLE IF EXISTS trainings;
DROP TABLE IF EXISTS lineups;
DROP TABLE IF EXISTS athletes;
