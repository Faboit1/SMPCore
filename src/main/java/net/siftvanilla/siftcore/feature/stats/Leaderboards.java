package net.siftvanilla.siftcore.feature.stats;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.EconomyApi;

/**
 * Cached leaderboards. A refresh (on an async timer) first saves pending stats, reads who hides from the boards
 * ({@link HiddenPlayers}), then runs one query per board and swaps in an immutable snapshot, so readers (dialogs,
 * placeholders, holograms) never touch the database. The money board is taken from the economy's own leaderboard.
 * Only players who have joined the server and don't hide are listed; extra rows are fetched so a board still fills
 * up when some rows belong to anything else or to hidden players. Only one refresh runs at a time. After a swap the
 * {@link Swapped} listener sees the old and the new boards (for climb alerts).
 */
final class Leaderboards {

    /** Rows fetched beyond the board size, to make up for rows that are not players. */
    static final int EXTRA_ROWS = 25;
    /** At most this many more rows are fetched for hidden players. */
    static final int MAX_HIDDEN_ROWS = 1_000;

    /** Told after new boards replaced the old ones (on a database callback thread). */
    @FunctionalInterface
    interface Swapped {
        void swapped(Map<Board, Leaderboard> before, Map<Board, Leaderboard> after);
    }

    /** What one refresh read: the stored rows and who is hidden. */
    private record Fetched(Map<Board, List<Leaderboard.Row>> rows, HiddenPlayers.Hidden hidden) {
    }

    private final StatsStore store;
    private final StatsStorage storage;
    private final IntFunction<List<EconomyApi.TopEntry>> moneyTop;
    private final Function<UUID, String> names;
    private final Predicate<UUID> listed;
    private final IntSupplier size;
    private final LongSupplier kdrMinKills;
    private final Logger logger;
    private final LongSupplier clock;
    private final Supplier<CompletableFuture<HiddenPlayers.Hidden>> hidden;
    private volatile Swapped swapped = (before, after) -> { };
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong failuresInARow = new AtomicLong();
    private volatile Map<Board, Leaderboard> boards = emptyBoards();
    private volatile long refreshedAt;

    /** Boards that hide nobody (tests). */
    Leaderboards(StatsStore store, StatsStorage storage, IntFunction<List<EconomyApi.TopEntry>> moneyTop,
                 Function<UUID, String> names, Predicate<UUID> listed, IntSupplier size, LongSupplier kdrMinKills,
                 Logger logger, LongSupplier clock) {
        this(store, storage, moneyTop, names, listed, size, kdrMinKills, logger, clock,
            () -> CompletableFuture.completedFuture(HiddenPlayers.Hidden.NONE));
    }

    /**
     * @param listed whether an account may appear on a board (a player who joined); others are left out
     * @param hidden who hides from the boards, read at every refresh; a failed read keeps the previous boards
     */
    Leaderboards(StatsStore store, StatsStorage storage, IntFunction<List<EconomyApi.TopEntry>> moneyTop,
                 Function<UUID, String> names, Predicate<UUID> listed, IntSupplier size, LongSupplier kdrMinKills,
                 Logger logger, LongSupplier clock, Supplier<CompletableFuture<HiddenPlayers.Hidden>> hidden) {
        this.store = store;
        this.storage = storage;
        this.moneyTop = moneyTop;
        this.names = names;
        this.listed = listed;
        this.size = size;
        this.kdrMinKills = kdrMinKills;
        this.logger = logger;
        this.clock = clock;
        this.hidden = hidden;
    }

    private static Map<Board, Leaderboard> emptyBoards() {
        Map<Board, Leaderboard> empty = new EnumMap<>(Board.class);
        for (Board board : Board.values()) {
            empty.put(board, Leaderboard.empty(board));
        }
        return Map.copyOf(empty);
    }

    /** Sets who is told about each swap of the boards. */
    void onSwap(Swapped listener) {
        this.swapped = listener;
    }

