package net.siftvanilla.siftcore.feature.economy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.EconomyApi;
import net.siftvanilla.siftcore.core.player.PlayerDirectory;
import net.siftvanilla.siftcore.economy.Ledger;
import net.siftvanilla.siftcore.economy.SystemAccounts;

/**
 * Leaderboards of balances, rebuilt off-thread on a timer from the in-memory ledger (never a database query on a
 * world thread). Holds the top N entries plus the sorted list of all balances so a player's rank is a binary search.
 */
public final class BalanceTop {

    /** One currency's snapshot. */
    private record Snapshot(List<EconomyApi.TopEntry> top, long[] sortedDescending) {
    }

    private final Ledger ledger;
    private final PlayerDirectory directory;
    private volatile Map<Currency, Snapshot> snapshots = Map.of();

    public BalanceTop(Ledger ledger, PlayerDirectory directory) {
        this.ledger = ledger;
        this.directory = directory;
    }

    /** Rebuilds every leaderboard; runs on an async thread. */
    public void refresh(int size) {
        Map<Currency, Snapshot> next = new EnumMap<>(Currency.class);
        for (Currency currency : Currency.values()) {
            PriorityQueue<Map.Entry<UUID, Long>> heap = new PriorityQueue<>(size + 1, Map.Entry.comparingByValue());
            long[][] all = {new long[Math.max(16, this.ledger.accountCount())]};
            int[] count = {0};
            this.ledger.forEach(currency, (account, balance) -> {
                if (SystemAccounts.isSystem(account) || balance <= 0) {
                    return;
                }
                if (count[0] == all[0].length) {
                    all[0] = Arrays.copyOf(all[0], all[0].length * 2);
                }
                all[0][count[0]++] = balance;
                heap.add(Map.entry(account, balance));
                if (heap.size() > size) {
                    heap.poll();
                }
            });
            List<Map.Entry<UUID, Long>> best = new ArrayList<>(heap);
            best.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            List<EconomyApi.TopEntry> top = new ArrayList<>(best.size());
            for (int i = 0; i < best.size(); i++) {
                UUID account = best.get(i).getKey();
                top.add(new EconomyApi.TopEntry(i + 1, account, this.directory.name(account), best.get(i).getValue()));
            }
            long[] sorted = Arrays.copyOf(all[0], count[0]);
            Arrays.sort(sorted);
            for (int i = 0, j = sorted.length - 1; i < j; i++, j--) {
                long tmp = sorted[i];
                sorted[i] = sorted[j];
                sorted[j] = tmp;
            }
            next.put(currency, new Snapshot(List.copyOf(top), sorted));
        }
        this.snapshots = Map.copyOf(next);
    }

    public List<EconomyApi.TopEntry> top(Currency currency, int limit) {
        Snapshot snapshot = this.snapshots.get(currency);
        if (snapshot == null) {
            return List.of();
        }
        return snapshot.top().subList(0, Math.min(limit, snapshot.top().size()));
    }

    /** The 1-based rank a balance would have, or 0 when the balance is zero. */
    public int rankOf(Currency currency, long balance) {
        Snapshot snapshot = this.snapshots.get(currency);
        if (snapshot == null || balance <= 0) {
            return 0;
        }
        long[] sorted = snapshot.sortedDescending();
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] > balance) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low + 1;
    }

    /** Number of accounts with a positive balance at the last refresh. */
    public int ranked(Currency currency) {
        Snapshot snapshot = this.snapshots.get(currency);
        return snapshot == null ? 0 : snapshot.sortedDescending().length;
    }
}
