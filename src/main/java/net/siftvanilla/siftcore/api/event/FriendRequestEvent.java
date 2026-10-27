package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired on the sender's thread for every friend request that passed the sender-side checks, before anything is
 * stored. Cancelling stops the request. Whether the target will actually see it (it may be hidden because they
 * ignore the sender, already have many requests, or recently denied this sender) is deliberately not exposed.
 */
public final class FriendRequestEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID sender;
    private final UUID target;

    public FriendRequestEvent(UUID sender, UUID target) {
        this.sender = sender;
        this.target = target;
    }

    public UUID sender() {
        return this.sender;
    }

    public UUID target() {
        return this.target;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
