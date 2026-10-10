package net.siftvanilla.siftcore.feature.staff;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/** What a frozen player may still do: look around, chat, and use the allowed commands. Pure logic. */
final class FreezeRules {

    /** Teleports a frozen player never makes, wherever they lead: pearls, chorus fruit, portals and spectating. */
    private static final Set<TeleportCause> NEVER = EnumSet.of(TeleportCause.ENDER_PEARL, TeleportCause.CONSUMABLE_EFFECT,
        TeleportCause.NETHER_PORTAL, TeleportCause.END_PORTAL, TeleportCause.END_GATEWAY, TeleportCause.SPECTATE);

    /** How far (squared, in blocks) a plugin teleport of a frozen player may go: it only re-sends where they stand. */
    private static final double PLUGIN_REACH_SQUARED = 1.0;

    private FreezeRules() {
    }

    /**
     * Whether a frozen player's teleport is refused. Pearls thrown before the freeze, chorus fruit, portals and
     * spectating always are; staff teleports (commands) never are. A plugin teleport is refused when it would take
     * the player away (another world, or more than a block): on Canvas a plugin teleport to where the player stands is
     * how the server applies a changed or cancelled move, which is how the freeze keeps them in place (and updates
     * where they look), so that one must go through.
     */
    static boolean teleportRefused(TeleportCause cause, boolean sameWorld, double distanceSquared) {
        if (NEVER.contains(cause)) {
            return true;
        }
        return cause == TeleportCause.PLUGIN && (!sameWorld || distanceSquared > PLUGIN_REACH_SQUARED);
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
