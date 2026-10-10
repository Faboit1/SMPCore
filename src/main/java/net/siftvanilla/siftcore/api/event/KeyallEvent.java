package net.siftvanilla.siftcore.api.event;

import java.util.List;
import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before a keyall hands out keys, once per keyall (scheduled or started by staff). Cancelling skips this
 * keyall: nobody gets keys, and a scheduled keyall still moves on to its next time. Fired on the global region
 * thread for scheduled keyalls and on the command sender's thread for keyalls started by a command.
 */
public final class KeyallEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String crate;
    private final int amount;
    private final List<UUID> recipients;
    private final boolean scheduled;
    private final String actor;

    /**
     * @param crate      the crate whose keys are given
     * @param amount     keys per player
     * @param recipients the players who will get them
     * @param scheduled  true for the timed keyall, false for one started by a command
     * @param actor      who started it: {@code system} for the timed keyall, a player UUID or {@code console}
     */
    public KeyallEvent(String crate, int amount, List<UUID> recipients, boolean scheduled, String actor) {
        this.crate = crate;
        this.amount = amount;
        this.recipients = List.copyOf(recipients);
        this.scheduled = scheduled;
        this.actor = actor;
    }

    /** The crate id. */
    public String crate() {
        return this.crate;
    }

    /** Keys per player. */
    public int amount() {
        return this.amount;
    }

    /** The players who will get keys (online and, unless configured otherwise, not vanished). */
    public List<UUID> recipients() {
        return this.recipients;
    }

    public boolean scheduled() {
        return this.scheduled;
    }

    public String actor() {
        return this.actor;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
