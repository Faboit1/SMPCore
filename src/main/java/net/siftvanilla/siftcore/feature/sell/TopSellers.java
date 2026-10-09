package net.siftvanilla.siftcore.feature.sell;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.storage.Database;

/**
 * The players who sold the most to the server (base value over every category), read from storage in the
 * background every few minutes. Every lookup is served from the last snapshot, so placeholders, displays and
 * {@code /sell top} never touch the database. Players who hide from leaderboards ({@code hide-from-leaderboards})
 * get no place: they are left out of the list and of the ranks.
 */
final class TopSellers {

    /** How many places are named. */
    static final int PLACES = 10;

    /** A place on the list. */
    record Entry(UUID uuid, String name, long sold) {
    }

    /**
     * One read of the totals.
     *
     * @param top    the first {@link #PLACES} places, without hidden players
     * @param sorted every total of a player who is not hidden, highest first (for ranks)
     * @param byUuid every player's total, hidden players included (what they sold is theirs to see; it is not a place)
     * @param at     when it was read
     */
    record Snapshot(List<Entry> top, long[] sorted, Map<UUID, Long> byUuid, long at) {

        static final Snapshot EMPTY = new Snapshot(List.of(), new long[0], Map.of(), 0);

        /** The 1-based place a total would have (ties share the better place). */
        int rankOf(long total) {
            int low = 0;
            int high = this.sorted.length;
            // first index whose value is not greater than total
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (this.sorted[mid] > total) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low + 1;
        }

        Optional<Entry> place(int place) {
            return place >= 1 && place <= this.top.size() ? Optional.of(this.top.get(place - 1)) : Optional.empty();
        }
    }

    private final Database database;
    private final Function<UUID, String> names;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    TopSellers(Database database, Function<UUID, String> names) {
        this.database = database;
        this.names = names;
    }

    Snapshot snapshot() {
        return this.snapshot;
    }

    /** Reads every total and replaces the snapshot, leaving out the {@code hidden} players. Off the world threads. */
    CompletableFuture<Snapshot> refresh(Predicate<UUID> hidden) {
        return this.database.read(c -> {
            List<UUID> uuids = new ArrayList<>();
            List<Long> totals = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, SUM(sold) AS total FROM sell_mastery GROUP BY uuid ORDER BY total DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long total = rs.getLong(2);
                    if (total <= 0) {
                        continue;
                    }
                    try {
                        uuids.add(UUID.fromString(rs.getString(1)));
                        totals.add(total);
                    } catch (IllegalArgumentException ignored) {
                        // A malformed row can't be shown; it is skipped.
                    }
                }
            }
            return build(uuids, totals, hidden, this.names, System.currentTimeMillis());
        }).thenApply(built -> {
            this.snapshot = built;
            return built;
        });
    }

    /**
     * The snapshot of totals read highest first: hidden players keep their own total but take no place, so the ones
     * below them move up.
     *
     * @param names the name to show for a player (null when unknown: the start of the uuid is shown)
     */
    static Snapshot build(List<UUID> uuids, List<Long> totals, Predicate<UUID> hidden, Function<UUID, String> names, long at) {
        List<Entry> top = new ArrayList<>(PLACES);
        long[] ranked = new long[totals.size()];
        int count = 0;
        Map<UUID, Long> byUuid = new HashMap<>(uuids.size() * 2);
        for (int i = 0; i < totals.size(); i++) {
            UUID uuid = uuids.get(i);
            long total = totals.get(i);
            byUuid.put(uuid, total);
            if (hidden.test(uuid)) {
                continue;
            }
            ranked[count++] = total;
            if (top.size() < PLACES) {
                String name = names.apply(uuid);
                top.add(new Entry(uuid, name == null ? uuid.toString().substring(0, 8) : name, total));
            }
        }
        return new Snapshot(List.copyOf(top), Arrays.copyOf(ranked, count), Map.copyOf(byUuid), at);
    }
}
