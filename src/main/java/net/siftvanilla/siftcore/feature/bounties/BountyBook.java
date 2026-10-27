package net.siftvanilla.siftcore.feature.bounties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every active bounty contribution, grouped by target. Changed only inside ledger transactions (under the economy
 * lock, through their apply and revert steps) and at startup; read from any thread. Each target's bounty is an
 * immutable value replaced as a whole, so a reader always sees one consistent bounty.
 */
final class BountyBook {

    /** One player's money on another player's head. */
    record Contribution(long id, UUID target, UUID sponsor, long amount, long created) {
    }

    /**
     * A target's bounty: the contributions oldest first, their total and the number of different sponsors.
     */
    record Bounty(UUID target, List<Contribution> contributions, long total, int sponsors) {

        static Bounty of(UUID target, List<Contribution> contributions) {
            List<Contribution> sorted = new ArrayList<>(contributions);
            sorted.sort(Comparator.comparingLong(Contribution::created).thenComparingLong(Contribution::id));
            long total = 0;
            Set<UUID> sponsors = new HashSet<>();
            for (Contribution contribution : sorted) {
                total = BountyMath.add(total, contribution.amount());
                sponsors.add(contribution.sponsor());
            }
            return new Bounty(target, List.copyOf(sorted), total, sponsors.size());
        }

        /** The part a sponsor put up. */
        long from(UUID sponsor) {
            long sum = 0;
            for (Contribution contribution : this.contributions) {
                if (contribution.sponsor().equals(sponsor)) {
                    sum = BountyMath.add(sum, contribution.amount());
                }
            }
            return sum;
        }

        /** The contributions a killer may claim: everything except what they put up themselves. */
        List<Contribution> claimableBy(UUID killer) {
            List<Contribution> claimable = new ArrayList<>(this.contributions.size());
            for (Contribution contribution : this.contributions) {
                if (!contribution.sponsor().equals(killer)) {
                    claimable.add(contribution);
                }
            }
            return claimable;
        }

        /** When the oldest contribution was placed. */
        long oldest() {
            return this.contributions.getFirst().created();
        }

        boolean contains(long id) {
            for (Contribution contribution : this.contributions) {
                if (contribution.id() == id) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Ranking(long version, List<Bounty> bounties) {
    }

    private static final Comparator<Bounty> BIGGEST_FIRST = Comparator.comparingLong(Bounty::total).reversed()
        .thenComparingLong(Bounty::oldest)
        .thenComparing(Bounty::target);

    private final Map<UUID, Bounty> byTarget = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong version = new AtomicLong();
    private volatile Ranking ranking = new Ranking(-1, List.of());

    /** Adds a contribution (inside a transaction's apply, or at startup). */
    void add(Contribution contribution) {
        this.byTarget.compute(contribution.target(), (target, bounty) -> {
            if (bounty != null && bounty.contains(contribution.id())) {
                return bounty;
            }
            List<Contribution> list = new ArrayList<>(bounty == null ? List.of() : bounty.contributions());
            list.add(contribution);
            this.total.addAndGet(contribution.amount());
            return Bounty.of(target, list);
        });
        this.version.incrementAndGet();
    }

    /** Removes a contribution; returns false when it was not active. */
    boolean remove(Contribution contribution) {
        boolean[] removed = {false};
        this.byTarget.computeIfPresent(contribution.target(), (target, bounty) -> {
            List<Contribution> list = new ArrayList<>(bounty.contributions().size());
            for (Contribution existing : bounty.contributions()) {
                if (existing.id() == contribution.id()) {
                    removed[0] = true;
                } else {
                    list.add(existing);
                }
            }
            if (!removed[0]) {
                return bounty;
            }
            this.total.addAndGet(-contribution.amount());
            return list.isEmpty() ? null : Bounty.of(target, list);
        });
        if (removed[0]) {
            this.version.incrementAndGet();
        }
        return removed[0];
    }

    /** True when every contribution is still active. */
    boolean allActive(Collection<Contribution> contributions) {
        for (Contribution contribution : contributions) {
            Bounty bounty = this.byTarget.get(contribution.target());
            if (bounty == null || !bounty.contains(contribution.id())) {
                return false;
            }
        }
        return true;
    }

    /** The bounty on a player, or null. */
    Bounty get(UUID target) {
        return this.byTarget.get(target);
    }

    /** The total on a player, 0 without a bounty. */
    long total(UUID target) {
        Bounty bounty = this.byTarget.get(target);
        return bounty == null ? 0 : bounty.total();
    }

    /** Every active contribution (any order). */
    List<Contribution> all() {
        List<Contribution> all = new ArrayList<>();
        for (Bounty bounty : this.byTarget.values()) {
            all.addAll(bounty.contributions());
        }
        return all;
    }

    /** The sum of every active contribution: what the bounty escrow must hold. */
    long totalActive() {
        return this.total.get();
    }

    int targets() {
        return this.byTarget.size();
    }

    /** The biggest bounties, biggest first (ties: the older first). Rebuilt only after a change. */
    List<Bounty> top(int limit) {
        long current = this.version.get();
        Ranking cached = this.ranking;
        if (cached.version() != current) {
            List<Bounty> sorted = new ArrayList<>(this.byTarget.values());
            sorted.sort(BIGGEST_FIRST);
            cached = new Ranking(current, List.copyOf(sorted));
            this.ranking = cached;
        }
        List<Bounty> all = cached.bounties();
        return all.subList(0, Math.min(Math.max(0, limit), all.size()));
    }

    /** Where a bounty of this size would rank (1 = biggest), 0 without one. */
    int rankOf(UUID target) {
        Bounty bounty = this.byTarget.get(target);
        if (bounty == null) {
            return 0;
        }
        List<Bounty> all = top(Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).target().equals(target)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Empties the book (startup reload). */
    void clear() {
        this.byTarget.clear();
        this.total.set(0);
        this.version.incrementAndGet();
    }
}