    /** The rows fetched per board: the board size, the extra rows, and one more per hidden player (capped). */
    static int fetchSize(int limit, HiddenPlayers.Hidden hidden) {
        return limit + EXTRA_ROWS + Math.min(Math.max(0, hidden.count()), MAX_HIDDEN_ROWS);
    }

    /**
     * Saves pending stats, rebuilds every board and swaps them in. Completes with true when the boards were
     * rebuilt, false when another refresh was already running or the queries failed (the old boards stay).
     */
    CompletableFuture<Boolean> refresh() {
        if (!this.running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false);
        }
        int limit = this.size.getAsInt();
        long minKills = this.kdrMinKills.getAsLong();
        CompletableFuture<Fetched> rows;
        try {
            // A failed save is retried later; the boards are built from what is stored.
            rows = this.store.save().handle((ignored, error) -> null)
                .thenCompose(ignored -> this.hidden.get())
                .thenCompose(hiding -> this.storage.top(fetchSize(limit, hiding), minKills).thenApply(top -> new Fetched(top, hiding)));
        } catch (RuntimeException e) {
            rows = CompletableFuture.failedFuture(e);
        }
        return rows.handle((fetched, error) -> {
            try {
                if (error != null) {
                    long failures = this.failuresInARow.incrementAndGet();
                    if (failures == 1 || failures % 10 == 0) {
                        this.logger.log(Level.WARNING, "Could not rebuild the leaderboards (" + failures + " in a row); the previous ones stay", error);
                    }
                    return false;
                }
                long now = this.clock.getAsLong();
                Map<Board, List<Leaderboard.Row>> stored = fetched.rows();
                Predicate<UUID> hiding = fetched.hidden().test();
                Map<Board, Leaderboard> next = new EnumMap<>(Board.class);
                for (Board board : Board.values()) {
                    next.put(board, board == Board.MONEY
                        ? money(limit, fetchSize(limit, fetched.hidden()), hiding, now)
                        : Leaderboard.build(board, players(stored.getOrDefault(board, List.of()), hiding), limit, this.names, now));
                }
                Map<Board, Leaderboard> before = this.boards;
                Map<Board, Leaderboard> after = Map.copyOf(next);
                this.boards = after;
                this.refreshedAt = now;
                this.failuresInARow.set(0);
                try {
                    this.swapped.swapped(before, after);
                } catch (RuntimeException e) {
                    this.logger.log(Level.WARNING, "Telling players about leaderboard changes failed", e);
                }
                return true;
            } finally {
                this.running.set(false);
            }
        });
    }

    private List<Leaderboard.Row> players(List<Leaderboard.Row> rows, Predicate<UUID> hiding) {
        List<Leaderboard.Row> players = new ArrayList<>(rows.size());
        for (Leaderboard.Row row : rows) {
            if (this.listed.test(row.uuid()) && !hiding.test(row.uuid())) {
                players.add(row);
            }
        }
        return players;
    }

    private Leaderboard money(int limit, int fetch, Predicate<UUID> hiding, long now) {
        List<EconomyApi.TopEntry> top = this.moneyTop.apply(fetch);
        Map<UUID, String> known = new HashMap<>();
        List<Leaderboard.Row> rows = new ArrayList<>(top.size());
        for (EconomyApi.TopEntry entry : top) {
            rows.add(new Leaderboard.Row(entry.account(), entry.value(), 0));
            known.put(entry.account(), entry.name());
        }
        return Leaderboard.build(Board.MONEY, players(rows, hiding), limit, uuid -> {
            String name = known.get(uuid);
            return name == null ? this.names.apply(uuid) : name;
        }, now);
    }

    /** The current snapshot of a board (empty before the first refresh). */
    Leaderboard board(Board board) {
        Leaderboard snapshot = this.boards.get(board);
        return snapshot == null ? Leaderboard.empty(board) : snapshot;
    }

    /** When the boards were last rebuilt (epoch millis), 0 before the first refresh. */
    long refreshedAt() {
        return this.refreshedAt;
    }

    boolean refreshing() {
        return this.running.get();
    }
}
