-- spawners: stacked spawners and stored drops
CREATE TABLE IF NOT EXISTS spawners (
    id {bigint} NOT NULL PRIMARY KEY,
    world VARCHAR(64) NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    owner {uuid} NOT NULL,
    mob VARCHAR(48) NOT NULL,
    stack INTEGER NOT NULL,
    xp {bigint} NOT NULL DEFAULT 0,
    created {bigint} NOT NULL
){engine};
CREATE UNIQUE INDEX idx_spawners_pos ON spawners (world, x, y, z);
CREATE INDEX idx_spawners_owner ON spawners (owner);

CREATE TABLE IF NOT EXISTS spawner_items (
    spawner_id {bigint} NOT NULL,
    item_type VARCHAR(96) NOT NULL,
    amount {bigint} NOT NULL,
    PRIMARY KEY (spawner_id, item_type)
){engine};
