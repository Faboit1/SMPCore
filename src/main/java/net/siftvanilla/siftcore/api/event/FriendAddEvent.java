package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before two players become friends, on the acting player's thread (or the global thread for staff).
 * Cancelling keeps them apart and changes nothing.
 */
public final class FriendAddEvent extends SiftCancellableEvent {

    /** How the friendship comes about. */
    public enum Cause {
        /** {@code player} accepted {@code friend}'s request. */
        REQUEST,
        /** {@code player} sent a request to {@code friend}, who had already sent one to them. */
        MUTUAL,
        /** Staff added the friendship with {@code /sift friends add}. */
        STAFF
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final UUID friend;
    private final Cause cause;

    public FriendAddEvent(UUID player, UUID friend, Cause cause) {
        this.player = player;
        this.friend = friend;
        this.cause = cause;
    }

    /** The accepting player, or the first player named by staff. */
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
