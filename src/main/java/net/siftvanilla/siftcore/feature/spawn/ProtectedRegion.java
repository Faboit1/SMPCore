package net.siftvanilla.siftcore.feature.spawn;

import java.util.Locale;

/**
 * The protected spawn area as plain arithmetic, so it can be checked from any thread without touching the world.
 * Immutable; the spawn feature swaps the whole value when the spawn point or the config changes.
 */
public sealed interface ProtectedRegion {

    /** Nothing is protected (protection disabled or no spawn world). */
    ProtectedRegion NONE = new Disabled();

    /** True when the point is inside the region. {@code world} is the Bukkit world name. */
    boolean contains(String world, double x, double y, double z);

    /** One line for logs and the self-test, e.g. {@code radius 64 around world 0, 0}. */
    String describe();

    /** Protection is off. */
    record Disabled() implements ProtectedRegion {
        @Override
        public boolean contains(String world, double x, double y, double z) {
            return false;
        }

        @Override
        public String describe() {
            return "disabled";
        }
    }

    /** Everything within {@code radius} blocks of a point, measured flat (a cylinder from the bottom to the top of the world). */
    record Radius(String world, double centerX, double centerZ, double radius) implements ProtectedRegion {
        public Radius {
            if (radius < 0) {
                throw new IllegalArgumentException("radius must not be negative");
            }
        }

        @Override
        public boolean contains(String world, double x, double y, double z) {
            if (!this.world.equals(world)) {
                return false;
            }
            double dx = x - this.centerX;
            double dz = z - this.centerZ;
            return dx * dx + dz * dz <= this.radius * this.radius;
        }

        @Override
        public String describe() {
            return String.format(Locale.ROOT, "radius %.0f around %s %.0f, %.0f", this.radius, this.world, this.centerX, this.centerZ);
        }
    }

    /** Every block between two corners, both included. */
    record Cuboid(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements ProtectedRegion {

        /** Builds a cuboid from two corners in any order. */
        public static Cuboid of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Cuboid(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        @Override
        public boolean contains(String world, double x, double y, double z) {
            if (!this.world.equals(world)) {
                return false;
            }
            int bx = (int) Math.floor(x);
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z);
            return bx >= this.minX && bx <= this.maxX && by >= this.minY && by <= this.maxY && bz >= this.minZ && bz <= this.maxZ;
        }

        @Override
        public String describe() {
            return String.format(Locale.ROOT, "cuboid %s %d %d %d to %d %d %d", this.world, this.minX, this.minY, this.minZ,
                this.maxX, this.maxY, this.maxZ);
        }
    }
}
