package net.siftvanilla.siftcore.feature.teams;

import java.util.Objects;

/** A team's home: a world name and a position. Pure data; resolved to a Bukkit location only when teleporting. */
public record TeamHome(String world, double x, double y, double z, float yaw, float pitch) {

    public TeamHome {
        Objects.requireNonNull(world);
        if (world.isBlank() || world.length() > 64) {
            throw new IllegalArgumentException("World name must be 1-64 characters");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Home coordinates must be finite");
        }
    }

    public int blockX() {
        return (int) Math.floor(this.x);
    }

    public int blockY() {
        return (int) Math.floor(this.y);
    }

    public int blockZ() {
        return (int) Math.floor(this.z);
    }
}
