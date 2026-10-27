-- teams and members
CREATE TABLE IF NOT EXISTS teams (
    id {bigint} NOT NULL PRIMARY KEY,
    name VARCHAR(16) NOT NULL,
    name_lower VARCHAR(16) NOT NULL,
    owner {uuid} NOT NULL,
    created {bigint} NOT NULL,
    friendly_fire SMALLINT NOT NULL DEFAULT 0,
    home_world VARCHAR(64),
    home_x DOUBLE,
    home_y DOUBLE,
    home_z DOUBLE,
    home_yaw DOUBLE,
    home_pitch DOUBLE
){engine};
CREATE UNIQUE INDEX idx_teams_name ON teams (name_lower);

-- A player is in at most one team.
CREATE TABLE IF NOT EXISTS team_members (
    uuid {uuid} NOT NULL PRIMARY KEY,
    team_id {bigint} NOT NULL,
    role VARCHAR(12) NOT NULL,
    joined {bigint} NOT NULL
){engine};
CREATE INDEX idx_team_members_team ON team_members (team_id);
