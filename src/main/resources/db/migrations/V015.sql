-- sell: mastery, the base value each player sold per sell category
-- sold only ever grows (each sale adds to it inside the sale's transaction); updated is when it last changed.
-- Rows of categories that were renamed or removed in features/sell.yml stay and are simply not used.
CREATE TABLE IF NOT EXISTS sell_mastery (
    uuid {uuid} NOT NULL,
    category VARCHAR(32) NOT NULL,
    sold {bigint} NOT NULL DEFAULT 0,
    updated {bigint} NOT NULL,
    PRIMARY KEY (uuid, category)
){engine};
CREATE INDEX idx_sell_mastery_sold ON sell_mastery (category, sold);
