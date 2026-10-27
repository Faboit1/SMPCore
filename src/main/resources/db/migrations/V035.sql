-- teams: remembered member limit of the owner's rank
-- The member limit comes from the owner's rank (siftcore.teams.size.<n>). It is stored so it still applies while
-- the owner is offline, for example after a restart. 0 means none known (the configured default applies).
ALTER TABLE teams ADD COLUMN size_limit INTEGER NOT NULL DEFAULT 0;
