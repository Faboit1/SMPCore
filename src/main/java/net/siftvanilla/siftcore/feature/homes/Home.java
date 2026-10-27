package net.siftvanilla.siftcore.feature.homes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** One saved home. Names are lowercase (see {@link HomeNames}). Immutable. */
record Home(String name, String world, double x, double y, double z, float yaw, float pitch, long created) {

    static Home at(String name, Location location, long now) {
        return new Home(name, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch(), now);
    }

    /** The location, or null while its world is not loaded. */
    Location toLocation() {
        World loaded = Bukkit.getWorld(this.world);
        return loaded == null ? null : new Location(loaded, this.x, this.y, this.z, this.yaw, this.pitch);
    }

    int blockX() {
        return (int) Math.floor(this.x);
    }

    int blockY() {
        return (int) Math.floor(this.y);
    }

    int blockZ() {
        return (int) Math.floor(this.z);
    }
}
