-- orders: history columns and the fill source
-- ended is when an order stopped taking deliveries (complete, cancelled or expired), refunded what came back to the
-- owner when it ended early, and order_fills.source how the items were delivered (menu, quick or sell).
ALTER TABLE orders ADD COLUMN ended {bigint};
ALTER TABLE orders ADD COLUMN refunded {bigint} NOT NULL DEFAULT 0;
ALTER TABLE order_fills ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'menu';
UPDATE orders SET ended = expires WHERE state = 'EXPIRED';
CREATE INDEX idx_orders_owner_ended ON orders (owner, ended);
