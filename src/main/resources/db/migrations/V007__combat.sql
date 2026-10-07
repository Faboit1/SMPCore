-- Lifetime counters (write-behind from memory) and the kill log used by anti-farm rules.
CREATE TABLE IF NOT EXISTS stats (
    uuid {uuid} NOT NULL PRIMARY KEY,
    kills INTEGER NOT NULL DEFAULT 0,
    deaths INTEGER NOT NULL DEFAULT 0,
    streak INTEGER NOT NULL DEFAULT 0,
    best_streak INTEGER NOT NULL DEFAULT 0,
    mobs_killed {bigint} NOT NULL DEFAULT 0,
    blocks_mined {bigint} NOT NULL DEFAULT 0,
    money_earned {bigint} NOT NULL DEFAULT 0,
    playtime_seconds {bigint} NOT NULL DEFAULT 0
){engine};
CREATE INDEX idx_stats_kills ON stats (kills);
CREATE INDEX idx_stats_deaths ON stats (deaths);
CREATE INDEX idx_stats_best_streak ON stats (best_streak);
CREATE INDEX idx_stats_mobs ON stats (mobs_killed);
CREATE INDEX idx_stats_blocks ON stats (blocks_mined);
CREATE INDEX idx_stats_earned ON stats (money_earned);
CREATE INDEX idx_stats_playtime ON stats (playtime_seconds);

CREATE TABLE IF NOT EXISTS kills (
    id {autoinc},
    killer {uuid} NOT NULL,
    victim {uuid} NOT NULL,
    ts {bigint} NOT NULL,
    counted SMALLINT NOT NULL,
    reason VARCHAR(32)
){engine};
CREATE INDEX idx_kills_pair ON kills (killer, victim, ts);

-- Each contribution to a bounty is a row; the money sits in the bounty escrow account until claimed or refunded.
CREATE TABLE IF NOT EXISTS bounties (
    id {bigint} NOT NULL PRIMARY KEY,
    target {uuid} NOT NULL,
    sponsor {uuid} NOT NULL,
    amount {bigint} NOT NULL,
    created {bigint} NOT NULL,
    state VARCHAR(12) NOT NULL,
    claimed_by {uuid},
    closed_at {bigint}
){engine};
CREATE INDEX idx_bounties_target ON bounties (target, state);
