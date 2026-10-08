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
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.api.economy.EconomyApi;

/**
 * Cached leaderboards. A refresh (on an async timer) first saves pending stats, then runs one query per board and
 * swaps in an immutable snapshot, so readers (dialogs, placeholders) never touch the database. The money board is
 * taken from the economy's own leaderboard. Only players who have joined the server are listed; a few extra rows are
 * fetched so a board still fills up when some rows belong to anything else. Only one refresh runs at a time.
 */
final class Leaderboards {

    /** Rows fetched beyond the board size, to make up for rows that are not players. */
    static final int EXTRA_ROWS = 25;

    private final StatsStore store;
    private final StatsStorage storage;
    private final IntFunction<List<EconomyApi.TopEntry>> moneyTop;
    private final Function<UUID, String> names;
    private final Predicate<UUID> listed;
    private final IntSupplier size;
    private final LongSupplier kdrMinKills;
    private final Logger logger;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong failuresInARow = new AtomicLong();
    private volatile Map<Board, Leaderboard> boards = emptyBoards();
    private volatile long refreshedAt;

    /**
     * @param listed whether an account may appear on a board (a player who joined); others are left out
     */
    Leaderboards(StatsStore store, StatsStorage storage, IntFunction<List<EconomyApi.TopEntry>> moneyTop,
                 Function<UUID, String> names, Predicate<UUID> listed, IntSupplier size, LongSupplier kdrMinKills,
                 Logger logger, LongSupplier clock) {
        this.store = store;
        this.storage = storage;
        this.moneyTop = moneyTop;
        this.names = names;
        this.listed = listed;
        this.size = size;
        this.kdrMinKills = kdrMinKills;
        this.logger = logger;
        this.clock = clock;
    }

    private static Map<Board, Leaderboard> emptyBoards() {
        Map<Board, Leaderboard> empty = new EnumMap<>(Board.class);
        for (Board board : Board.values()) {
            empty.put(board, Leaderboard.empty(board));
        }
        return Map.copyOf(empty);
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
        CompletableFuture<Map<Board, List<Leaderboard.Row>>> rows;
        try {
            // A failed save is retried later; the boards are built from what is stored.
            rows = this.store.save().handle((ignored, error) -> null)
                .thenCompose(ignored -> this.storage.top(limit + EXTRA_ROWS, minKills));
        } catch (RuntimeException e) {
            rows = CompletableFuture.failedFuture(e);
        }
        return rows.handle((stored, error) -> {
            try {
                if (error != null) {
                    long failures = this.failuresInARow.incrementAndGet();
                    if (failures == 1 || failures % 10 == 0) {
                        this.logger.log(Level.WARNING, "Could not rebuild the leaderboards (" + failures + " in a row); the previous ones stay", error);
                    }
                    return false;
                }
                long now = this.clock.getAsLong();
                Map<Board, Leaderboard> next = new EnumMap<>(Board.class);
                for (Board board : Board.values()) {
                    next.put(board, board == Board.MONEY
                        ? money(limit, now)
                        : Leaderboard.build(board, players(stored.getOrDefault(board, List.of())), limit, this.names, now));
                }
                this.boards = Map.copyOf(next);
                this.refreshedAt = now;
                this.failuresInARow.set(0);
                return true;
            } finally {
                this.running.set(false);
            }
        });
    }

    private List<Leaderboard.Row> players(List<Leaderboard.Row> rows) {
        List<Leaderboard.Row> players = new ArrayList<>(rows.size());
        for (Leaderboard.Row row : rows) {
            if (this.listed.test(row.uuid())) {
                players.add(row);
            }
        }
        return players;
    }

    private Leaderboard money(int limit, long now) {
        List<EconomyApi.TopEntry> top = this.moneyTop.apply(limit + EXTRA_ROWS);
        Map<UUID, String> known = new HashMap<>();
        List<Leaderboard.Row> rows = new ArrayList<>(top.size());
        for (EconomyApi.TopEntry entry : top) {
            rows.add(new Leaderboard.Row(entry.account(), entry.value(), 0));
            known.put(entry.account(), entry.name());
        }
        return Leaderboard.build(Board.MONEY, players(rows), limit, uuid -> {
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
