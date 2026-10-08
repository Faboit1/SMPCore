package net.siftvanilla.siftcore.feature.shards;

/** Arithmetic of shard shop purchases. Pure, unit tested. */
final class ShardMath {

    private ShardMath() {
    }

    /**
     * How many more items of one kind fit into an inventory: the free space in stacks of the same item plus whole
     * empty slots.
     *
     * @param partialAmounts amounts of the stacks that already hold this exact item
     * @param emptySlots     empty slots
     * @param maxStack       the item's maximum stack size
     */
    static long capacity(int[] partialAmounts, int emptySlots, int maxStack) {
        if (maxStack < 1 || emptySlots < 0) {
            return 0;
        }
        long free = (long) emptySlots * maxStack;
        for (int amount : partialAmounts) {
            free += Math.max(0, maxStack - amount);
        }
        return free;
    }

    /**
     * Which claim box stacks of a purchase to move into an inventory with room for {@code room} more items: every stack
     * that still fits, in order (a stack is never split, so what doesn't fit stays in the claim box).
     */
    static boolean[] pick(int[] stackAmounts, long room) {
        boolean[] chosen = new boolean[stackAmounts.length];
        long left = Math.max(0, room);
        for (int i = 0; i < stackAmounts.length; i++) {
            int amount = stackAmounts[i];
            if (amount > 0 && amount <= left) {
                chosen[i] = true;
                left -= amount;
            }
        }
        return chosen;
    }

    /** Shards left after paying, never below zero (for the confirmation text). */
    static long left(long balance, long total) {
        return Math.max(0, balance - total);
    }

    /** Whether a purchase of {@code total} needs a second confirmation. */
    static boolean needsConfirmation(long total, long confirmAbove) {
        return confirmAbove <= 0 || total >= confirmAbove;
    }
}
