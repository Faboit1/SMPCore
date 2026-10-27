package net.siftvanilla.siftcore.feature.displays;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Where a display stands: a world name, the bottom centre of the text, and the way a fixed display faces
 * ({@code yaw} 0 south, 90 west, 180 north, -90 east). Pure data, safe on any thread.
 */
public record DisplayPosition(String world, double x, double y, double z, float yaw) {

    public int chunkX() {
        return (int) Math.floor(this.x) >> 4;
    }

    public int chunkZ() {
        return (int) Math.floor(this.z) >> 4;
    }

    /** Same world and coordinates; the facing may differ (a facing change is applied in place). */
    public boolean samePlace(DisplayPosition other) {
        return other != null && this.world.equals(other.world)
            && Double.compare(this.x, other.x) == 0
            && Double.compare(this.y, other.y) == 0
            && Double.compare(this.z, other.z) == 0;
    }

    /** A coordinate as short text: two decimals at most, trailing zeros dropped ({@code 12.5}, {@code 64}). */
    public static String format(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        String text = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return text.equals("-0") ? "0" : text;
    }

    /** Rounds a coordinate to hundredths so stored positions stay readable. */
    public static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * The facing that makes a fixed display face someone looking along {@code viewerYaw}, snapped to 45 degrees so
     * displays placed by eye line up with the blocks around them. Always in [-180, 180).
     */
    public static float facing(float viewerYaw) {
        double opposite = viewerYaw + 180.0;
        double snapped = Math.round(opposite / 45.0) * 45.0;
        double normalized = ((snapped % 360.0) + 360.0) % 360.0;
        return (float) (normalized >= 180.0 ? normalized - 360.0 : normalized);
    }
}
