package net.siftvanilla.siftcore.feature.spawners;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Virtual loot: what {@code n} kills of a mob drop, worked out without simulating {@code n} kills. Each drop entry
 * costs a bounded number of random draws whatever {@code n} is, so a stack of a thousand spawners is as cheap as one:
 * <ol>
 *   <li>how many kills drop the item: a binomial count, simulated exactly for small {@code n}, by geometric jumps
 *       when few or almost all kills drop it, and with the normal approximation otherwise;</li>
 *   <li>how much those drops add up to: a sum of uniform amounts, simulated exactly for few drops and with the
 *       normal approximation otherwise.</li>
 * </ol>
 * Results never leave their possible range ({@code 0..n} drops, {@code drops*min..drops*max} items), and the mean is
 * exact up to rounding. Bukkit-free so it can be tested.
 */
final class LootMath {

    /** Up to this many trials or drops are simulated one by one. */
    static final int EXACT_LIMIT = 32;
    /** Below this many expected successes (or failures) the binomial count uses geometric jumps. */
    static final double SPARSE_LIMIT = 16.0;

    private LootMath() {
    }

    /** How many of {@code n} independent trials succeed with probability {@code p} each. */
    static long binomial(long n, double p, RandomGenerator random) {
        if (n <= 0 || p <= 0) {
            return 0;
        }
        if (p >= 1) {
            return n;
        }
        if (n <= EXACT_LIMIT) {
            long hits = 0;
            for (long i = 0; i < n; i++) {
                if (random.nextDouble() < p) {
                    hits++;
                }
            }
            return hits;
        }
        double q = 1.0 - p;
        if (n * p < SPARSE_LIMIT) {
            return sparse(n, p, random);
        }
        if (n * q < SPARSE_LIMIT) {
            return n - sparse(n, q, random);
        }
        double mean = n * p;
        double sd = Math.sqrt(mean * q);
        long value = Math.round(mean + sd * random.nextGaussian());
        return Math.clamp(value, 0L, n);
    }

    /**
     * Successes among {@code n} trials of probability {@code p}, counted by jumping from one success to the next with
     * geometrically distributed gaps: about {@code n*p + 1} draws.
     */
    private static long sparse(long n, double p, RandomGenerator random) {
        double logFail = Math.log1p(-p);
        long position = 0;
        long hits = 0;
        while (true) {
            double u = random.nextDouble();
            double gap = Math.floor(Math.log1p(-u) / logFail);
            if (!(gap < n)) {
                return hits;
            }
            position += (long) gap + 1;
            if (position > n) {
                return hits;
            }
            hits++;
        }
    }

    /** The total of {@code drops} amounts, each uniform in {@code min..max}. */
    static long uniformSum(long drops, int min, int max, RandomGenerator random) {
        if (drops <= 0) {
            return 0;
        }
        if (min == max) {
            return Math.multiplyExact(drops, (long) min);
        }
        if (drops <= EXACT_LIMIT) {
            long total = 0;
            for (long i = 0; i < drops; i++) {
                total += random.nextInt(min, max + 1);
            }
            return total;
        }
        double width = max - min + 1.0;
        double mean = drops * (min + max) / 2.0;
        double sd = Math.sqrt(drops * (width * width - 1.0) / 12.0);
        long value = Math.round(mean + sd * random.nextGaussian());
        return Math.clamp(value, Math.multiplyExact(drops, (long) min), Math.multiplyExact(drops, (long) max));
    }

    /** What {@code kills} kills drop, item key to amount (items that dropped nothing are left out). */
    static Map<String, Long> roll(List<DropEntry> drops, long kills, RandomGenerator random) {
        Map<String, Long> loot = new LinkedHashMap<>();
        if (kills <= 0) {
            return loot;
        }
        for (DropEntry entry : drops) {
            long hits = binomial(kills, entry.chance(), random);
            long amount = uniformSum(hits, entry.min(), entry.max(), random);
            if (amount > 0) {
                loot.merge(entry.item(), amount, Long::sum);
            }
        }
        return loot;
    }

    /**
     * Kills this cycle for {@code stack} stacked spawners at {@code killsPerCycle} each. A fractional total is rounded
     * up with the probability of its fraction, so the average is exact.
     */
    static long kills(int stack, double killsPerCycle, RandomGenerator random) {
        if (stack <= 0 || killsPerCycle <= 0) {
            return 0;
        }
        double total = stack * killsPerCycle;
        long whole = (long) Math.floor(total);
        double fraction = total - whole;
        return fraction > 0 && random.nextDouble() < fraction ? whole + 1 : whole;
    }

    /** Average items of one kind per kill, summed over the table. */
    static double expectedItemsPerKill(List<DropEntry> drops) {
        double total = 0;
        for (DropEntry entry : drops) {
            total += entry.expectedPerKill();
        }
        return total;
    }
}
