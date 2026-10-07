CREATE TABLE IF NOT EXISTS homes (
    uuid {uuid} NOT NULL,
    name VARCHAR(32) NOT NULL,
    world VARCHAR(64) NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    z DOUBLE NOT NULL,
    yaw DOUBLE NOT NULL,
    pitch DOUBLE NOT NULL,
    created {bigint} NOT NULL,
    PRIMARY KEY (uuid, name)
){engine};
