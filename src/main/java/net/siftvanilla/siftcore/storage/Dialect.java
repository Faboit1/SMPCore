package net.siftvanilla.siftcore.storage;

/** The SQL flavours SiftCore supports. Migrations use tokens that each dialect expands. */
public enum Dialect {
    SQLITE,
    MYSQL;

    /** Expands the migration tokens ({autoinc}, {blob}, {bigint}, {text}, {uuid}, {now}) for this dialect. */
    public String expand(String sql) {
        return switch (this) {
            case SQLITE -> sql
                .replace("{autoinc}", "INTEGER PRIMARY KEY AUTOINCREMENT")
                .replace("{blob}", "BLOB")
                .replace("{bigint}", "INTEGER")
                .replace("{text}", "TEXT")
                .replace("{uuid}", "CHAR(36)")
                .replace("{engine}", "");
            case MYSQL -> sql
                .replace("{autoinc}", "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY")
                .replace("{blob}", "MEDIUMBLOB")
                .replace("{bigint}", "BIGINT")
                .replace("{text}", "TEXT")
                .replace("{uuid}", "CHAR(36)")
                .replace("{engine}", " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        };
    }

    /**
     * An upsert that adds {@code ?} to a numeric column, for a table whose primary key is {@code keyColumns}.
     * Parameters: the key columns in order, then the delta.
     */
    public String addUpsert(String table, String[] keyColumns, String valueColumn) {
        String keys = String.join(", ", keyColumns);
        String marks = "?, ".repeat(keyColumns.length);
        return switch (this) {
            case SQLITE -> "INSERT INTO " + table + " (" + keys + ", " + valueColumn + ") VALUES (" + marks + "?) "
                + "ON CONFLICT(" + keys + ") DO UPDATE SET " + valueColumn + " = " + valueColumn + " + excluded." + valueColumn;
            case MYSQL -> "INSERT INTO " + table + " (" + keys + ", " + valueColumn + ") VALUES (" + marks + "?) "
                + "ON DUPLICATE KEY UPDATE " + valueColumn + " = " + valueColumn + " + VALUES(" + valueColumn + ")";
        };
    }

    /**
     * An upsert that replaces the given value columns, for a table whose primary key is {@code keyColumns}.
     * Parameters: key columns then value columns, in order.
     */
    public String replaceUpsert(String table, String[] keyColumns, String[] valueColumns) {
        String keys = String.join(", ", keyColumns);
        String values = String.join(", ", valueColumns);
        String marks = "?, ".repeat(keyColumns.length + valueColumns.length);
        marks = marks.substring(0, marks.length() - 2);
        StringBuilder set = new StringBuilder();
        for (String column : valueColumns) {
            if (!set.isEmpty()) {
                set.append(", ");
            }
            set.append(column).append(" = ").append(this == SQLITE ? "excluded." + column : "VALUES(" + column + ")");
        }
        return switch (this) {
            case SQLITE -> "INSERT INTO " + table + " (" + keys + ", " + values + ") VALUES (" + marks + ") ON CONFLICT(" + keys + ") DO UPDATE SET " + set;
            case MYSQL -> "INSERT INTO " + table + " (" + keys + ", " + values + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE " + set;
        };
    }

    /** An insert that silently skips rows whose primary key already exists. */
    public String insertIgnore(String table, String[] columns) {
        String cols = String.join(", ", columns);
        String marks = "?, ".repeat(columns.length);
        marks = marks.substring(0, marks.length() - 2);
        return switch (this) {
            case SQLITE -> "INSERT OR IGNORE INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
            case MYSQL -> "INSERT IGNORE INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
        };
    }
}
