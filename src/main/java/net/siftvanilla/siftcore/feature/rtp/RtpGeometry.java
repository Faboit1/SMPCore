package net.siftvanilla.siftcore.feature.rtp;

import java.util.Locale;
import net.siftvanilla.siftcore.feature.spawn.BorderSpec;

/** How a random teleport ring relates to a square world border. Pure arithmetic. */
final class RtpGeometry {

    private RtpGeometry() {
    }

    /**
     * The largest radius a full circle around {@code (centerX, centerZ)} can have and still stay at least
     * {@code margin} blocks inside the border. Negative when the centre itself is too close to (or past) the edge.
     */
    static double reach(BorderSpec border, double centerX, double centerZ, double margin) {
        double offset = Math.max(Math.abs(centerX - border.centerX()), Math.abs(centerZ - border.centerZ()));
        return border.halfSize() - margin - offset;
    }

    /** The outer radius actually usable right now: the configured one, cut down to what fits inside the border. */
    static double usableMax(double maxRadius, BorderSpec border, double centerX, double centerZ, double margin) {
        return border == null ? maxRadius : Math.min(maxRadius, reach(border, centerX, centerZ, margin));
    }

    /**
     * Explains why a ring does not fit inside a border, or returns null when it fits. The text is written for the
     * config problem list ({@code features/rtp.yml: 'regions.overworld.max-radius' <text>}).
     */
    static String problem(String world, BorderSpec border, double centerX, double centerZ, int minRadius, int maxRadius, int margin) {
        double reach = reach(border, centerX, centerZ, margin);
        if (maxRadius <= reach) {
            return null;
        }
        String where = String.format(Locale.ROOT, "the world border of '%s' (%,.0f wide around %s) leaves room for at most %s around %s "
                + "while keeping %d blocks from the border",
            world, border.size(), xz(border.centerX(), border.centerZ()), reach <= 0 ? "nothing" : String.format(Locale.ROOT, "%,d", (long) Math.floor(reach)),
            xz(centerX, centerZ), margin);
        if (reach <= minRadius) {
            return String.format(Locale.ROOT, "is %,d (ring %,d to %,d), but %s, which is not even the minimum radius; lower min-radius "
                + "and max-radius, move the centre, or grow the border", maxRadius, minRadius, maxRadius, where);
        }
        return String.format(Locale.ROOT, "is %,d, but %s; lower it to %,d or grow the border by %,d", maxRadius, where,
            (long) Math.floor(reach), (long) Math.ceil((maxRadius - reach) * 2));
    }

    private static String xz(double x, double z) {
        return String.format(Locale.ROOT, "%.0f, %.0f", x, z);
    }
}
