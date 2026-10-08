package net.siftvanilla.siftcore.feature.staff;

import java.util.Set;

/** What a frozen player may still do: look around, chat, and use the allowed commands. Pure logic. */
final class FreezeRules {

    private FreezeRules() {
    }

    /**
     * True when a move changes the player's position. Turning the head keeps x, y and z exactly the same (the
     * client sends a rotation-only packet and the server keeps the old position), so any difference at all is a
     * position change; there is no tolerance a player could creep through.
     */
    static boolean changesPosition(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        return Double.compare(fromX, toX) != 0 || Double.compare(fromY, toY) != 0 || Double.compare(fromZ, toZ) != 0;
    }

    /** True when the typed command may run while frozen. */
    static boolean commandAllowed(String commandLine, Set<String> allowed) {
        return CommandLabels.matches(commandLine, allowed);
    }
}
