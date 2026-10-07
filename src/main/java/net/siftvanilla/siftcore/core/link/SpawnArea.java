package net.siftvanilla.siftcore.core.link;

import org.bukkit.Location;

/** The protected spawn region. Implemented by the teleport feature (spawn); used by RTP, combat and AFK. */
public interface SpawnArea {

    SpawnArea NONE = location -> false;

    /** True inside the protected spawn region. Pure arithmetic, safe from any thread. */
    boolean contains(Location location);
}
