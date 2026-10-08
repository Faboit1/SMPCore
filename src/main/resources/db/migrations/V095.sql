-- displays: positions of leaderboards and info boards placed in-game
-- template is null for a display defined in features/displays.yml that was moved in-game (its template comes from
-- the file); it is set for displays created in-game with /displays create.
CREATE TABLE IF NOT EXISTS displays (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    template VARCHAR(32),
    world VARCHAR(64) NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    z DOUBLE NOT NULL,
    yaw DOUBLE NOT NULL,
    placed_by VARCHAR(36),
    placed_at {bigint} NOT NULL
){engine};
