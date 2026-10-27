-- boosters: server-wide sell boosters
-- /sift store booster (a store purchase, recorded in store_deliveries too) and /sift booster start add a booster.
-- Boosters run one at a time in id order and never stack. state: queued (waiting its turn), active (running), ended (ran
-- its whole length), stopped (ended or taken out of line early by staff), revoked (taken back by a store refund).
-- seconds: the whole length. remaining: milliseconds still to run; it counts down only while the server runs and is
-- stored every minute and at shutdown, so a restart resumes it. owner: the buyer or the staff member who started it
-- (null for the server itself). source: store or staff. ref: the store reference. started and ended: epoch millis, 0
-- until then.
CREATE TABLE IF NOT EXISTS boosters (
    id {bigint} NOT NULL PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    percent INTEGER NOT NULL,
    seconds {bigint} NOT NULL,
    remaining {bigint} NOT NULL,
    state VARCHAR(8) NOT NULL,
    owner {uuid},
    source VARCHAR(8) NOT NULL,
    ref VARCHAR(64),
    reason VARCHAR(128),
    actor VARCHAR(36),
    created {bigint} NOT NULL,
    started {bigint} NOT NULL,
    ended {bigint} NOT NULL
){engine};
CREATE INDEX idx_boosters_state ON boosters (state);
