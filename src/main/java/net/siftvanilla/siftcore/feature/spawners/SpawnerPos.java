package net.siftvanilla.siftcore.feature.spawners;

import java.util.Objects;

/** Where a spawner block is: world name and block coordinates. Bukkit-free. */
record SpawnerPos(String world, int x, int y, int z) {

    SpawnerPos {
        Objects.requireNonNull(world);
    }

    /** The chunk this block is in. */
    ChunkKey chunk() {
        return new ChunkKey(this.world, this.x >> 4, this.z >> 4);
    }

    /** Squared distance from the block centre to a point. */
    double distanceSquared(double px, double py, double pz) {
        double dx = this.x + 0.5 - px;
        double dy = this.y + 0.5 - py;
        double dz = this.z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** {@code x, y, z} for text. */
    String coordinates() {
        return this.x + ", " + this.y + ", " + this.z;
    }

    /** A chunk of one world. */
    record ChunkKey(String world, int x, int z) {
    }
}
