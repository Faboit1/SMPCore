package net.siftvanilla.siftcore.feature.stats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory {@link StatsStorage} that behaves like the database: writes are applied in the order they are
 * completed, as deltas on top of the stored row. In manual mode writes and loads wait until the test completes
 * them, so the in-flight states can be observed; failures can be injected.
 */
final class FakeStorage implements StatsStorage {

    private record Write(List<PendingWrite> writes, CompletableFuture<Map<UUID, String>> future) {
    }

    private record Load(UUID player, CompletableFuture<StatsSnapshot> future) {
    }

    private final Map<UUID, StatsSnapshot> rows = new HashMap<>();
    private final Deque<Write> heldWrites = new ArrayDeque<>();
    private final Deque<Load> heldLoads = new ArrayDeque<>();
    final AtomicInteger writeCalls = new AtomicInteger();
    final AtomicInteger loadCalls = new AtomicInteger();
    private final Set<UUID> refused = new HashSet<>();
    private boolean manual;
    private int failNextWrites;

    synchronized void manual(boolean manual) {
        this.manual = manual;
    }

    synchronized void failNextWrites(int count) {
        this.failNextWrites = count;
    }

    /** The database refuses every row of this player (like a value it cannot hold) until {@link #accept}. */
    synchronized void refuse(UUID player) {
        this.refused.add(player);
    }

    synchronized void accept(UUID player) {
        this.refused.remove(player);
    }

    synchronized void put(UUID player, StatsSnapshot row) {
        this.rows.put(player, row);
    }

    synchronized StatsSnapshot row(UUID player) {
        return this.rows.getOrDefault(player, StatsSnapshot.ZERO);
    }

    synchronized boolean hasRow(UUID player) {
        return this.rows.containsKey(player);
    }

    synchronized int heldWrites() {
        return this.heldWrites.size();
    }

    synchronized int heldLoads() {
        return this.heldLoads.size();
    }

    @Override
    public CompletableFuture<StatsSnapshot> load(UUID player) {
        this.loadCalls.incrementAndGet();
        CompletableFuture<StatsSnapshot> future = new CompletableFuture<>();
        synchronized (this) {
            if (this.manual) {
                this.heldLoads.add(new Load(player, future));
                return future;
            }
        }
        future.complete(row(player));
        return future;
    }

    @Override
    public CompletableFuture<Map<UUID, String>> write(List<PendingWrite> writes) {
        this.writeCalls.incrementAndGet();
        CompletableFuture<Map<UUID, String>> future = new CompletableFuture<>();
        synchronized (this) {
            if (this.manual) {
                this.heldWrites.add(new Write(List.copyOf(writes), future));
                return future;
            }
        }
        finish(new Write(List.copyOf(writes), future));
        return future;
    }

    /** Completes the oldest held write (applying it unless a failure was injected). */
    void completeWrite() {
        Write write;
        synchronized (this) {
            write = this.heldWrites.poll();
        }
        if (write == null) {
            throw new IllegalStateException("No held write");
        }
        finish(write);
    }

    /** Completes the oldest held load with the stored row. */
    void completeLoad() {
        Load load;
        synchronized (this) {
            load = this.heldLoads.poll();
        }
        if (load == null) {
            throw new IllegalStateException("No held load");
        }
        load.future().complete(row(load.player()));
    }

    /** Like {@link SqlStatsStorage}: each row is stored or refused on its own; all refused fails the whole write. */
    private void finish(Write write) {
        boolean fail;
        Map<UUID, String> refusedRows = new HashMap<>();
        synchronized (this) {
            fail = this.failNextWrites > 0;
            if (fail) {
                this.failNextWrites--;
            } else {
                for (PendingWrite pending : write.writes()) {
                    if (this.refused.contains(pending.player())) {
                        refusedRows.put(pending.player(), "injected refusal");
                    } else {
                        this.rows.put(pending.player(), pending.delta().applyTo(this.rows.getOrDefault(pending.player(), StatsSnapshot.ZERO)));
                    }
                }
                fail = refusedRows.size() == write.writes().size();
            }
        }
        if (fail) {
            write.future().completeExceptionally(new java.sql.SQLException("injected failure"));
        } else {
            write.future().complete(Map.copyOf(refusedRows));
        }
    }

    @Override
    public synchronized CompletableFuture<Map<Board, List<Leaderboard.Row>>> top(int limit, long kdrMinKills) {
        Map<Board, List<Leaderboard.Row>> result = new EnumMap<>(Board.class);
        for (Board board : Board.values()) {
            if (!board.fromStats()) {
                continue;
            }
            List<Leaderboard.Row> rows = new ArrayList<>();
            this.rows.forEach((uuid, row) -> {
                switch (board) {
                    case KDR -> {
                        if (row.kills() >= kdrMinKills && row.kills() > 0) {
                            rows.add(new Leaderboard.Row(uuid, row.kills(), row.deaths()));
                        }
                    }
                    case STREAK -> rows.add(new Leaderboard.Row(uuid, row.bestStreak(), 0));
                    default -> rows.add(new Leaderboard.Row(uuid, row.get(Counter.byId(board.id()).orElseThrow()), 0));
                }
            });
            rows.sort(board.displayOrder());
            result.put(board, rows.subList(0, Math.min(limit, rows.size())));
        }
        return CompletableFuture.completedFuture(result);
    }
}
