package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player kill is about to be counted: it passed the anti-farm rules (not the same team, not the same
 * IP, not the same pair again too soon). Cancelling it records the kill as not counted: no kill or streak for the
 * killer and no bounty claim. Bounties are claimed by a listener of this event at the monitor priority.
 * <p>
 * Fired on the victim's thread while their death is processed. The killer may be offline (the victim fell after
 * being hit by someone who logged out) or owned by another region.
 */
public final class PlayerKillCreditEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID killer;
    private final Player victim;
    private final boolean combatLog;

    public PlayerKillCreditEvent(UUID killer, Player victim, boolean combatLog) {
        this.killer = killer;
        this.victim = victim;
        this.combatLog = combatLog;
    }

    /** The player who gets the kill. */
    public UUID killer() {
        return this.killer;
    }

    /** The killer if they are online, otherwise null. */
    public Player killerPlayer() {
        return Bukkit.getPlayer(this.killer);
    }

    public Player victim() {
        return this.victim;
    }

    /** True when the victim died because they left the server in combat. */
    public boolean combatLog() {
        return this.combatLog;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
