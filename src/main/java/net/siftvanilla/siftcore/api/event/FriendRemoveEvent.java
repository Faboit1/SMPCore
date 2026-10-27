package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before a friendship ends, on the acting player's thread (or the global thread for staff). Cancelling keeps
 * the friendship and changes nothing. The other side is never told about a removal.
 */
public final class FriendRemoveEvent extends SiftCancellableEvent {

    /** Who ends the friendship. */
    public enum Cause {
        /** {@code player} removed {@code friend}. */
        PLAYER,
        /** Staff removed it with {@code /sift friends remove}. */
        STAFF
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final UUID friend;
    private final Cause cause;

    public FriendRemoveEvent(UUID player, UUID friend, Cause cause) {
        this.player = player;
        this.friend = friend;
        this.cause = cause;
    }

    public UUID player() {
        return this.player;
    }

    public UUID friend() {
        return this.friend;
    }

    public Cause cause() {
        return this.cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
