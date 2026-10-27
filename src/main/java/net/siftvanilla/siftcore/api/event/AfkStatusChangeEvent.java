package net.siftvanilla.siftcore.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired after a player became AFK or came back. Informational (a tab list or scoreboard can refresh the player's AFK
 * mark at once instead of waiting for its next update). Fired on the player's thread, or on the chat thread when a
 * chat message brought the player back, so check {@link #isAsynchronous()} before touching world state.
 */
public final class AfkStatusChangeEvent extends SiftEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final boolean afk;
    private final boolean manual;

    /**
     * @param afk    true when the player is AFK now, false when they came back
     * @param manual true when the player used /afk (going AFK or coming back), false when it happened by itself
     */
    public AfkStatusChangeEvent(Player player, boolean afk, boolean manual) {
        this.player = player;
        this.afk = afk;
        this.manual = manual;
    }

    public Player player() {
        return this.player;
    }

    /** True when the player is AFK now, false when they came back. */
    public boolean afk() {
        return this.afk;
    }

    /** True when it happened through /afk, false when activity or inactivity caused it. */
    public boolean manual() {
        return this.manual;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
