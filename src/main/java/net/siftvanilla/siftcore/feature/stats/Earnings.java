package net.siftvanilla.siftcore.feature.stats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.Posting;
import net.siftvanilla.siftcore.economy.SystemAccounts;

/**
 * Works out the money each player earned in one committed transaction. A player earns the money credited to them
 * with an earning kind (selling, auction sales, order fills, bounties, spawner loot, crate money). Taxes taken from
 * that same player in that same transaction (auction and order taxes) are subtracted, never below zero. Debits of
 * an earning kind (the buyer's side of a sale), tax postings in transactions where the player earned nothing (a fee
 * paid up front), shards, system accounts and accounts that are not players never count.
 */
final class Earnings {

    private Earnings() {
    }

    /** Net earnings per player, only players with more than zero. */
    static Map<UUID, Long> of(List<Posting> postings, Set<String> earnKinds, Set<String> taxKinds, Predicate<UUID> isPlayer) {
        Map<UUID, long[]> sums = new HashMap<>(4);
        for (Posting posting : postings) {
            if (posting.currency() != Currency.MONEY || SystemAccounts.isSystem(posting.account())) {
                continue;
            }
            long delta = posting.delta();
            if (delta > 0 && earnKinds.contains(posting.kind())) {
                long[] sum = sums.computeIfAbsent(posting.account(), k -> new long[2]);
                sum[0] = StreakChange.plus(sum[0], delta);
            } else if (delta < 0 && taxKinds.contains(posting.kind())) {
                long[] sum = sums.computeIfAbsent(posting.account(), k -> new long[2]);
                sum[1] = StreakChange.plus(sum[1], -delta);
            }
        }
        Map<UUID, Long> result = new HashMap<>(4);
        for (Map.Entry<UUID, long[]> entry : sums.entrySet()) {
            long net = entry.getValue()[0] - entry.getValue()[1];
            if (entry.getValue()[0] > 0 && net > 0 && isPlayer.test(entry.getKey())) {
                result.put(entry.getKey(), net);
            }
        }
        return result;
    }
}
