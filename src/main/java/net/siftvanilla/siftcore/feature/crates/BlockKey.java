package net.siftvanilla.siftcore.feature.crates;

import java.util.Locale;
import java.util.Objects;

/**
 * The position of a block, by world name. Pure value; parsed from and written as {@code world x y z}.
 */
public record BlockKey(String world, int x, int y, int z) {

    public BlockKey {
        Objects.requireNonNull(world);
    }

    /**
     * Parses {@code world x y z}.
     *
     * @throws IllegalArgumentException with a short reason
     */
    public static BlockKey parse(String text) {
        String[] parts = text.strip().split("\\s+");
        if (parts.length != 4) {
            throw new IllegalArgumentException("must be 'world x y z'");
        }
        try {
            return new BlockKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("must be 'world x y z' with whole numbers");
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %d %d %d", this.world, this.x, this.y, this.z);
    }
}
