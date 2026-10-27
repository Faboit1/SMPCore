package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Fired before a player puts money on another player's head. Cancelling it stops the bounty; nothing is paid. */
public final class BountyPlaceEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID sponsor;
    private final UUID target;
    private final long amount;

    public BountyPlaceEvent(UUID sponsor, UUID target, long amount) {
        this.sponsor = sponsor;
        this.target = target;
        this.amount = amount;
    }

    /** Who pays. */
    public UUID sponsor() {
        return this.sponsor;
    }

    /** Whose head the money goes on. */
    public UUID target() {
        return this.target;
    }

    public long amount() {
        return this.amount;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
