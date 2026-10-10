-- staff: punishments, reports, vanish and freeze
-- One row per ban, mute, kick and warning. expires is NULL for permanent bans and mutes and for kicks and
-- warnings; revoked is set when staff lift a ban or mute early. notified is 0 for warnings the player has not
-- seen yet (given while they were offline).
CREATE TABLE IF NOT EXISTS staff_punishments (
    id {bigint} NOT NULL PRIMARY KEY,
    type VARCHAR(8) NOT NULL,
    target {uuid} NOT NULL,
    target_name VARCHAR(16) NOT NULL,
    staff VARCHAR(36) NOT NULL,
    staff_name VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created {bigint} NOT NULL,
    expires {bigint},
    revoked {bigint},
    revoked_by VARCHAR(32),
    notified INTEGER NOT NULL DEFAULT 1
){engine};
CREATE INDEX idx_staff_punishments_target ON staff_punishments (target, created);
CREATE INDEX idx_staff_punishments_active ON staff_punishments (type, revoked);

-- Player reports. state is OPEN, HANDLED or DISMISSED.
CREATE TABLE IF NOT EXISTS staff_reports (
    id {bigint} NOT NULL PRIMARY KEY,
    reporter {uuid} NOT NULL,
    reporter_name VARCHAR(16) NOT NULL,
    target {uuid} NOT NULL,
    target_name VARCHAR(16) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created {bigint} NOT NULL,
    state VARCHAR(10) NOT NULL,
    closed_by VARCHAR(32),
    closed_at {bigint}
){engine};
CREATE INDEX idx_staff_reports_state ON staff_reports (state, created);

-- Staff who are vanished; the row stays while they are offline so they come back vanished.
CREATE TABLE IF NOT EXISTS staff_vanish (
    uuid {uuid} NOT NULL PRIMARY KEY,
    since {bigint} NOT NULL
){engine};

-- Frozen players; the row stays while they are offline so they come back frozen.
CREATE TABLE IF NOT EXISTS staff_freeze (
    uuid {uuid} NOT NULL PRIMARY KEY,
    staff VARCHAR(36) NOT NULL,
    staff_name VARCHAR(32) NOT NULL,
    since {bigint} NOT NULL
){engine};
