package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Fired before a player pays another player with /pay. Cancelling stops the payment. */
public final class PlayerPayEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID from;
    private final UUID to;
    private final long amount;

    public PlayerPayEvent(UUID from, UUID to, long amount) {
        this.from = from;
        this.to = to;
        this.amount = amount;
    }

    public UUID from() {
        return this.from;
    }

    public UUID to() {
        return this.to;
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
