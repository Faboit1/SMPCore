package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Fired before a player joins a team. Cancelling keeps them out (an invite stays usable until it runs out). */
public final class TeamJoinEvent extends SiftCancellableEvent {

    /** How the player is joining. */
    public enum Cause {
        /** They accepted an invite. */
        INVITE,
        /** Staff added them with {@code /team admin add}. */
        STAFF
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final long team;
    private final String teamName;
    private final UUID player;
    private final Cause cause;

    public TeamJoinEvent(long team, String teamName, UUID player, Cause cause) {
        this.team = team;
        this.teamName = teamName;
        this.player = player;
        this.cause = cause;
    }

    public long team() {
        return this.team;
    }

    public String teamName() {
        return this.teamName;
    }

    public UUID player() {
        return this.player;
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
