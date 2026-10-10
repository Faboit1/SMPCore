package net.siftvanilla.siftcore.feature.stats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * An immutable snapshot of one leaderboard, best first. Ranks use standard competition ranking: players with equal
 * values share a rank and the next rank skips accordingly (1, 2, 2, 4). The order among equal players is stable
 * (by UUID). Lookups by player are a hash lookup, so placeholders can read it on any thread.
 */
public final class Leaderboard {

    /** A ranked value from storage. For {@link Board#KDR} {@code value} is kills and {@code secondary} deaths. */
    public record Row(UUID uuid, long value, long secondary) {
    }

    /** One place on the board. */
    public record Entry(int rank, UUID uuid, String name, long value, long secondary) {
    }

    private final Board board;
    private final List<Entry> entries;
    private final Map<UUID, Entry> byPlayer;
    private final long builtAt;

    private Leaderboard(Board board, List<Entry> entries, long builtAt) {
        this.board = board;
        this.entries = List.copyOf(entries);
        Map<UUID, Entry> index = new HashMap<>();
        for (Entry entry : this.entries) {
            index.put(entry.uuid(), entry);
        }
        this.byPlayer = Map.copyOf(index);
        this.builtAt = builtAt;
    }

    /** A board with nobody on it yet (before the first refresh). */
    public static Leaderboard empty(Board board) {
        return new Leaderboard(board, List.of(), 0L);
    }

    /**
     * Sorts the rows, keeps the best {@code size} (a player listed twice keeps their first row) and ranks them.
     * Rows with nothing to rank (a zero or negative value) are left out.
     */
    public static Leaderboard build(Board board, List<Row> rows, int size, Function<UUID, String> names, long builtAt) {
        List<Row> sorted = new ArrayList<>(rows.size());
        Set<UUID> seen = new HashSet<>();
        for (Row row : rows) {
            if (row.value() > 0 && seen.add(row.uuid())) {
                sorted.add(row);
            }
        }
        sorted.sort(board.displayOrder());
        int limit = Math.min(Math.max(0, size), sorted.size());
        List<Entry> entries = new ArrayList<>(limit);
        int rank = 0;
        for (int i = 0; i < limit; i++) {
            Row row = sorted.get(i);
            if (i == 0 || board.rankOrder().compare(sorted.get(i - 1), row) != 0) {
                rank = i + 1;
            }
            entries.add(new Entry(rank, row.uuid(), names.apply(row.uuid()), row.value(), row.secondary()));
        }
        return new Leaderboard(board, entries, builtAt);
    }

    public Board board() {
        return this.board;
    }

    public List<Entry> entries() {
        return this.entries;
    }

    public int size() {
        return this.entries.size();
    }

    /** When it was built (epoch millis), 0 for an empty placeholder board. */
    public long builtAt() {
        return this.builtAt;
    }

    /** The entry at a 1-based position. */
    public Optional<Entry> at(int position) {
        return position >= 1 && position <= this.entries.size() ? Optional.of(this.entries.get(position - 1)) : Optional.empty();
    }

    /** The player's rank, or 0 when they are not on the board. */
    public int rankOf(UUID player) {
        Entry entry = this.byPlayer.get(player);
        return entry == null ? 0 : entry.rank();
    }

    public Optional<Entry> entryOf(UUID player) {
        return Optional.ofNullable(this.byPlayer.get(player));
    }

    /** Number of pages of {@code pageSize} entries (at least one). */
    public int pages(int pageSize) {
        int size = Math.max(1, pageSize);
        return Math.max(1, (this.entries.size() + size - 1) / size);
    }

    /** The entries of a 1-based page; the page is clamped into range. */
    public List<Entry> page(int page, int pageSize) {
        int size = Math.max(1, pageSize);
        int current = Math.clamp(page, 1, pages(size));
        int from = Math.min(this.entries.size(), (current - 1) * size);
        int to = Math.min(this.entries.size(), from + size);
        return this.entries.subList(from, to);
    }
}
