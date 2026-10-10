package net.siftvanilla.siftcore.feature.spawners;

import java.util.Objects;

/**
 * One line of a mob's drop table: each kill drops the item with probability {@code chance}, and when it does, a
 * whole amount between {@code min} and {@code max} (both included, every amount equally likely). Looting is ignored.
 *
 * @param item   item key, e.g. {@code minecraft:rotten_flesh}
 * @param min    smallest amount of one drop (0 or more)
 * @param max    largest amount of one drop ({@code min} or more)
 * @param chance probability that a kill drops the item at all (0 to 1)
 */
record DropEntry(String item, int min, int max, double chance) {

    /** The most one drop entry may give per kill. */
    static final int MAX_AMOUNT = 64;

    DropEntry {
        Objects.requireNonNull(item);
        if (min < 0 || max < min || max > MAX_AMOUNT) {
            throw new IllegalArgumentException("amounts must satisfy 0 <= min <= max <= " + MAX_AMOUNT + ", got " + min + ".." + max);
        }
        if (!(chance >= 0 && chance <= 1)) {
            throw new IllegalArgumentException("chance must be between 0 and 1, got " + chance);
        }
    }

    /** Average amount of this item per kill. */
    double expectedPerKill() {
        return this.chance * (this.min + this.max) / 2.0;
    }
}
