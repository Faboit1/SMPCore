package net.siftvanilla.siftcore.feature.afk;

import java.util.Locale;

/**
 * The AFK zone as plain arithmetic: every block between two corners (both included) in one world, plus where
 * /afkzone puts players. Immutable and safe from any thread; the feature swaps the whole value when the zone changes.
 *
 * @param arrival where /afkzone teleports to, or null for the ground in the middle of the zone
 */
record ZoneBox(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Point arrival) {

    /** A position with a view direction. */
    record Point(double x, double y, double z, float yaw, float pitch) {

        Point offset(double dx, double dy, double dz) {
            return new Point(this.x + dx, this.y + dy, this.z + dz, this.yaw, this.pitch);
        }

        /** Parses {@code "x y z"} or {@code "x y z yaw pitch"}. */
        static Point parse(String text) {
            String[] parts = text.trim().split("\\s+");
            if (parts.length != 3 && parts.length != 5) {
                throw new IllegalArgumentException("needs 3 numbers (x y z) or 5 (x y z yaw pitch)");
            }
            double[] values = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    values[i] = Double.parseDouble(parts[i]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + parts[i] + "' is not a number");
                }
                if (!Double.isFinite(values[i]) || Math.abs(values[i]) > 30_000_000) {
                    throw new IllegalArgumentException("'" + parts[i] + "' is out of range");
                }
            }
            return parts.length == 3
                ? new Point(values[0], values[1], values[2], 0f, 0f)
                : new Point(values[0], values[1], values[2], (float) values[3], (float) Math.clamp(values[4], -90.0, 90.0));
        }

        String format() {
            return String.format(Locale.ROOT, "%.1f %.1f %.1f %.0f %.0f", this.x, this.y, this.z, this.yaw, this.pitch);
        }
    }

    /** A block corner {@code "x y z"}. */
    record Corner(int x, int y, int z) {

        Corner offset(int dx, int dy, int dz) {
            return new Corner(this.x + dx, this.y + dy, this.z + dz);
        }

        static Corner parse(String text) {
            String[] parts = text.trim().split("\\s+");
            if (parts.length != 3) {
                throw new IllegalArgumentException("needs 3 whole numbers (x y z)");
            }
            int[] values = new int[3];
            for (int i = 0; i < 3; i++) {
                try {
                    values[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + parts[i] + "' is not a whole number");
                }
                if (Math.abs(values[i]) > 30_000_000) {
                    throw new IllegalArgumentException("'" + parts[i] + "' is out of range");
                }
            }
            return new Corner(values[0], values[1], values[2]);
        }

        String format() {
            return this.x + " " + this.y + " " + this.z;
        }
    }

    /** The box between two corners in any order. */
    static ZoneBox of(String world, Corner a, Corner b, Point arrival) {
        return new ZoneBox(world, Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
            Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()), arrival);
    }

    /** True when the point is inside (block coordinates, both corners included). */
    boolean contains(String world, double x, double y, double z) {
        if (!this.world.equals(world)) {
            return false;
        }
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        return bx >= this.minX && bx <= this.maxX && by >= this.minY && by <= this.maxY && bz >= this.minZ && bz <= this.maxZ;
    }

    double centerX() {
        return (this.minX + this.maxX + 1) / 2.0;
    }

    double centerZ() {
        return (this.minZ + this.maxZ + 1) / 2.0;
    }

    long volume() {
        return (long) (this.maxX - this.minX + 1) * (this.maxY - this.minY + 1) * (this.maxZ - this.minZ + 1);
    }

    /** The eight corner points of the box (block corners, outer edges included), for "is it all inside spawn". */
    double[][] cornerPoints() {
        double[][] points = new double[8][];
        int i = 0;
        for (int x : new int[] {this.minX, this.maxX}) {
            for (int y : new int[] {this.minY, this.maxY}) {
                for (int z : new int[] {this.minZ, this.maxZ}) {
                    points[i++] = new double[] {x + 0.5, y + 0.5, z + 0.5};
                }
            }
        }
        return points;
    }

    String describe() {
        return String.format(Locale.ROOT, "%s %d %d %d to %d %d %d", this.world, this.minX, this.minY, this.minZ,
            this.maxX, this.maxY, this.maxZ);
    }
}
