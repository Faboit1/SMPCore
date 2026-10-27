package net.siftvanilla.siftcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player opens a crate with a key, after the reward was drawn and before the key is spent.
 * Cancelling stops the opening: the key is kept and nothing is given. Fired on the player's thread.
 */
public final class CrateOpenEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String crate;
    private final String reward;
    private final String display;
    private final String rarity;

    /**
     * @param player  who opens the crate
     * @param crate   the crate id
     * @param reward  the drawn reward's id
     * @param display plain text naming the reward ({@code 8 diamonds})
     * @param rarity  the reward's rarity id
     */
    public CrateOpenEvent(Player player, String crate, String reward, String display, String rarity) {
        this.player = player;
        this.crate = crate;
        this.reward = reward;
        this.display = display;
        this.rarity = rarity;
    }

    public Player player() {
        return this.player;
    }

    /** The crate id. */
    public String crate() {
        return this.crate;
    }

    /** The id of the reward that was drawn. */
    public String reward() {
        return this.reward;
    }

    /** Plain text naming the reward. */
    public String display() {
        return this.display;
    }

    /** The rarity id of the reward. */
    public String rarity() {
        return this.rarity;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
