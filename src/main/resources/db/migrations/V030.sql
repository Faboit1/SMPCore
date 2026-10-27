-- spawners: XP waiting for its player
-- Stored spawner XP that left a spawner (collected, picked up, refunded) moves here in the same transaction, so it is
-- stored with that change. It is then paid out on the player's thread: right away when they are online, otherwise
-- when they next join. Rows change by deltas, so a failed transaction rolls back exactly its own part.
CREATE TABLE IF NOT EXISTS spawner_xp (
    uuid {uuid} NOT NULL PRIMARY KEY,
    xp {bigint} NOT NULL
){engine};
