package net.siftvanilla.siftcore.feature.spawn;

/**
 * A square world border: its centre and full width (the vanilla {@code size}, so a 10000 border reaches 5000 blocks
 * from the centre in every direction). Pure value, used by the spawn feature to apply borders and by random
 * teleport to check that its rings fit inside them.
 */
public record BorderSpec(double centerX, double centerZ, double size) {

    public BorderSpec {
        if (!(size > 0) || !Double.isFinite(size) || !Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
            throw new IllegalArgumentException("A border needs a finite centre and a positive size");
        }
    }

    /** Distance from the centre to each edge. */
    public double halfSize() {
        return this.size / 2.0;
    }

    /** True when the point is at least {@code margin} blocks inside the border. */
    public boolean inside(double x, double z, double margin) {
        double reach = halfSize() - margin;
        return Math.abs(x - this.centerX) <= reach && Math.abs(z - this.centerZ) <= reach;
    }
}
