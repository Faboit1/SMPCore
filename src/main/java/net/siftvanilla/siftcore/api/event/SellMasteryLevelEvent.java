package net.siftvanilla.siftcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired after a sale was stored when it raised the player's sell mastery level in a category. Informational: the
 * level already changed. Fired on the player's thread.
 */
public final class SellMasteryLevelEvent extends SiftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String category;
    private final int previousLevel;
    private final int level;
    private final double multiplier;

    public SellMasteryLevelEvent(Player player, String category, int previousLevel, int level, double multiplier) {
        this.player = player;
        this.category = category;
        this.previousLevel = previousLevel;
        this.level = level;
        this.multiplier = multiplier;
    }

    public Player player() {
        return this.player;
    }

    /** The sell category id, e.g. {@code mining}. */
    public String category() {
        return this.category;
    }

    public int previousLevel() {
        return this.previousLevel;
    }

    public int level() {
        return this.level;
    }

    /** The player's multiplier for the category now (rank plus mastery). */
    public double multiplier() {
        return this.multiplier;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
