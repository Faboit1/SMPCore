package net.siftvanilla.siftcore.feature.stats;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/** The leaderboards. Every board except {@link #MONEY} is ranked from the {@code stats} table. */
public enum Board {
    KILLS("kills", "kills", Format.NUMBER),
    DEATHS("deaths", "deaths", Format.NUMBER),
    /** Kills per death; only players with at least the configured number of kills are ranked. */
    KDR("kdr", null, Format.KDR),
    /** The best streak ever (the current streak changes too often to rank). */
    STREAK("streak", "best_streak", Format.NUMBER),
    PLAYTIME("playtime", "playtime_seconds", Format.DURATION),
    MOBS("mobs", "mobs_killed", Format.NUMBER),
    BLOCKS("blocks", "blocks_mined", Format.NUMBER),
    EARNED("earned", "money_earned", Format.MONEY),
    /** Balances, taken from the economy's own leaderboard. */
    MONEY("money", null, Format.MONEY);

    /** How a board's values are shown. */
    public enum Format {
        NUMBER,
        KDR,
        DURATION,
        MONEY
    }

    private final String id;
    private final String column;
    private final Format format;

    Board(String id, String column, Format format) {
        this.id = id;
        this.column = column;
        this.format = format;
    }

    /** Stable lowercase id used in /top and placeholders. */
    public String id() {
        return this.id;
    }

    /** The {@code stats} column ranked directly, or null for {@link #KDR} (computed) and {@link #MONEY}. */
    public String column() {
        return this.column;
    }

    public Format format() {
        return this.format;
    }

    /** Whether the board is built from the {@code stats} table. */
    public boolean fromStats() {
        return this != MONEY;
    }

    /**
     * Orders rows best first by the ranked value only. Rows that compare equal share a rank. For {@link #KDR} the
     * value is kills and the secondary value deaths, compared as an exact ratio.
     */
    public Comparator<Leaderboard.Row> rankOrder() {
        if (this == KDR) {
            return (a, b) -> Kdr.compare(b.value(), b.secondary(), a.value(), a.secondary());
        }
        return (a, b) -> Long.compare(b.value(), a.value());
    }

    /** The display order: rank order, then more kills first (KDR), then by UUID text so the order is stable. */
    public Comparator<Leaderboard.Row> displayOrder() {
        Comparator<Leaderboard.Row> order = rankOrder();
        if (this == KDR) {
            order = order.thenComparing((a, b) -> Long.compare(b.value(), a.value()));
        }
        return order.thenComparing(row -> row.uuid().toString());
    }

    public static Optional<Board> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (Board board : values()) {
            if (board.id.equals(lower)) {
                return Optional.of(board);
            }
        }
        return Optional.empty();
    }
}
