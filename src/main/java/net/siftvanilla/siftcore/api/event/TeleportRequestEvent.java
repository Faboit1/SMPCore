package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before a teleport request (/tpa or /tpahere) reaches its target. Cancelling stops the request; the sender
 * is told they can't send one to that player (for example because the target ignores them).
 */
public final class TeleportRequestEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID sender;
    private final UUID target;
    private final boolean here;

    public TeleportRequestEvent(UUID sender, UUID target, boolean here) {
        this.sender = sender;
        this.target = target;
        this.here = here;
    }

    public UUID sender() {
        return this.sender;
    }

    public UUID target() {
        return this.target;
    }

    /** True for /tpahere (the target would come to the sender), false for /tpa (the sender goes to the target). */
    public boolean here() {
        return this.here;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
