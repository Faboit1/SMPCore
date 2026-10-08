-- friends: friendships, requests, cached rank limits, history
-- Two directed rows per friendship; favourite, note and notice are the owner's own view of the friend.
CREATE TABLE IF NOT EXISTS friends (
    owner {uuid} NOT NULL,
    friend {uuid} NOT NULL,
    since {bigint} NOT NULL,
    favourite SMALLINT NOT NULL DEFAULT 0,
    note VARCHAR(64),
    notice SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (owner, friend)
){engine};
-- state: pending (both sides see it), shadow (only the sender sees it), closed (nobody sees it; deny memory).
CREATE TABLE IF NOT EXISTS friend_requests (
    sender {uuid} NOT NULL,
    target {uuid} NOT NULL,
    created {bigint} NOT NULL,
    state VARCHAR(8) NOT NULL,
    decided {bigint},
    PRIMARY KEY (sender, target)
){engine};
CREATE INDEX idx_friend_requests_target ON friend_requests (target, state);
-- The friend limit of a player's rank when last seen (0 = unknown, the default applies) and their rank label.
CREATE TABLE IF NOT EXISTS friend_profiles (
    uuid {uuid} NOT NULL PRIMARY KEY,
    friend_limit INTEGER NOT NULL DEFAULT 0,
    rank_label VARCHAR(32),
    updated {bigint} NOT NULL DEFAULT 0
){engine};
-- History: request, accept, mutual, deny, cancel, remove, staff_add, staff_remove, staff_clear.
CREATE TABLE IF NOT EXISTS friend_log (
    id {autoinc},
    ts {bigint} NOT NULL,
    player {uuid} NOT NULL,
    other {uuid} NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor VARCHAR(36)
){engine};
CREATE INDEX idx_friend_log_player ON friend_log (player, ts);
CREATE INDEX idx_friend_log_other ON friend_log (other, ts);
