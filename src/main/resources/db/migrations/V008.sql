-- rewards: crate keys, crate log, kit claims
CREATE TABLE IF NOT EXISTS crate_keys (
    uuid {uuid} NOT NULL,
    crate VARCHAR(32) NOT NULL,
    amount INTEGER NOT NULL,
    PRIMARY KEY (uuid, crate)
){engine};

CREATE TABLE IF NOT EXISTS crate_log (
    id {autoinc},
    ts {bigint} NOT NULL,
    uuid {uuid} NOT NULL,
    crate VARCHAR(32) NOT NULL,
    reward VARCHAR(64) NOT NULL,
    detail VARCHAR(255)
){engine};
CREATE INDEX idx_crate_log_uuid ON crate_log (uuid, ts);

CREATE TABLE IF NOT EXISTS kit_claims (
    uuid {uuid} NOT NULL,
    kit VARCHAR(32) NOT NULL,
    last_claim {bigint} NOT NULL,
    PRIMARY KEY (uuid, kit)
){engine};
