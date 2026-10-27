package net.siftvanilla.siftcore.feature.afk;

import net.siftvanilla.siftcore.feature.afk.ZoneBox.Corner;
import net.siftvanilla.siftcore.feature.afk.ZoneBox.Point;

/**
 * Where the AFK zone is, as configured: two corners in a world, either as world coordinates or as offsets from the
 * world's spawn point (so the default zone follows {@code /setspawn}), and an optional arrival point.
 *
 * @param arrival where /afkzone teleports to (an offset like the corners when anchored to the spawn), or null for the
 *                ground in the middle of the zone
 */
record ZoneSpec(Anchor anchor, String world, Corner from, Corner to, Point arrival) {

    /** What the corners are relative to. */
    enum Anchor {
        /** Offsets from the world's spawn point. */
        SPAWN,
        /** World coordinates. */
        ABSOLUTE
    }

    /** The zone for a world whose spawn point is at the given block. */
    ZoneBox resolve(int spawnX, int spawnY, int spawnZ) {
        if (this.anchor == Anchor.ABSOLUTE) {
            return ZoneBox.of(this.world, this.from, this.to, this.arrival);
        }
        Point arrivalPoint = this.arrival == null ? null : this.arrival.offset(spawnX, spawnY, spawnZ);
        return ZoneBox.of(this.world, this.from.offset(spawnX, spawnY, spawnZ), this.to.offset(spawnX, spawnY, spawnZ), arrivalPoint);
    }

    String describe() {
        String where = this.anchor == Anchor.SPAWN ? "around the spawn of " + this.world : "in " + this.world;
        return where + " from " + this.from.format() + " to " + this.to.format();
    }
}
