package net.siftvanilla.siftcore.api.event;

import java.util.UUID;
import org.bukkit.event.HandlerList;

/** Fired before a player creates a team (and pays for it). Cancelling stops the creation; nothing is charged. */
public final class TeamCreateEvent extends SiftCancellableEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID owner;
    private final String name;
    private final long cost;

    public TeamCreateEvent(UUID owner, String name, long cost) {
        this.owner = owner;
        this.name = name;
        this.cost = cost;
    }

    /** The player creating the team, who becomes its owner. */
    public UUID owner() {
        return this.owner;
    }

    /** The team name as typed (already validated). */
    public String name() {
        return this.name;
    }

    /** What creating the team costs, in whole money units. */
    public long cost() {
        return this.cost;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
