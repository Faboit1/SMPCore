-- shards: crate keys bought in the shard shop
-- The shards are taken and the row is written (state pending) in one transaction; the keys are given afterwards with
-- the row's ref, which the crates feature refuses to give twice. The row then becomes done, or refunded when the keys
-- could not be given (a compensating shard_refund in the ledger). Rows still pending at startup are resumed.
CREATE TABLE IF NOT EXISTS shard_purchases (
    ref VARCHAR(64) NOT NULL PRIMARY KEY,
    uuid {uuid} NOT NULL,
    offer VARCHAR(32) NOT NULL,
    crate VARCHAR(32) NOT NULL,
    key_count INTEGER NOT NULL,
    cost {bigint} NOT NULL,
    created {bigint} NOT NULL,
    state VARCHAR(12) NOT NULL,
    closed {bigint}
){engine};
CREATE INDEX idx_shard_purchases_state ON shard_purchases (state);
CREATE INDEX idx_shard_purchases_uuid ON shard_purchases (uuid, created);
