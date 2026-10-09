package net.siftvanilla.siftcore.feature.friends;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * How the friends list is ordered and what each row says, as pure functions over rows the viewer's thread built.
 * The order is the viewer's {@code friends-list-order} setting ({@link Sort}); the default ({@link Sort#STATUS}) is
 * online favourites by name, online others by name, offline favourites by last seen (most recent first), offline
 * others by last seen. AFK friends count as online. A row whose last-seen time the viewer may not see has 0 there
 * ({@link #hideSeen}). Pure; no Bukkit.
 */
public final class ListOrder {

    /** What a friend looks like to the viewer right now. */
    public enum Status {
        /** Online and visible. */
        ONLINE,
        /** Online, visible and away from keyboard. */
        AFK,
        /** Offline, or online but hidden from the viewer (vanished, or the viewer can't see them). */
        OFFLINE;

        public boolean online() {
            return this != OFFLINE;
        }
    }

    /**
     * One friend as the viewer sees them.
     *
     * @param id        the friend
     * @param name      their last known name
     * @param favourite whether the viewer marked them as a favourite
     * @param status    online, AFK or offline (as the viewer sees it)
     * @param lastSeen  when they were last seen, from the player directory (the same source as {@code /seen})
     * @param since     when the friendship started
     */
    public record Row(UUID id, String name, boolean favourite, Status status, long lastSeen, long since) {
    }

    /** The default list order described above. */
    public static final Comparator<Row> ORDER = Comparator
        .comparingInt(ListOrder::group)
        .thenComparing((a, b) -> a.status().online()
            ? a.name().toLowerCase(Locale.ROOT).compareTo(b.name().toLowerCase(Locale.ROOT))
            : Long.compare(b.lastSeen(), a.lastSeen()))
        .thenComparing(row -> row.name().toLowerCase(Locale.ROOT))
        .thenComparing(Row::id);

    private static final Comparator<Row> BY_NAME = Comparator
        .comparing((Row row) -> row.name().toLowerCase(Locale.ROOT))
        .thenComparing(Row::id);

    /** Online first (by name), then the most recently online; unknown or hidden last-seen times (0) last. */
    private static final Comparator<Row> RECENT = Comparator
        .comparingInt((Row row) -> row.status().online() ? 0 : 1)
        .thenComparing((a, b) -> a.status().online() ? 0 : Long.compare(b.lastSeen(), a.lastSeen()))
        .thenComparing(BY_NAME);

    private static final Comparator<Row> OLDEST_FIRST = Comparator
        .comparingLong(Row::since)
        .thenComparing(BY_NAME);

    /** The orders a player can pick for their friends list ({@code friends-list-order}). */
    public enum Sort {
        /** Favourites and online friends first (the default). */
        STATUS("status"),
        /** By name, A to Z. */
        NAME("name"),
        /** Online friends first, then the most recently online. */
        LAST_SEEN("last-seen"),
        /** The longest friendships first. */
        OLDEST("oldest");

        private final String id;

        Sort(String id) {
            this.id = id;
        }

        /** The stored id. */
        public String id() {
            return this.id;
        }

        /** How this order compares two rows (every order ends on the name, then the id, so it is total). */
        public Comparator<Row> comparator() {
            return switch (this) {
                case STATUS -> ORDER;
                case NAME -> BY_NAME;
                case LAST_SEEN -> RECENT;
                case OLDEST -> OLDEST_FIRST;
            };
        }
    }

    private ListOrder() {
    }

    private static int group(Row row) {
        if (row.status().online()) {
            return row.favourite() ? 0 : 1;
        }
        return row.favourite() ? 2 : 3;
    }

    /** The rows in the default list order. */
    public static List<Row> sort(List<Row> rows) {
        return sort(rows, Sort.STATUS);
    }

    /** The rows in the order the viewer picked. */
    public static List<Row> sort(List<Row> rows, Sort sort) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort((sort == null ? Sort.STATUS : sort).comparator());
        return sorted;
    }

    /**
     * The rows with the last-seen time of {@code hidden} friends taken out (0), so neither the row text nor the order
     * gives it away. Online rows are left alone: being online shows in the tab list anyway.
     */
    public static List<Row> hideSeen(List<Row> rows, Set<UUID> hidden) {
        if (hidden.isEmpty()) {
            return rows;
        }
        List<Row> result = new ArrayList<>(rows.size());
        for (Row row : rows) {
            result.add(!row.status().online() && hidden.contains(row.id())
                ? new Row(row.id(), row.name(), row.favourite(), row.status(), 0, row.since()) : row);
        }
        return result;
    }

    /** Rows whose name starts with {@code prefix}, ignoring case; all rows for an empty prefix. */
    public static List<Row> filter(List<Row> rows, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return rows;
        }
        String lower = prefix.strip().toLowerCase(Locale.ROOT);
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if (row.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(row);
            }
        }
        return result;
    }

    /** Number of pages for {@code size} rows ({@code pageSize} per page), at least 1. */
    public static int pages(int size, int pageSize) {
        return Math.max(1, (size + pageSize - 1) / pageSize);
    }

    /** The rows of one page (1-based, clamped to the valid range). */
    public static <T> List<T> page(List<T> rows, int page, int pageSize) {
        int pages = pages(rows.size(), pageSize);
        int current = Math.clamp(page, 1, pages);
        int from = Math.min(rows.size(), (current - 1) * pageSize);
        int to = Math.min(rows.size(), current * pageSize);
        return rows.subList(from, to);
    }

    /** How many rows are online. */
    public static int online(List<Row> rows) {
        int count = 0;
        for (Row row : rows) {
            if (row.status().online()) {
                count++;
            }
        }
        return count;
    }
}
