CREATE TABLE IF NOT EXISTS auction_listings (
    id {bigint} NOT NULL PRIMARY KEY,
    seller {uuid} NOT NULL,
    item {blob} NOT NULL,
    item_type VARCHAR(96) NOT NULL,
    search_name VARCHAR(160) NOT NULL,
    category VARCHAR(32) NOT NULL,
    amount INTEGER NOT NULL,
    price {bigint} NOT NULL,
    created {bigint} NOT NULL,
    expires {bigint} NOT NULL,
    state VARCHAR(12) NOT NULL,
    buyer {uuid},
    closed_at {bigint},
    tax {bigint} NOT NULL DEFAULT 0
){engine};
CREATE INDEX idx_ah_state ON auction_listings (state, expires);
CREATE INDEX idx_ah_seller ON auction_listings (seller, state);
CREATE INDEX idx_ah_buyer ON auction_listings (buyer, closed_at);
