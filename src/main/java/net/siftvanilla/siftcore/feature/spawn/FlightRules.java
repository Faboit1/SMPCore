package net.siftvanilla.siftcore.feature.spawn;

/**
 * The pure rules of flying at spawn: how high flight goes, and when a fall that began with flight turning off in the
 * air is over (so its protection ends). Bukkit-free, so they are unit tested.
 */
final class FlightRules {

    /**
     * The longest a fall stays protected, however it goes. Only a backstop: a fall from the flight ceiling lands in a
     * few seconds, and a fall that ends any other way (water, a ladder, an elytra, flying again) ends the protection
     * at once.
     */
    static final long FALL_LIMIT_MILLIS = 60_000;

    private FlightRules() {
    }

    /**
     * The highest a player may fly: {@code maxHeight} blocks above the spawn point. Above it flight drops them, so
     * nobody can climb high inside spawn and glide or fall far out of it.
     */
    static double ceiling(double spawnY, int maxHeight) {
        return spawnY + maxHeight;
    }

    /** Whether a player at {@code y} is above the ceiling. */
    static boolean aboveCeiling(double y, double ceiling) {
        return y > ceiling;
    }

    /**
     * One look at a falling player.
     *
     * @param onGround     the player stands on something (as the server last heard)
     * @param solidBelow   the block right under their feet is not passable
     * @param inLiquid     in water or lava (a fall into it does no damage)
     * @param climbing     on a ladder, vine or scaffolding
     * @param gliding      flying an elytra: they steer their own landing now
     * @param riding       sitting in a vehicle or on a mount
     * @param flying       flying again (creative, or flight came back)
     * @param fallDistance how far they have fallen so far (resets whenever a fall ends)
     */
    record Sample(boolean onGround, boolean solidBelow, boolean inLiquid, boolean climbing, boolean gliding, boolean riding,
                  boolean flying, float fallDistance) {
    }

    /**
     * A protected fall: the next fall damage is taken away until the player lands, however long the fall takes (up to
     * {@link #FALL_LIMIT_MILLIS}). It ends without a landing too, as soon as the fall ends some other way, so the
     * protection never lingers for a later fall. Not thread-safe: the player's thread only.
     */
    static final class Fall {

        private final long until;
        private float lastDistance;

        /**
         * @param now      when flight turned off, epoch millis
         * @param distance how far the player had fallen then (usually 0: flying resets it)
         */
        Fall(long now, float distance) {
            this.until = now + FALL_LIMIT_MILLIS;
            this.lastDistance = distance;
        }

        /** Whether the fall is over, so the protection ends: the player landed or is safe some other way. */
        boolean over(Sample sample, long now) {
            if (now > this.until) {
                return true;
            }
            if (sample.flying() || sample.gliding() || sample.riding() || sample.inLiquid() || sample.climbing()) {
                return true;
            }
            if (sample.onGround() && sample.solidBelow()) {
                // Standing again. Fall damage is dealt in the same step the server learns they landed, so it already
                // came (and was taken away) by the time this is seen.
                return true;
            }
            if (sample.fallDistance() < this.lastDistance) {
                // The fall distance was reset: the fall ended (slime, a cobweb, powder snow, a teleport).
                return true;
            }
            this.lastDistance = sample.fallDistance();
            return false;
        }
    }
}
