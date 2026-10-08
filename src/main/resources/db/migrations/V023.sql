-- orders: expiry warnings
-- 1 once the owner was told the order ends soon (reset when the order is extended).
ALTER TABLE orders ADD COLUMN warned INTEGER NOT NULL DEFAULT 0;
