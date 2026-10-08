package net.siftvanilla.siftcore.feature.friends;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * How the friends list is ordered and what each row says, as pure functions over rows the viewer's thread built.
 * Order: online favourites by name, online others by name, offline favourites by last seen (most recent first),
 * offline others by last seen. AFK friends count as online. Pure; no Bukkit.
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

    /** The list order described above. */
    public static final Comparator<Row> ORDER = Comparator
        .comparingInt(ListOrder::group)
        .thenComparing((a, b) -> a.status().online()
            ? a.name().toLowerCase(Locale.ROOT).compareTo(b.name().toLowerCase(Locale.ROOT))
            : Long.compare(b.lastSeen(), a.lastSeen()))
        .thenComparing(row -> row.name().toLowerCase(Locale.ROOT))
        .thenComparing(Row::id);

    private ListOrder() {
    }

    private static int group(Row row) {
        if (row.status().online()) {
            return row.favourite() ? 0 : 1;
        }
        return row.favourite() ? 2 : 3;
    }

    /** The rows in list order. */
    public static List<Row> sort(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(ORDER);
        return sorted;
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
