package net.siftvanilla.siftcore.feature.spawners;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage and stack arithmetic. A stacked spawner holds {@code slots} slots of 64 items of any kind, and XP up to a
 * per-spawner amount; both grow with the stack. Bukkit-free so it can be tested.
 */
final class StorageMath {

    /** Items one storage slot holds, whatever the item. */
    static final int ITEMS_PER_SLOT = 64;
    /** The largest stack any spawner can reach, whatever the config and ranks say. */
    static final int HARD_STACK_CAP = 10_000;

    private StorageMath() {
    }

    /** Items a spawner stack can hold. */
    static long capacity(int stack, int slotsPerSpawner) {
        if (stack <= 0 || slotsPerSpawner <= 0) {
            return 0;
        }
        return (long) stack * slotsPerSpawner * ITEMS_PER_SLOT;
    }

    /** XP a spawner stack can hold. */
    static long xpCapacity(int stack, long xpPerSpawner) {
        if (stack <= 0 || xpPerSpawner <= 0) {
            return 0;
        }
        try {
            return Math.multiplyExact(stack, xpPerSpawner);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** XP after adding {@code generated} to {@code stored}, never above {@code capacity} (and never lowered). */
    static long addXp(long stored, long generated, long capacity) {
        if (generated <= 0 || stored >= capacity) {
            return stored;
        }
        long room = capacity - stored;
        return stored + Math.min(room, generated);
    }

    /** The stack cap for a mob and a player's rank bonus, never above {@link #HARD_STACK_CAP}. */
    static int stackCap(int mobCap, int rankBonus) {
        long cap = (long) Math.max(1, mobCap) + Math.max(0, rankBonus);
        return (int) Math.min(HARD_STACK_CAP, cap);
    }

    /** How many of {@code offered} spawners fit on a stack of {@code stack} with cap {@code cap}. */
    static int acceptable(int stack, int cap, int offered) {
        if (offered <= 0 || stack >= cap) {
            return 0;
        }
        return Math.min(offered, cap - stack);
    }

    /**
     * What part of freshly generated loot fits into {@code free} items of space. Everything fits when there is room;
     * otherwise every kind gets its proportional share (largest remainders get the leftover items, ties by key), so
     * a full storage never favours one drop over another. The result never exceeds the loot of any kind, and its
     * total is {@code min(free, total loot)}.
     */
    static Map<String, Long> fit(Map<String, Long> loot, long free) {
        long total = 0;
        for (long amount : loot.values()) {
            if (amount > 0) {
                total = saturatingAdd(total, amount);
            }
        }
        Map<String, Long> result = new LinkedHashMap<>();
        if (total <= 0 || free <= 0) {
            return result;
        }
        if (total <= free) {
            loot.forEach((item, amount) -> {
                if (amount > 0) {
                    result.put(item, amount);
                }
            });
            return result;
        }
        record Share(String item, long whole, long remainder, long cap) {
        }
        BigInteger freeBig = BigInteger.valueOf(free);
        BigInteger totalBig = BigInteger.valueOf(total);
        List<Share> shares = new ArrayList<>(loot.size());
        long given = 0;
        for (Map.Entry<String, Long> entry : loot.entrySet()) {
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }
            BigInteger[] division = BigInteger.valueOf(amount).multiply(freeBig).divideAndRemainder(totalBig);
            long whole = division[0].longValueExact();
            shares.add(new Share(entry.getKey(), whole, division[1].longValueExact(), amount));
            given += whole;
        }
        long left = free - given;
        List<Share> order = new ArrayList<>(shares);
        order.sort(Comparator.comparingLong(Share::remainder).reversed().thenComparing(Share::item));
        Map<String, Long> extra = new LinkedHashMap<>();
        while (left > 0) {
            boolean progressed = false;
            for (Share share : order) {
                if (left <= 0) {
                    break;
                }
                long current = share.whole() + extra.getOrDefault(share.item(), 0L);
                if (current < share.cap()) {
                    extra.merge(share.item(), 1L, Long::sum);
                    left--;
                    progressed = true;
                }
            }
            if (!progressed) {
                break;
            }
        }
        for (Share share : shares) {
            long amount = share.whole() + extra.getOrDefault(share.item(), 0L);
            if (amount > 0) {
                result.put(share.item(), amount);
            }
        }
        return result;
    }

    /** How many stacks of {@code maxStack} items {@code amount} items need. */
    static long stacks(long amount, int maxStack) {
        if (amount <= 0) {
            return 0;
        }
        int size = Math.max(1, maxStack);
        return (amount + size - 1) / size;
    }

    static long saturatingMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return (high == 0 && low >= 0) || (high == -1 && low < 0) ? low : (a < 0) == (b < 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
