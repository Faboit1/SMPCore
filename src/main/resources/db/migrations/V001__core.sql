-- Players, currency accounts, the append-only ledger, per-player settings, the claim box and the audit log.
CREATE TABLE IF NOT EXISTS players (
    uuid {uuid} NOT NULL PRIMARY KEY,
    name VARCHAR(16) NOT NULL,
    name_lower VARCHAR(16) NOT NULL,
    first_join {bigint} NOT NULL,
    last_seen {bigint} NOT NULL,
    ip_hash VARCHAR(64)
){engine};
CREATE INDEX idx_players_name ON players (name_lower);
CREATE INDEX idx_players_ip ON players (ip_hash);

-- One row per (account, currency). System accounts (escrow) use fixed UUIDs.
CREATE TABLE IF NOT EXISTS accounts (
    uuid {uuid} NOT NULL,
    currency VARCHAR(16) NOT NULL,
    balance {bigint} NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, currency)
){engine};
CREATE INDEX idx_accounts_top ON accounts (currency, balance);

-- Append-only. Every balance change is one row; rows of one transaction share tx_id.
-- flow is SOURCE (money created), SINK (money destroyed) or TRANSFER (moved between accounts, nets to zero per tx).
CREATE TABLE IF NOT EXISTS ledger (
    id {autoinc},
    tx_id VARCHAR(36) NOT NULL,
    ts {bigint} NOT NULL,
    currency VARCHAR(16) NOT NULL,
    account {uuid} NOT NULL,
    delta {bigint} NOT NULL,
    balance_after {bigint} NOT NULL,
    kind VARCHAR(32) NOT NULL,
    flow VARCHAR(8) NOT NULL,
    counterparty {uuid},
    ref VARCHAR(64),
    actor VARCHAR(36),
    note VARCHAR(255)
){engine};
CREATE INDEX idx_ledger_account ON ledger (account, ts);
CREATE INDEX idx_ledger_tx ON ledger (tx_id);
CREATE INDEX idx_ledger_kind ON ledger (kind, ts);

CREATE TABLE IF NOT EXISTS settings (
    uuid {uuid} NOT NULL,
    setting VARCHAR(32) NOT NULL,
    value VARCHAR(64) NOT NULL,
    PRIMARY KEY (uuid, setting)
){engine};

-- The claim box: items owed to a player (auction purchases, expired listings, crate rewards, overflow).
-- An item is removed from here (claimed) before it is put in an inventory.
CREATE TABLE IF NOT EXISTS deliveries (
    id {bigint} NOT NULL PRIMARY KEY,
    owner {uuid} NOT NULL,
    source VARCHAR(32) NOT NULL,
    ref VARCHAR(64),
    item {blob} NOT NULL,
    created {bigint} NOT NULL,
    claimed {bigint}
){engine};
CREATE INDEX idx_deliveries_owner ON deliveries (owner, claimed);

CREATE TABLE IF NOT EXISTS audit_log (
    id {autoinc},
    ts {bigint} NOT NULL,
    actor VARCHAR(36),
    action VARCHAR(48) NOT NULL,
    target VARCHAR(64),
    details VARCHAR(1024)
){engine};
CREATE INDEX idx_audit_ts ON audit_log (ts);
CREATE INDEX idx_audit_action ON audit_log (action, ts);
