package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player leaves a team or is removed from it. Cancelling keeps them in the team. Not fired for the
 * members of a team that is disbanded (see {@link TeamDisbandEvent}).
 */
public final class TeamLeaveEvent extends SiftCancellableEvent {

    /** Why the player is leaving. */
    public enum Reason {
        /** They left on their own. */
        LEFT,
        /** The owner or an admin removed them. */
        KICKED,
        /** Staff removed them with {@code /team admin kick}. */
        STAFF
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final long team;
    private final String teamName;
    private final UUID player;
    private final Reason reason;
    private final UUID actor;

    public TeamLeaveEvent(long team, String teamName, UUID player, Reason reason, UUID actor) {
        this.team = team;
        this.teamName = teamName;
        this.player = player;
        this.reason = reason;
        this.actor = actor;
    }

    public long team() {
        return this.team;
    }

    public String teamName() {
        return this.teamName;
    }

    /** The player leaving. */
    public UUID player() {
        return this.player;
    }

    public Reason reason() {
        return this.reason;
    }

    /** Who caused it: the player themselves, the member who removed them, or null for staff and the console. */
    public UUID actor() {
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
