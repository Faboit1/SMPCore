package net.siftvanilla.siftcore.feature.rtp;

import java.util.OptionalInt;

/**
 * Decides where in one block column a player can safely land, independent of Bukkit so it can be tested on a
 * fake block grid. A safe spot is solid, harmless ground with two clear blocks above it (feet and head).
 */
final class SafeSpot {

    /** What a block means for landing on or in it. */
    enum Surface {
        /** Nothing in the way: air, short plants, snow layers. Fine for feet and head. */
        CLEAR,
        /** Solid, harmless ground. */
        SOLID,
        /** Water, lava or anything waterlogged. */
        LIQUID,
        /** Hurts or traps: fire, magma, cactus, powder snow, berry bushes, cobwebs, campfires, wither roses... */
        HAZARD,
        /** Blocks you can't stand in and shouldn't stand on: leaves, fences, walls, glass panes. */
        OTHER
    }

    /** One block column, read top to bottom. */
    @FunctionalInterface
    interface Column {
        Surface at(int y);
    }

    private SafeSpot() {
    }

    static boolean standable(Surface ground, Surface feet, Surface head) {
        return ground == Surface.SOLID && feet == Surface.CLEAR && head == Surface.CLEAR;
    }

    /**
     * Surface worlds (overworld, end): the topmost motion-blocking block is the ground. Returns the feet height, or
     * empty when that spot is not safe (water, lava, a tree top, the void of the end).
     *
     * @param top  the y of the topmost motion-blocking block (from the height map)
     * @param minY the lowest block y of the world
     * @param maxY the highest block y of the world
     */
    static OptionalInt surface(Column column, int top, int minY, int maxY) {
        if (top < minY || top + 2 > maxY) {
            return OptionalInt.empty();
        }
        return standable(column.at(top), column.at(top + 1), column.at(top + 2)) ? OptionalInt.of(top + 1) : OptionalInt.empty();
    }

    /**
     * Cavern worlds (the nether): scans down from {@code fromY} to {@code toY} for the first safe floor below the
     * bedrock roof. Reads each block once. Returns the feet height or empty.
     */
    static OptionalInt cavern(Column column, int fromY, int toY) {
        if (fromY < toY) {
            return OptionalInt.empty();
        }
        Surface head = column.at(fromY + 2);
        Surface feet = column.at(fromY + 1);
        for (int y = fromY; y >= toY; y--) {
            Surface ground = column.at(y);
            if (standable(ground, feet, head)) {
                return OptionalInt.of(y + 1);
            }
            head = feet;
            feet = ground;
        }
        return OptionalInt.empty();
    }
}
