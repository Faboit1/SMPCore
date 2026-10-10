-- orders: buy orders and their fills
-- Money for unfilled units sits in the orders escrow account; delivered items are counted in
-- filled - collected and handed out as plain stacks of item_type.
CREATE TABLE IF NOT EXISTS orders (
    id {bigint} NOT NULL PRIMARY KEY,
    owner {uuid} NOT NULL,
    item_type VARCHAR(96) NOT NULL,
    quantity INTEGER NOT NULL,
    filled INTEGER NOT NULL DEFAULT 0,
    collected INTEGER NOT NULL DEFAULT 0,
    price_each {bigint} NOT NULL,
    escrow {bigint} NOT NULL,
    created {bigint} NOT NULL,
    expires {bigint} NOT NULL,
    state VARCHAR(12) NOT NULL
){engine};
CREATE INDEX idx_orders_state ON orders (state, item_type);
CREATE INDEX idx_orders_owner ON orders (owner, state);

CREATE TABLE IF NOT EXISTS order_fills (
    id {autoinc},
    order_id {bigint} NOT NULL,
    seller {uuid} NOT NULL,
    quantity INTEGER NOT NULL,
    paid {bigint} NOT NULL,
    tax {bigint} NOT NULL,
    ts {bigint} NOT NULL
){engine};
CREATE INDEX idx_fills_order ON order_fills (order_id);
CREATE INDEX idx_fills_seller ON order_fills (seller, ts);
