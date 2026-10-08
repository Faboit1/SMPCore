package net.siftvanilla.siftcore.feature.homes;

/**
 * Whether a home still looks safe to arrive at: someone may have poured lava, lit a fire or built a wall where it
 * was set. Only the two blocks the player occupies matter. A solid block at the feet is normal (a home set on a slab,
 * stairs or a carpet stands inside that block); at the head it would suffocate. Bukkit-free so it is unit tested.
 */
final class HomeSafety {

    /** What one block means for a player standing in it. */
    enum Kind {
        /** Air, plants, water, slabs... nothing that hurts. */
        CLEAR,
        LAVA,
        /** Fire, soul fire or a lit campfire. */
        FIRE,
        /** A full block that fills the space (suffocates at head height). */
        FULL
    }

    /** Why a home looks unsafe; {@link #NONE} when it doesn't. */
    enum Danger {
        NONE,
        LAVA,
        FIRE,
        BLOCKED
    }

    private HomeSafety() {
    }

    static Danger danger(Kind feet, Kind head) {
        if (feet == Kind.LAVA || head == Kind.LAVA) {
            return Danger.LAVA;
        }
        if (feet == Kind.FIRE || head == Kind.FIRE) {
            return Danger.FIRE;
        }
        if (head == Kind.FULL) {
            return Danger.BLOCKED;
        }
        return Danger.NONE;
    }
}
