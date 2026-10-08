package net.siftvanilla.siftcore.feature.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** The server spawn: a world name and an exact position with facing. Immutable and thread-safe. */
public record SpawnPoint(String world, double x, double y, double z, float yaw, float pitch) {

    public SpawnPoint {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("A spawn point needs a world");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("A spawn point needs finite coordinates");
        }
    }

    public static SpawnPoint of(Location location) {
        return new SpawnPoint(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch());
    }

    /** The location, or null while the world is not loaded. Safe from any thread (no world access). */
    public Location toLocation() {
        World loaded = Bukkit.getWorld(this.world);
        return loaded == null ? null : new Location(loaded, this.x, this.y, this.z, this.yaw, this.pitch);
    }
}
