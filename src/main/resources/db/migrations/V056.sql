-- crates: command rewards waiting to run
-- The console commands of a won reward are stored in the opening's transaction (with the spent key and the log row),
-- run from the console once that is stored, and deleted after they ran. Rows still here at startup (the server stopped
-- or crashed before they ran) are run then, and each one is logged.
CREATE TABLE IF NOT EXISTS crate_commands (
    ref VARCHAR(64) NOT NULL,
    seq INTEGER NOT NULL,
    uuid {uuid} NOT NULL,
    command {text} NOT NULL,
    created {bigint} NOT NULL,
    PRIMARY KEY (ref, seq)
){engine};
