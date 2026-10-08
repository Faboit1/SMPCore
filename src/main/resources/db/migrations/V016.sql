-- shop: each player's most recent purchases, for the "Buy again" row of the shop
-- One row per (player, entry): the amount last bought and when. The shop shows the newest few.
CREATE TABLE IF NOT EXISTS shop_recent (
    uuid {uuid} NOT NULL,
    ref VARCHAR(64) NOT NULL,
    amount INTEGER NOT NULL,
    ts {bigint} NOT NULL,
    PRIMARY KEY (uuid, ref)
){engine};
CREATE INDEX idx_shop_recent_ts ON shop_recent (uuid, ts);
