-- store: delivered store purchases, one row per reference
-- /sift store money|shards|keys|rank records the store's reference (order or transaction id) here. Money and shards
-- insert the row in the same transaction as the ledger rows, so a reference is paid at most once. Keys are granted
-- with the crate grant reference store:<ref> (crate_grants, V055) and recorded here after that commits. A rank is
-- recorded as pending before LuckPerms is changed and marked done afterwards; a pending rank is finished on the next
-- start or the next delivery with the same reference.
-- /sift store revoke (refunds, chargebacks) takes a delivery back and keeps the row as revoked, so the reference can't
-- be delivered again. Revoking a rank is recorded as revoking with its plan (revoke_until) before LuckPerms is changed,
-- like a delivery, and finished the same way.
-- kind: money, shards, keys, rank. item: the currency id, crate id or LuckPerms group. amount: money, shards or keys
-- (0 for ranks). duration: a rank's length in seconds (0 permanent). until_ts: a rank's end in epoch seconds
-- (0 permanent). state: done, pending, revoking or revoked. revoke_until: for a rank being revoked, -1 leaves timed
-- grants alone, 0 removes them, more cuts them to that epoch second. note: why it was revoked and what was taken.
CREATE TABLE IF NOT EXISTS store_deliveries (
    ref VARCHAR(64) NOT NULL PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    uuid {uuid} NOT NULL,
    item VARCHAR(64) NOT NULL,
    amount {bigint} NOT NULL,
    duration {bigint} NOT NULL,
    state VARCHAR(8) NOT NULL,
    actor VARCHAR(36),
    ts {bigint} NOT NULL,
    until_ts {bigint} NOT NULL,
    revoke_until {bigint} NOT NULL,
    note VARCHAR(128)
){engine};
CREATE INDEX idx_store_deliveries_uuid ON store_deliveries (uuid, ts);
