-- cosmetics: chat colours, nicknames, chat tags, join and leave messages, kill effects
-- One row per player who chose something. Styles are stored as text ('gold', '#FFB07A', '#55FFFF:#5555FF').
CREATE TABLE IF NOT EXISTS player_cosmetics (
    uuid {uuid} NOT NULL PRIMARY KEY,
    chat_style VARCHAR(32),
    nick VARCHAR(16),
    nick_lower VARCHAR(16),
    nick_style VARCHAR(32),
    tag VARCHAR(32),
    owned_tags {text},
    join_message VARCHAR(64),
    leave_message VARCHAR(64),
    kill_effect VARCHAR(32),
    updated {bigint} NOT NULL
){engine};
CREATE INDEX idx_player_cosmetics_nick ON player_cosmetics (nick_lower);
