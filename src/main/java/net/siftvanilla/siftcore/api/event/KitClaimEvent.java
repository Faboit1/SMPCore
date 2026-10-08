package net.siftvanilla.siftcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player gets a kit: when they claim it ({@code /kit <name>} or the kits dialog), and when staff give
 * it to them with {@code /kits give} ({@link #forced()}). Cancelling stops it: nothing is given and the kit's cooldown
 * does not start. Fired on the player's thread. Not fired for kits given to players who are offline.
 */
public final class KitClaimEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String kit;
    private final boolean forced;

    /**
     * @param player who gets the kit
     * @param kit    the kit id from {@code features/kits.yml}
     * @param forced true when staff give the kit (no permission or cooldown check, the cooldown does not start)
     */
    public KitClaimEvent(Player player, String kit, boolean forced) {
        this.player = player;
        this.kit = kit;
        this.forced = forced;
    }

    public Player player() {
        return this.player;
    }

    /** The kit id. */
    public String kit() {
        return this.kit;
    }

    /** True when staff give the kit with {@code /kits give}. */
    public boolean forced() {
        return this.forced;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
