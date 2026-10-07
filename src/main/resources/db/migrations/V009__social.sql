CREATE TABLE IF NOT EXISTS ignores (
    uuid {uuid} NOT NULL,
    ignored {uuid} NOT NULL,
    PRIMARY KEY (uuid, ignored)
){engine};
