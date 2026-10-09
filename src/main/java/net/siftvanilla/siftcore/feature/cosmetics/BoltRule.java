package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * When the lightning kill effect may strike its visual bolt. Unlike particles and sounds, which go to a list of
 * receivers, the bolt is a real (harmless) entity: every client that tracks it sees the bolt and the sky flash and
 * hears the thunder. A client tracks it up to the entity tracking range ({@code entity-tracking-range.other} in
 * spigot.yml, 64 blocks by default), measured across the ground (height does not count), and never beyond the chunks
 * it is sent (the view distance). So the bolt only strikes when nobody within the larger of the effect range and the
 * view distance, plus room for players moving while it flashes, turned kill effects off; otherwise the effect shows
 * its sparks and thunder to the players who want them. Pure, so it is unit tested.
 */
final class BoltRule {

    /** Blocks added for players who move while the bolt flashes (up to about a second and a half). */
    static final double MARGIN = 16.0;

    private BoltRule() {
    }

    /**
     * How far from the bolt a player may be and still see it.
     *
     * @param range              the kill effect range (blocks)
     * @param viewDistanceChunks the largest view or send distance of the world (chunks)
     */
    static double reach(int range, int viewDistanceChunks) {
        return Math.max(range, Math.max(0, viewDistanceChunks) * 16.0) + MARGIN;
    }

    /**
     * Whether the bolt may strike at ({@code x}, {@code z}): nobody within {@code reach} of it, across the ground,
     * turned kill effects off.
     *
     * @param players      everyone in the world
     * @param x            where a player stands (east-west)
     * @param z            where a player stands (north-south)
     * @param wantsEffects whether a player wants to see kill effects
     */
    static <T> boolean clear(double boltX, double boltZ, double reach, Iterable<T> players, ToDoubleFunction<T> x, ToDoubleFunction<T> z,
                             Predicate<T> wantsEffects) {
        double reachSquared = reach * reach;
        for (T player : players) {
            double dx = x.applyAsDouble(player) - boltX;
            double dz = z.applyAsDouble(player) - boltZ;
            if (dx * dx + dz * dz <= reachSquared && !wantsEffects.test(player)) {
                return false;
            }
        }
        return true;
    }
}
