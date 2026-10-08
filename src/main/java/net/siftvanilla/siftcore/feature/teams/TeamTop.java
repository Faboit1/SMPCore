package net.siftvanilla.siftcore.feature.teams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Team leaderboards by total kills and by total money of the members. Rebuilt off-thread on a timer from memory
 * (stats and the ledger are both in memory) and read lock-free from immutable snapshots. Teams with nothing to
 * show (zero kills, no money) are not ranked.
 */
public final class TeamTop {

    /** The two boards. */
    public enum Board {
        KILLS,
        MONEY
    }

    /** One place on a board. */
    public record Entry(int rank, long team, String name, long value) {
    }

    private record Snapshot(List<Entry> top, Map<Long, Entry> byTeam) {
    }

    private volatile Snapshot kills = new Snapshot(List.of(), Map.of());
    private volatile Snapshot money = new Snapshot(List.of(), Map.of());
    private volatile long refreshedAt;

    /**
     * Rebuilds both boards.
     *
     * @param killsOf a member's lifetime kills
     * @param moneyOf a member's money
     * @param size    places kept for display (every ranked team still gets a place)
     * @param now     the time of the refresh
     */
    public void refresh(Collection<Team> teams, ToLongFunction<UUID> killsOf, ToLongFunction<UUID> moneyOf, int size, long now) {
        this.kills = build(teams, killsOf, size);
        this.money = build(teams, moneyOf, size);
        this.refreshedAt = now;
    }

    private static Snapshot build(Collection<Team> teams, ToLongFunction<UUID> valueOf, int size) {
        List<Entry> scored = new ArrayList<>();
        for (Team team : teams) {
            long total = total(team, valueOf);
            if (total > 0) {
                scored.add(new Entry(0, team.id(), team.name(), total));
            }
        }
        scored.sort(Comparator.comparingLong(Entry::value).reversed()
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(Entry::team));
        List<Entry> top = new ArrayList<>(Math.min(size, scored.size()));
        Map<Long, Entry> byTeam = new HashMap<>(scored.size() * 2);
        for (int i = 0; i < scored.size(); i++) {
            Entry ranked = new Entry(i + 1, scored.get(i).team(), scored.get(i).name(), scored.get(i).value());
            byTeam.put(ranked.team(), ranked);
            if (i < size) {
                top.add(ranked);
            }
        }
        return new Snapshot(List.copyOf(top), Map.copyOf(byTeam));
    }

    /** The sum over the members, saturating instead of overflowing; negative values count as zero. */
    public static long total(Team team, ToLongFunction<UUID> valueOf) {
        long sum = 0;
        for (UUID member : team.memberIds()) {
            long value = Math.max(0, valueOf.applyAsLong(member));
            sum = sum > Long.MAX_VALUE - value ? Long.MAX_VALUE : sum + value;
        }
        return sum;
    }

    /** The places kept for display, best first. */
    public List<Entry> top(Board board) {
        return snapshot(board).top();
    }

    /** The team's place on a board, if it is ranked. */
    public Optional<Entry> entry(Board board, long team) {
        return Optional.ofNullable(snapshot(board).byTeam().get(team));
    }

    /** The team's place number on a board, or 0 when it is not ranked. */
    public int rank(Board board, long team) {
        return entry(board, team).map(Entry::rank).orElse(0);
    }

    /** When the boards were last rebuilt (epoch millis, 0 = never). */
    public long refreshedAt() {
        return this.refreshedAt;
    }

    private Snapshot snapshot(Board board) {
        return board == Board.KILLS ? this.kills : this.money;
    }
}
