-- cosmetics: nickname holds (when a holder was last able to show their nickname, and a nickname they lost)
-- A nickname blocks other players while its holder can show it and for nicknames.hold after that. Existing nicknames
-- count from their row's last change.
ALTER TABLE player_cosmetics ADD COLUMN nick_seen {bigint} NOT NULL DEFAULT 0;
ALTER TABLE player_cosmetics ADD COLUMN nick_lost VARCHAR(16);
UPDATE player_cosmetics SET nick_seen = updated WHERE nick IS NOT NULL;
