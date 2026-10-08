-- crates: applied grant references, crate blocks placed in-game and the keyall schedule
-- crate_keys and crate_log are in V008. A key grant with a reference (store delivery, shard shop, keyall) stores the
-- reference here in the same transaction, so a repeated grant is refused. References older than grants.remember
-- (features/crates.yml) are deleted.
CREATE TABLE IF NOT EXISTS crate_grants (
    ref VARCHAR(64) NOT NULL PRIMARY KEY,
    uuid {uuid} NOT NULL,
    crate VARCHAR(32) NOT NULL,
    amount INTEGER NOT NULL,
    actor VARCHAR(36),
    ts {bigint} NOT NULL
){engine};
CREATE INDEX idx_crate_grants_ts ON crate_grants (ts);

-- Blocks that act as a crate, placed with /crates block add (crates.yml can list more).
CREATE TABLE IF NOT EXISTS crate_blocks (
    world VARCHAR(64) NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    crate VARCHAR(32) NOT NULL,
    placed_by VARCHAR(36),
    placed_at {bigint} NOT NULL,
    PRIMARY KEY (world, x, y, z)
){engine};

-- When the next keyall runs, so restarts keep the schedule. One row per schedule (id 'keyall').
CREATE TABLE IF NOT EXISTS crate_schedule (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    next_run {bigint} NOT NULL,
    last_run {bigint} NOT NULL,
    runs INTEGER NOT NULL
){engine};
