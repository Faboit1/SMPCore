-- orders: notices for owners who were offline
-- One row per owner, order and kind (delivered, complete, expired, cancelled, ending). Deliveries add up into the
-- same row; the row is shown and deleted when the owner joins. The money itself is always in the ledger.
CREATE TABLE IF NOT EXISTS order_notices (
    owner {uuid} NOT NULL,
    order_id {bigint} NOT NULL,
    kind VARCHAR(12) NOT NULL,
    units INTEGER NOT NULL DEFAULT 0,
    amount {bigint} NOT NULL DEFAULT 0,
    detail VARCHAR(64),
    created {bigint} NOT NULL,
    PRIMARY KEY (owner, order_id, kind)
){engine};
CREATE INDEX idx_order_notices_owner ON order_notices (owner, created);
