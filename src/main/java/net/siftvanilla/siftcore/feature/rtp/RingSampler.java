package net.siftvanilla.siftcore.feature.rtp;

import java.util.random.RandomGenerator;

/**
 * Picks random points in a ring (annulus) around a centre, uniformly by area: every square block of the ring is
 * equally likely, so the far edge (which has more area) is not under-represented as it would be with a uniform
 * radius.
 */
final class RingSampler {

    /** A block column. */
    record Point(int x, int z) {
    }

    private RingSampler() {
    }

    static Point sample(RandomGenerator random, double centerX, double centerZ, double minRadius, double maxRadius) {
        if (minRadius < 0 || maxRadius < minRadius) {
            throw new IllegalArgumentException("Invalid ring " + minRadius + ".." + maxRadius);
        }
        double min2 = minRadius * minRadius;
        double r = Math.sqrt(min2 + random.nextDouble() * (maxRadius * maxRadius - min2));
        double angle = random.nextDouble() * Math.PI * 2;
        return new Point((int) Math.floor(centerX + r * Math.cos(angle)), (int) Math.floor(centerZ + r * Math.sin(angle)));
    }

    /** Whether the centre of a block column lies inside the ring. */
    static boolean inRing(int x, int z, double centerX, double centerZ, double minRadius, double maxRadius) {
        double dx = x + 0.5 - centerX;
        double dz = z + 0.5 - centerZ;
        double d2 = dx * dx + dz * dz;
        return d2 >= minRadius * minRadius && d2 <= maxRadius * maxRadius;
    }
}
